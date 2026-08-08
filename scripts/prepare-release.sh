#!/usr/bin/env bash
#
# prepare-release.sh: prepare an SDK release and open it for review.
#
# Creates the two branches described in CONTRIBUTING.md and makes the version
# bump commit:
#
#   release/vX.Y.Z   cut from the PREVIOUS release tag. It is the base of the
#                    release PR and what eventually gets tagged.
#   candidate/vX.Y.Z cut from the revision being released. It carries the
#                    version bumps and the CHANGELOG promotion.
#
# The PR from candidate/vX.Y.Z into release/vX.Y.Z then shows exactly what this
# release adds over the previous one, with none of the intervening history.
#
# Usage:
#   ./scripts/prepare-release.sh --issue <N> [options] <remote> <version> [<revision>]
#
#   <remote>    git remote for zcash/zcash-android-wallet-sdk, e.g. upstream
#   <version>   version being released, without the leading v, e.g. 2.7.1
#   <revision>  commit or branch holding the changes to release
#               (default: current HEAD, which should be a maint/ branch)
#
# Options:
#   --issue <N>       release tracking issue; the PR body gets `Closes #N`.
#                     Required: every pull request must reference an issue.
#   --previous <tag>  base the release branch on this tag rather than the
#                     detected one. Use when the newest tag reachable from
#                     <revision> is not the release you are following.
#   --dry-run         print what would happen and change nothing.
#
# It deliberately does not tag or publish. Those happen only after a human
# reviews and merges the draft pull request this script opens.

set -euo pipefail

readonly TAG_PREFIX="v"

cd "$(git rev-parse --show-toplevel)"
# shellcheck source=lib/release-lib.sh
. "scripts/lib/release-lib.sh"

PREVIOUS=""
ISSUE=""
DRY_RUN=false
while [ $# -gt 0 ]; do
    case "$1" in
        --issue)        ISSUE="${2:?--issue needs an issue number}"; shift 2 ;;
        --previous)     PREVIOUS="${2:?--previous needs a tag}"; shift 2 ;;
        --dry-run)      DRY_RUN=true; shift ;;
        -h|--help)      sed -n '2,34p' "$0" | sed 's|^# \{0,1\}||'; exit 0 ;;
        --*)            die "unknown option '$1'" ;;
        *)              break ;;
    esac
done

