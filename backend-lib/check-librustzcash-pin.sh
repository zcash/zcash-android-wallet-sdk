#!/bin/sh
# Verifies the librustzcash-family [patch.crates-io] pin is a single, explicit upstream git rev
# and that it agrees between this crate (backend-lib/Cargo.toml) and its slipstream-jni path
# dependency (backend-lib/slipstream-jni/Cargo.toml) -- see the header comment on each file's
# [patch.crates-io] block for the three-manifest mirror rule this enforces.
#
# Fails (exit 1) if:
#   (a) the librustzcash rev differs between the two manifests (or either pins more than one),
#   (b) the orchard rev differs between the two manifests (or either is missing one), or
#   (c) either manifest still has a `path = "...librustzcash` patch (a filesystem-path patch
#       instead of a pinned git rev).
# Warns (non-fatal) if the sibling engine repo checkout (~/Developer/zcash/slipstream/Cargo.toml)
# pins a different librustzcash rev, when that checkout is present on this machine.
#
# Usage: ./check-librustzcash-pin.sh (run from anywhere; paths are resolved relative to this
# script's own location, not the caller's working directory).

set -eu

script_dir=$(cd "$(dirname "$0")" && pwd)
backend_lib_toml="$script_dir/Cargo.toml"
jni_toml="$script_dir/slipstream-jni/Cargo.toml"
engine_toml="$HOME/Developer/zcash/slipstream/Cargo.toml"

fail=0

librustzcash_revs() {
    grep -E 'git = "https://github\.com/zcash/librustzcash"' "$1" \
        | sed -E 's/.*rev = "([0-9a-f]+)".*/\1/' \
        | sort -u
}

orchard_rev() {
    grep -E 'orchard = \{ git = "https://github\.com/zcash/orchard' "$1" \
        | sed -E 's/.*rev = "([0-9a-f]+)".*/\1/' \
        | sort -u
}

librustzcash_path_patches() {
    grep -E '^[A-Za-z_0-9]+[[:space:]]*=[[:space:]]*\{[[:space:]]*path[[:space:]]*=[[:space:]]*"[^"]*librustzcash' "$1" || true
}

if [ ! -f "$backend_lib_toml" ]; then
    echo "error: $backend_lib_toml not found" >&2
    exit 1
fi
if [ ! -f "$jni_toml" ]; then
    echo "error: $jni_toml not found" >&2
    exit 1
fi

backend_paths=$(librustzcash_path_patches "$backend_lib_toml")
jni_paths=$(librustzcash_path_patches "$jni_toml")
if [ -n "$backend_paths" ]; then
    echo "error: backend-lib/Cargo.toml still path-patches a librustzcash crate (must be a pinned git rev):" >&2
    echo "$backend_paths" >&2
    fail=1
fi
if [ -n "$jni_paths" ]; then
    echo "error: backend-lib/slipstream-jni/Cargo.toml still path-patches a librustzcash crate (must be a pinned git rev):" >&2
    echo "$jni_paths" >&2
    fail=1
fi

backend_revs=$(librustzcash_revs "$backend_lib_toml")
jni_revs=$(librustzcash_revs "$jni_toml")
backend_rev_count=$(printf '%s\n' "$backend_revs" | grep -c . || true)
jni_rev_count=$(printf '%s\n' "$jni_revs" | grep -c . || true)

if [ "$backend_rev_count" -ne 1 ]; then
    echo "error: backend-lib/Cargo.toml pins $backend_rev_count distinct librustzcash revs (expected exactly 1):" >&2
    echo "$backend_revs" >&2
    fail=1
fi
if [ "$jni_rev_count" -ne 1 ]; then
    echo "error: backend-lib/slipstream-jni/Cargo.toml pins $jni_rev_count distinct librustzcash revs (expected exactly 1):" >&2
    echo "$jni_revs" >&2
    fail=1
fi

if [ "$backend_rev_count" -eq 1 ] && [ "$jni_rev_count" -eq 1 ] && [ "$backend_revs" != "$jni_revs" ]; then
    echo "error: librustzcash rev drift -- backend-lib pins $backend_revs, slipstream-jni pins $jni_revs" >&2
    fail=1
fi

backend_orchard=$(orchard_rev "$backend_lib_toml")
jni_orchard=$(orchard_rev "$jni_toml")
if [ -z "$backend_orchard" ]; then
    echo "error: backend-lib/Cargo.toml has no git-pinned orchard rev in [patch.crates-io]" >&2
    fail=1
fi
if [ -z "$jni_orchard" ]; then
    echo "error: backend-lib/slipstream-jni/Cargo.toml has no git-pinned orchard rev in [patch.crates-io]" >&2
    fail=1
fi
if [ -n "$backend_orchard" ] && [ -n "$jni_orchard" ] && [ "$backend_orchard" != "$jni_orchard" ]; then
    echo "error: orchard rev drift -- backend-lib pins $backend_orchard, slipstream-jni pins $jni_orchard" >&2
    fail=1
fi

if [ "$fail" -ne 0 ]; then
    exit 1
fi

if [ -f "$engine_toml" ]; then
    engine_revs=$(librustzcash_revs "$engine_toml")
    engine_rev_count=$(printf '%s\n' "$engine_revs" | grep -c . || true)
    if [ "$engine_rev_count" -eq 1 ]; then
        if [ "$engine_revs" != "$backend_revs" ]; then
            echo "warning: engine repo ($engine_toml) pins librustzcash rev $engine_revs, SDK pins $backend_revs -- bump one of them" >&2
        fi
    else
        echo "warning: engine repo ($engine_toml) pins $engine_rev_count distinct librustzcash revs, could not compare" >&2
    fi
fi

echo "librustzcash pin check passed: rev $backend_revs, orchard $backend_orchard"
