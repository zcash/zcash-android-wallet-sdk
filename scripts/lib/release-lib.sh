# Shared helpers for prepare-release.sh. Source this; do not execute it.
#
# Pure transforms live here so scripts/tests/run-tests.sh can exercise them
# without a network, a git remote, or a GitHub token. Written for bash 3.2,
# which is what macOS ships.

DRY_RUN="${DRY_RUN:-false}"

step() { echo; echo "==> $*"; }

die() {
    echo "error: $1" >&2
    shift
    while [ $# -gt 0 ]; do echo "       $1" >&2; shift; done
    exit 1
}

run() {
    if [ "$DRY_RUN" = "true" ]; then
        echo "  would run: $*"
    else
        "$@"
    fi
}

valid_version() {
    printf '%s\n' "$1" | grep -Eq \
        '^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-(alpha|beta|rc)\.[0-9]+)?$'
}

parse_version() {
    local version="$1" suffix
    [[ "$version" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)(-(alpha|beta|rc)\.([0-9]+))?$ ]]
    VERSION_MAJOR="${BASH_REMATCH[1]}"
    VERSION_MINOR="${BASH_REMATCH[2]}"
    VERSION_PATCH="${BASH_REMATCH[3]}"
    suffix="${BASH_REMATCH[5]}"
    case "$suffix" in
        alpha) VERSION_RANK=1; VERSION_SEQUENCE="${BASH_REMATCH[6]}" ;;
        beta)  VERSION_RANK=2; VERSION_SEQUENCE="${BASH_REMATCH[6]}" ;;
        rc)    VERSION_RANK=3; VERSION_SEQUENCE="${BASH_REMATCH[6]}" ;;
        *)     VERSION_RANK=4; VERSION_SEQUENCE=0 ;;
    esac
}

version_le() {
    local lhs_major lhs_minor lhs_patch lhs_rank lhs_sequence
    local rhs_major rhs_minor rhs_patch rhs_rank rhs_sequence

    parse_version "$1"
    lhs_major="$VERSION_MAJOR"
    lhs_minor="$VERSION_MINOR"
    lhs_patch="$VERSION_PATCH"
    lhs_rank="$VERSION_RANK"
    lhs_sequence="$VERSION_SEQUENCE"
    parse_version "$2"
    rhs_major="$VERSION_MAJOR"
    rhs_minor="$VERSION_MINOR"
    rhs_patch="$VERSION_PATCH"
    rhs_rank="$VERSION_RANK"
    rhs_sequence="$VERSION_SEQUENCE"

    if (( lhs_major != rhs_major )); then (( lhs_major < rhs_major )); return; fi
    if (( lhs_minor != rhs_minor )); then (( lhs_minor < rhs_minor )); return; fi
    if (( lhs_patch != rhs_patch )); then (( lhs_patch < rhs_patch )); return; fi
    if (( lhs_rank != rhs_rank )); then (( lhs_rank < rhs_rank )); return; fi
    (( 10#$lhs_sequence <= 10#$rhs_sequence ))
}

repo_slug_from_url() {
    printf '%s\n' "$1" | sed -E \
        -e 's|\.git$||' \
        -e 's|^[a-z+]+://||' \
        -e 's|^[^/@]+@||' \
        -e 's|^[^/:]+[:/]||'
}

gradle_property_value() {
    awk -F= -v key="$2" '$1 == key { sub(/^[^=]*=/, ""); print; exit }' "$1"
}

set_gradle_property() {
    local file="$1" key="$2" value="$3" tmp
    tmp="$(mktemp)"
    if ! awk -v key="$key" -v value="$value" '
        !changed && index($0, key "=") == 1 {
            print key "=" value
            changed = 1
            next
        }
        { print }
        END { if (!changed) exit 1 }
    ' "$file" > "$tmp"; then
        rm -f "$tmp"
        return 1
    fi
    mv "$tmp" "$file"
}

cargo_package_version() {
    awk '
        /^\[/ { in_pkg = ($0 == "[package]") }
        in_pkg && /^version[[:space:]]*=/ {
            sub(/^version[[:space:]]*=[[:space:]]*"/, "")
            sub(/"[[:space:]]*$/, "")
            print
            exit
        }
    ' "$1"
}

cargo_lock_package_version() {
    awk -v pkg="$2" '
        /^name = / {
            name = $0
            sub(/^name = "/, "", name)
            sub(/"$/, "", name)
            next
        }
        /^version = / && name == pkg {
            sub(/^version = "/, "")
            sub(/"$/, "")
            print
            exit
        }
    ' "$1"
}

bump_cargo_version() {
    local file="$1" version="$2" tmp
    tmp="$(mktemp)"
    if ! awk -v ver="$version" '
        /^\[/ { in_pkg = ($0 == "[package]") }
        in_pkg && !changed && /^version[[:space:]]*=/ {
            print "version = \"" ver "\""
            changed = 1
            next
        }
        { print }
        END { if (!changed) exit 1 }
    ' "$file" > "$tmp"; then
        rm -f "$tmp"
        return 1
    fi
    mv "$tmp" "$file"
}

changelog_unreleased_nonempty() {
    awk '
        /^## \[Unreleased\]/ { f = 1; next }
        f && /^## \[/ { exit }
        f && /^[-*] / { found = 1 }
        END { exit !found }
    ' "$1"
}

promote_changelog() {
    local file="$1" version="$2" date="$3" tmp
    tmp="$(mktemp)"
    if ! awk -v ver="$version" -v date="$date" '
        !changed && /^## \[Unreleased\]/ {
            print
            print ""
            print "## [" ver "] - " date
            changed = 1
            next
        }
        { print }
        END { if (!changed) exit 1 }
    ' "$file" > "$tmp"; then
        rm -f "$tmp"
        return 1
    fi
    mv "$tmp" "$file"
}

require_clean_tree() {
    if [ -n "$(git status --porcelain --untracked-files=no)" ]; then
        die "the working tree has uncommitted changes." \
            "Commit or stash them before releasing."
    fi
}

require_remote() {
    if ! git remote get-url "$1" >/dev/null 2>&1; then
        die "no such remote '$1'." "Available remotes: $(git remote | tr '\n' ' ')"
    fi
}

require_gh_auth() {
    if ! command -v gh >/dev/null 2>&1; then
        die "the GitHub CLI (gh) is not installed." "See https://cli.github.com/"
    fi
    if ! gh auth status >/dev/null 2>&1; then
        die "gh is not authenticated." "Run: gh auth login"
    fi
}

require_gh_auth_for_run() {
    [ "$DRY_RUN" = "true" ] || require_gh_auth
}

repo_for_remote() { repo_slug_from_url "$(git remote get-url "$1")"; }