[ $# -eq 2 ] || [ $# -eq 3 ] ||
    die "expected <remote> <version> and optional <revision>."
[ -n "$ISSUE" ] || die "--issue <N> is required." \
    "Every pull request must reference an issue (see CONTRIBUTING.md)."
printf '%s\n' "$ISSUE" | grep -Eq '^[0-9]+$' ||
    die "--issue must be a numeric GitHub issue number."
readonly REMOTE="$1"
readonly VERSION="${2#v}"
readonly REVISION="${3:-HEAD}"
valid_version "$VERSION" || die "invalid version '${VERSION}'." \
    "Expected semantic version syntax such as 2.7.1 or 2.8.0-rc.1."

# ---------------------------------------------------------------- preflight

step "Checking preconditions"

require_clean_tree
require_remote "$REMOTE"
require_gh_auth_for_run
readonly GH_REPO="$(repo_for_remote "$REMOTE")"
echo "  repository: ${GH_REPO}"

echo "  fetching $REMOTE ..."
git fetch --tags "$REMOTE" >/dev/null 2>&1

REV_SHA="$(git rev-parse --verify "${REVISION}^{commit}")"
echo "  releasing the content of $REVISION ($(git rev-parse --short "$REV_SHA"))"

readonly NEW_TAG="${TAG_PREFIX}${VERSION}"
if git rev-parse -q --verify "refs/tags/${NEW_TAG}" >/dev/null; then
    echo "error: ${NEW_TAG} is already tagged; pick a new version." >&2
    exit 1
fi

# ------------------------------------------------------- previous release

step "Determining the release base"

if [ -n "$PREVIOUS" ]; then
    PREV_TAG="${PREVIOUS}"
    git rev-parse -q --verify "refs/tags/${PREV_TAG}" >/dev/null || {
        echo "error: no such tag '${PREV_TAG}'." >&2; exit 1; }
    echo "  using --previous ${PREV_TAG}"
else
    # Only tags reachable from the revision are candidates: a tag on a newer
    # line is not something this release can be a successor to.
    PREV_TAG="$(git \
        -c versionsort.suffix=-alpha \
        -c versionsort.suffix=-beta \
        -c versionsort.suffix=-rc \
        -c versionsort.suffix= \
        tag --list --merged "$REV_SHA" --sort=version:refname \
        "${TAG_PREFIX}[0-9]*" | tail -1)"
    [ -n "$PREV_TAG" ] || {
        echo "error: no release tags are reachable from ${REVISION}." >&2
        echo "       pass --previous <tag> to say which release this follows." >&2
        exit 1; }
    echo "  newest release reachable from ${REVISION}: ${PREV_TAG}"
fi

PREV_VERSION="${PREV_TAG#"$TAG_PREFIX"}"
if version_le "$VERSION" "$PREV_VERSION"; then
    echo "error: ${VERSION} does not come after ${PREV_VERSION}." >&2
    exit 1
fi

readonly RELEASE_BRANCH="release/${TAG_PREFIX}${VERSION}"
readonly CANDIDATE_BRANCH="candidate/${TAG_PREFIX}${VERSION}"

for b in "$RELEASE_BRANCH" "$CANDIDATE_BRANCH"; do
    git rev-parse -q --verify "refs/heads/${b}" >/dev/null && {
        echo "error: branch ${b} already exists locally." >&2; exit 1; }
done

echo
echo "  ${RELEASE_BRANCH}  <- ${PREV_TAG}        (PR base, pushed to ${REMOTE})"
echo "  ${CANDIDATE_BRANCH}   <- ${REVISION}   (version bumps go here)"

# --------------------------------------------------------------- branches

step "Creating ${RELEASE_BRANCH} from ${PREV_TAG}"
run git branch "$RELEASE_BRANCH" "refs/tags/${PREV_TAG}"
run git push "$REMOTE" "${RELEASE_BRANCH}:${RELEASE_BRANCH}"

step "Creating ${CANDIDATE_BRANCH} from ${REVISION}"
run git switch -c "$CANDIDATE_BRANCH" "$REV_SHA"

# ------------------------------------------------------------ version bump

step "Bumping the version to ${VERSION}"

bump_versions() {
    # gradle.properties carries the version the Gradle publishing conventions
    # read. IS_SNAPSHOT is deliberately left alone: release publishing passes
    # -PIS_SNAPSHOT=false rather than committing the change.
    set_gradle_property gradle.properties LIBRARY_VERSION "$VERSION"

    # Only the [package] version; dependency versions must not be touched even
    # if another table appears before [package].
    bump_cargo_version backend-lib/Cargo.toml "$VERSION"

    # Let cargo minimally update the lock while checking that the bumped
    # manifest still resolves, without requesting dependency upgrades.
    cargo check --manifest-path backend-lib/Cargo.toml --offline >/dev/null
}

if $DRY_RUN; then
    echo "  would set LIBRARY_VERSION, backend-lib/Cargo.toml and Cargo.lock to ${VERSION}"
else
    bump_versions
    [ "$(gradle_property_value gradle.properties LIBRARY_VERSION)" = "$VERSION" ] &&
        [ "$(cargo_package_version backend-lib/Cargo.toml)" = "$VERSION" ] ||
        die "the Gradle or Cargo manifest version was not updated."
    [ "$(cargo_lock_package_version backend-lib/Cargo.lock zcash-android-wallet-sdk)" = "$VERSION" ] \
        || { echo "error: Cargo.lock package entry not updated." >&2; exit 1; }
    echo "  gradle.properties, backend-lib/Cargo.toml, backend-lib/Cargo.lock"
fi

# ------------------------------------------------------------- CHANGELOG

step "Promoting the CHANGELOG"

# Entries are written as part of the commit that makes each change, so this
# only ever promotes what is already there -- it never generates text. An
# empty Unreleased section means the entries were not written, which is much
# more likely to be an oversight than a genuinely invisible release.
if ! changelog_unreleased_nonempty CHANGELOG.md; then
    echo "error: the [Unreleased] section is empty." >&2
    echo "       Every user-visible change needs an entry before release; see" >&2
    echo "       the CHANGELOG discipline in AGENTS.md." >&2
    exit 1
fi

if $DRY_RUN; then
    echo "  would insert '## [${VERSION}] - $(date +%Y-%m-%d)' below [Unreleased]"
else
    promote_changelog CHANGELOG.md "$VERSION" "$(date +%Y-%m-%d)" ||
        die "CHANGELOG.md has no [Unreleased] heading to promote."
    echo "  ## [${VERSION}] - $(date +%Y-%m-%d)"
fi

# ----------------------------------------------------------------- commit

step "Committing"
run git add gradle.properties backend-lib/Cargo.toml backend-lib/Cargo.lock CHANGELOG.md
run git commit -m "Prepare SDK release ${VERSION}"

step "Pushing ${CANDIDATE_BRANCH}"
run git push -u "$REMOTE" "$CANDIDATE_BRANCH"

# --------------------------------------------------------------- draft PR

step "Opening the draft pull request"
if $DRY_RUN; then
    echo "  would open a draft PR ${CANDIDATE_BRANCH} -> ${RELEASE_BRANCH} on ${GH_REPO}"
    echo "  body would close issue #${ISSUE}"
else
    PR_BODY_FILE="$(mktemp)"
    cat > "$PR_BODY_FILE" <<EOF
Closes #${ISSUE}

Release \`${VERSION}\`, following \`${PREV_TAG}\`.

The base of this pull request is \`${RELEASE_BRANCH}\`, which starts out
identical to \`${PREV_TAG}\`. Its diff is therefore exactly what users receive
relative to the previous release.
EOF
    if ! gh pr create --repo "$GH_REPO" --draft \
        --base "$RELEASE_BRANCH" --head "$CANDIDATE_BRANCH" \
        --title "Release zcash-android-wallet-sdk ${VERSION}" \
        --body-file "$PR_BODY_FILE"; then
        die "gh pr create failed." \
            "${RELEASE_BRANCH} and ${CANDIDATE_BRANCH} are already pushed." \
            "The PR body remains at ${PR_BODY_FILE}; open the draft PR by hand."
    fi
    rm -f "$PR_BODY_FILE"
fi

if $DRY_RUN; then
    cat <<EOF

Dry run: nothing was changed. ${RELEASE_BRANCH} and ${CANDIDATE_BRANCH} were
not created, nothing was pushed to ${REMOTE}, and no pull request was opened.
The working tree is untouched.
EOF
else
    cat <<EOF

Done. ${RELEASE_BRANCH} and ${CANDIDATE_BRANCH} are on ${REMOTE}, and the draft
pull request is open. ${CANDIDATE_BRANCH} is checked out here.

Review the PR diff, then merge it and tag ${NEW_TAG} on ${RELEASE_BRANCH}.
Afterwards merge the release branch back into its maint/ branch and forward
along the chain, as described in CONTRIBUTING.md.
EOF
fi
