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
    printf '%s\n' "$1" |
        grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z]+([.-][0-9A-Za-z]+)*)?$'
}

version_sort() { sed 's/-/~/' | sort -V | sed 's/~/-/'; }

version_le() {
    [ "$(printf '%s\n%s\n' "$1" "$2" | version_sort | head -1)" = "$1" ]
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

repo_for_remote() { repo_slug_from_url "$(git remote get-url "$1")"; }
