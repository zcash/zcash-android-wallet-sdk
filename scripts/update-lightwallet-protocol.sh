#!/usr/bin/env bash
#
# update-lightwallet-protocol.sh: vendor the lightwallet protocol definitions.
#
# The canonical definitions live in zcash/lightwallet-protocol and are vendored
# here as a git subtree at lightwallet-client-lib/lightwallet-protocol/. That
# subtree is a verbatim copy: never edit it, or the next update conflicts.
#
# The protos this module actually compiles are *derived* from that subtree into
# lightwallet-client-lib/src/main/proto/. The derivation adds one thing upstream
# does not carry: `option java_package`. Without it protoc would generate into
# `cash.z.wallet.sdk.rpc` (from the proto `package` declaration) rather than the
# `cash.z.wallet.sdk.internal.rpc` that this SDK's public API exposes, so taking
# upstream verbatim would be a breaking change for every consumer.
#
# darkside.proto is *not* derived. It has no upstream counterpart -- it is the
# lightwalletd darkside test harness, maintained by hand in src/main/proto/.
#
# Usage:
#   ./scripts/update-lightwallet-protocol.sh v0.5.0   # pull that tag, then derive
#   ./scripts/update-lightwallet-protocol.sh --derive-only
#
# The subtree pull needs a clean working tree; the derive step does not.
# Both steps are idempotent: re-running with no upstream change leaves the
# working tree untouched.

set -euo pipefail

readonly REMOTE="git@github.com:zcash/lightwallet-protocol.git"
readonly PREFIX="lightwallet-client-lib/lightwallet-protocol"
readonly SRC_DIR="${PREFIX}/walletrpc"
readonly DEST_DIR="lightwallet-client-lib/src/main/proto"
readonly JAVA_PACKAGE="cash.z.wallet.sdk.internal.rpc"

readonly REPO_ROOT="$(git rev-parse --show-toplevel)"
cd "${REPO_ROOT}"

usage() {
    sed -n '2,26p' "$0" | sed 's|^# \{0,1\}||'
    exit "${1:-1}"
}

# Replaces DEST_DIR/<name>.proto with the subtree's copy, injecting the
# java_package option directly after the proto `package` declaration and
# prepending a banner marking the result as derived.
derive_proto() {
    local src="$1"
    local name
    name="$(basename "${src}")"
    local dest="${DEST_DIR}/${name}"

    if grep -q '^option java_package' "${src}"; then
        echo "error: ${src} already sets java_package upstream." >&2
        echo "       Upstream now carries it, so this script's injection is obsolete:" >&2
        echo "       drop the derivation and compile the subtree directly." >&2
        exit 1
    fi

    if ! grep -q '^package ' "${src}"; then
        echo "error: ${src} has no 'package' declaration to anchor the injection." >&2
        exit 1
    fi

    {
        cat <<EOF
// DERIVED FILE -- DO NOT EDIT.
//
// Generated from ${SRC_DIR}/${name} by
// scripts/update-lightwallet-protocol.sh. The only change from the vendored
// upstream copy is the added \`option java_package\` below; edit the subtree
// and re-run the script rather than editing this file.

EOF
        awk -v pkg="${JAVA_PACKAGE}" '
            { print }
            !injected && /^package / {
                print "option java_package = \"" pkg "\";"
                injected = 1
            }
        ' "${src}"
    } > "${dest}"

    echo "  derived ${dest}"
}

derive_all() {
    if [ ! -d "${SRC_DIR}" ]; then
        echo "error: ${SRC_DIR} not found; has the subtree been added?" >&2
        exit 1
    fi

    local protos=()
    while IFS= read -r p; do protos+=("${p}"); done \
        < <(find "${SRC_DIR}" -maxdepth 1 -name '*.proto' | sort)

    if [ "${#protos[@]}" -eq 0 ]; then
        echo "error: no .proto files found in ${SRC_DIR}." >&2
        exit 1
    fi

    echo "Deriving ${#protos[@]} proto file(s) from ${SRC_DIR}:"
    for p in "${protos[@]}"; do
        derive_proto "${p}"
    done
}

case "${1:-}" in
    -h|--help)
        usage 0
        ;;
    --derive-only)
        derive_all
        ;;
    '')
        usage 1
        ;;
    -*)
        echo "error: unknown option '$1'." >&2
        usage 1
        ;;
    *)
        readonly TAG="$1"
        if [ -n "$(git status --porcelain --untracked-files=no)" ]; then
            echo "error: working tree has uncommitted changes; git subtree needs it clean." >&2
            exit 1
        fi
        echo "Pulling ${REMOTE} ${TAG} into ${PREFIX}/ ..."
        git subtree pull --prefix="${PREFIX}/" "${REMOTE}" "${TAG}" --squash
        derive_all
        ;;
esac

echo
echo "Done. Review 'git diff' and rebuild:"
echo "  ./gradlew :lightwallet-client-lib:generateDebugProto"
