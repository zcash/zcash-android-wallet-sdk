#!/usr/bin/env bash
#
# check-jni-symbols.sh: assert the JNI export surface has not changed.
#
# Kotlin binds JNI by symbol name and resolves lazily, so renaming or dropping
# an export is a RUNTIME crash rather than a compile error. Neither `cargo
# check` nor the Kotlin build catches it. This script does.
#
# It builds backend-lib as a shared library, extracts the exported `Java_*`
# symbol names, and diffs them against the committed baseline.
#
# Usage:
#   ./scripts/check-jni-symbols.sh            # verify against the baseline
#   ./scripts/check-jni-symbols.sh --update   # rewrite the baseline
#
# --update is for a change that INTENTIONALLY alters the export surface, i.e.
# adding, removing or renaming a JNI function. Run it, then commit the updated
# baseline in the same commit as the code, so the diff shows the contract
# change explicitly. A pure refactor must never need --update: if it does, the
# refactor renamed something and would have crashed at runtime.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
cd "${REPO_ROOT}"

BASELINE="backend-lib/jni-symbols.txt"

update=false
if [ $# -gt 0 ]; then
    case "$1" in
        --update) update=true ;;
        -h | --help)
            sed -n '3,25p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
            exit 0
            ;;
        *)
            echo "unknown argument: $1" >&2
            exit 2
            ;;
    esac
fi

echo "==> Building backend-lib"
(cd backend-lib && cargo build --lib)

# The crate is built as both a cdylib and a staticlib; the cdylib is the one
# that carries dynamic symbols, and its extension is platform-specific.
lib=""
for candidate in \
    backend-lib/target/debug/libzcashwalletsdk.dylib \
    backend-lib/target/debug/libzcashwalletsdk.so; do
    if [ -f "${candidate}" ]; then
        lib="${candidate}"
        break
    fi
done

if [ -z "${lib}" ]; then
    echo "error: no built cdylib found under backend-lib/target/debug/" >&2
    exit 1
fi

# Apple's nm spells "defined only" as -U; GNU binutils spells it
# --defined-only and needs -D to read the dynamic symbol table at all.
case "$(uname -s)" in
    Darwin) nm_args=(-gU) ;;
    *) nm_args=(-D --defined-only) ;;
esac

# Keep only the NAME. nm's first column is an address that changes on every
# rebuild, so diffing raw nm output never succeeds. Mach-O prefixes symbols
# with an underscore that ELF does not, so strip it for a portable baseline.
extract_symbols() {
    nm "${nm_args[@]}" "${lib}" \
        | awk '$2 == "T" { print $3 }' \
        | sed 's/^_//' \
        | grep '^Java_' \
        | sort -u
}

actual="$(mktemp)"
trap 'rm -f "${actual}"' EXIT
extract_symbols > "${actual}"

count="$(wc -l < "${actual}" | tr -d ' ')"

if [ "${count}" -eq 0 ]; then
    echo "error: extracted 0 JNI symbols from ${lib}." >&2
    echo "The extraction is broken; this is not a passing state." >&2
    exit 1
fi

if [ "${update}" = true ]; then
    cp "${actual}" "${BASELINE}"
    echo "==> Baseline updated: ${BASELINE} (${count} symbols)"
    echo "    Commit it alongside the code change that altered the surface."
    exit 0
fi

if [ ! -f "${BASELINE}" ]; then
    echo "error: ${BASELINE} does not exist." >&2
    echo "Create it with: $0 --update" >&2
    exit 1
fi

if diff -u "${BASELINE}" "${actual}" > /dev/null; then
    echo "==> JNI export surface unchanged (${count} symbols)"
    exit 0
fi

echo
echo "JNI export surface CHANGED. Kotlin binds these by name at runtime, so" >&2
echo "an unintended change here is a crash on the device, not a build error." >&2
echo >&2
diff -u "${BASELINE}" "${actual}" | sed -n '3,$p' | grep -E '^[-+]' >&2 || true
echo >&2
echo "If this was intentional, re-run with --update and commit the baseline" >&2
echo "in the same commit. If it was not, you have renamed or dropped an" >&2
echo "export and the Kotlin side will fail to resolve it." >&2
exit 1
