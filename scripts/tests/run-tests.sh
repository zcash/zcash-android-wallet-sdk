#!/usr/bin/env bash
set -u

TESTS_DIR="$(cd "$(dirname "$0")" && pwd)"
readonly TESTS_DIR
REPO_ROOT="$(cd "${TESTS_DIR}/../.." && pwd)"
readonly REPO_ROOT

# shellcheck source-path=SCRIPTDIR
# shellcheck source=../lib/release-lib.sh
. "${REPO_ROOT}/scripts/lib/release-lib.sh"

_assertions=0
_failures=0
_current=""

_fail() {
    _failures=$((_failures + 1))
    printf 'not ok - %s: %s\n' "$_current" "$*" >&2
}

assert_eq() {
    _assertions=$((_assertions + 1))
    [ "$1" = "$2" ] || _fail "$3 (expected [$1], got [$2])"
}

assert_succeeds() {
    _assertions=$((_assertions + 1))
    local label="$1"
    shift
    "$@" >/dev/null 2>&1 || _fail "$label"
}

assert_fails() {
    _assertions=$((_assertions + 1))
    local label="$1"
    shift
    "$@" >/dev/null 2>&1 && _fail "$label"
}

SCRATCH="$(mktemp -d)"
trap 'rm -rf "$SCRATCH"' EXIT

for file in "${TESTS_DIR}"/test-*.sh; do
    # shellcheck source=/dev/null
    . "$file"
done

for test_name in $(declare -F | awk '{print $3}' | grep '^test_' | sort); do
    _current="$test_name"
    "$test_name"
done

printf '%d assertions, %d failed\n' "$_assertions" "$_failures"
[ "$_failures" -eq 0 ]
