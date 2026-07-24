#!/usr/bin/env bash
#
# check-jni-move.sh: prove a refactor MOVED JNI functions without editing them.
#
# scripts/check-jni-symbols.sh proves the set of exported NAMES is unchanged.
# That is necessary but not sufficient: a function can keep its name and have
# its body rewritten. This script closes that gap by comparing each `Java_*`
# function body, byte for byte, against a reference revision.
#
# Usage:
#   ./scripts/check-jni-move.sh <ref>          # summary
#   ./scripts/check-jni-move.sh <ref> --diff   # plus a diff of each change
#
# <ref> is the revision the refactor started from, e.g. the PR's base commit.
#
# Exit status is 0 when every function is byte-identical, 1 otherwise. A
# non-zero exit is NOT automatically a bug: some changes are intended. It means
# those functions need reading, and the intended edits should be small enough
# to enumerate in the commit message.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
cd "${REPO_ROOT}"

if [ $# -lt 1 ]; then
    echo "usage: $0 <ref> [--diff]" >&2
    exit 2
fi

ref="$1"
show_diff=false
if [ "${2:-}" = "--diff" ]; then
    show_diff=true
fi

RUST_DIR="backend-lib/src/main/rust"

workdir="$(mktemp -d)"
trap 'rm -rf "${workdir}"' EXIT
mkdir -p "${workdir}/base" "${workdir}/head"

# Splits a Rust source file into one file per `Java_*` export, named after the
# function. Top-level items are closed by a `}` in column 0, which rustfmt
# guarantees, so that is the terminator.
split_exports() {
    local dest="$1"
    awk -v dest="${dest}" '
        /^pub extern "C" fn Java_[A-Za-z0-9_]+/ {
            name = $0
            sub(/^pub extern "C" fn /, "", name)
            sub(/[<(].*$/, "", name)
            out = dest "/" name
            capturing = 1
        }
        capturing { print $0 > out }
        capturing && /^\}/ { capturing = 0; close(out) }
    '
}

# Base side: read the files out of the reference revision rather than the
# working tree, so this works without checking anything out.
while IFS= read -r f; do
    git show "${ref}:${f}" 2>/dev/null | split_exports "${workdir}/base" || true
done < <(git ls-tree -r --name-only "${ref}" -- "${RUST_DIR}" | grep '\.rs$')

while IFS= read -r f; do
    split_exports "${workdir}/head" < "${f}"
done < <(find "${RUST_DIR}" -name '*.rs' | sort)

base_count=$(find "${workdir}/base" -type f | wc -l | tr -d ' ')
head_count=$(find "${workdir}/head" -type f | wc -l | tr -d ' ')

if [ "${base_count}" -eq 0 ]; then
    echo "error: extracted 0 exports from ${ref}; the extraction is broken." >&2
    exit 1
fi

identical=0
changed=()
removed=()
added=()

for path in "${workdir}"/base/*; do
    name="$(basename "${path}")"
    if [ ! -f "${workdir}/head/${name}" ]; then
        removed+=("${name}")
    elif cmp -s "${path}" "${workdir}/head/${name}"; then
        identical=$((identical + 1))
    else
        changed+=("${name}")
    fi
done

for path in "${workdir}"/head/*; do
    name="$(basename "${path}")"
    [ -f "${workdir}/base/${name}" ] || added+=("${name}")
done

echo "Comparing ${head_count} exports against ${ref} (${base_count} there)."
echo "  byte-identical: ${identical}"
echo "  changed:        ${#changed[@]}"
echo "  removed:        ${#removed[@]}"
echo "  added:          ${#added[@]}"

report() {
    local label="$1"
    shift
    [ $# -eq 0 ] && return 0
    echo
    echo "${label}:"
    printf '  %s\n' "$@"
}

report "CHANGED (body differs; each needs justifying)" "${changed[@]+"${changed[@]}"}"
report "REMOVED (gone from the tree)" "${removed[@]+"${removed[@]}"}"
report "ADDED (new exports)" "${added[@]+"${added[@]}"}"

if [ "${show_diff}" = true ]; then
    for name in "${changed[@]+"${changed[@]}"}"; do
        echo
        echo "=== ${name}"
        diff -u "${workdir}/base/${name}" "${workdir}/head/${name}" || true
    done
fi

if [ "${#changed[@]}" -eq 0 ] && [ "${#removed[@]}" -eq 0 ]; then
    echo
    echo "==> Every export moved verbatim."
    exit 0
fi

exit 1
