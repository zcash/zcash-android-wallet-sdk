#!/usr/bin/env bash
#
# Check that one hop of the maintenance chain still merges cleanly, optionally
# with a pending change applied to either side.
#
# Usage: check-merge-forward.sh <source> <target> [pr-base] [pr-ref]
#
#   source    branch merged from, e.g. maint/v2.7.x
#   target    branch merged into, e.g. maint/v2.8.x
#   pr-base   branch a pending change targets; when it is <source> or <target>
#             that side is replaced by <pr-ref>. Omit to check the branches as
#             they stand.
#   pr-ref    the pending change; defaults to HEAD (refs/pull/N/merge in CI).
#
# Branches resolve under refs/remotes/origin, so run `git fetch origin` first.
# Exits 1 only when the change introduces the conflict, 0 otherwise.

set -euo pipefail

SOURCE="${1:?usage: check-merge-forward.sh <source> <target> [pr-base] [pr-ref]}"
TARGET="${2:?usage: check-merge-forward.sh <source> <target> [pr-base] [pr-ref]}"
PR_BASE="${3:-}"
PR_REF="${4:-HEAD}"

# commit-tree needs an identity. Fall back only when git has none of its own, so
# a local run keeps the user's; git var fails exactly when commit-tree would.
if ! git var GIT_COMMITTER_IDENT >/dev/null 2>&1; then
  export GIT_AUTHOR_NAME='merge-forward check' GIT_AUTHOR_EMAIL='ci@localhost'
  export GIT_COMMITTER_NAME='merge-forward check' GIT_COMMITTER_EMAIL='ci@localhost'
fi

CONFLICTS=''

# Merge $2 into $1 in memory. Sets CONFLICTS and returns 1 on conflict.
merge_hop() {
  local into="$1" from="$2" out rc=0
  out="$(git merge-tree --write-tree --name-only "$into" "$from")" || rc=$?
  if [ "$rc" -ne 0 ]; then
    # Line 1 is the tree, then the conflicting paths up to a blank line.
    CONFLICTS="$(printf '%s\n' "$out" | tail -n +2 | sed -n '/^$/q;p')"
    return 1
  fi
}

say() {
  printf '%s\n' "$*"
  if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then printf '%s\n' "$*" >>"$GITHUB_STEP_SUMMARY"; fi
}

src="origin/${SOURCE}"
dst="origin/${TARGET}"
applied='no'
if [ -n "$PR_BASE" ]; then
  if [ "$PR_BASE" = "$SOURCE" ]; then src="$(git rev-parse "$PR_REF")"; applied='yes'; fi
  if [ "$PR_BASE" = "$TARGET" ]; then dst="$(git rev-parse "$PR_REF")"; applied='yes'; fi
fi

say "## Merge-forward: \`${SOURCE}\` -> \`${TARGET}\`"
say ''

if merge_hop "$dst" "$src"; then
  if [ "$applied" = 'yes' ]; then
    say ':white_check_mark: Still merges cleanly with this change applied.'
  elif [ -n "$PR_BASE" ]; then
    say ':white_check_mark: Merges cleanly. This change touches neither side.'
  else
    say ':white_check_mark: Merges cleanly.'
  fi
  exit 0
fi

conflicts="$CONFLICTS"

say 'Conflicting paths:'
say ''
say '```'
say "$conflicts"
say '```'
say ''

if [ "$applied" = 'no' ]; then
  say ':warning: **Pre-existing.** This change touches neither side of the hop.'
  exit 0
fi

# Re-run untouched, so a conflict the branches already carry is not blamed on
# this change.
if ! merge_hop "origin/${TARGET}" "origin/${SOURCE}"; then
  say ':warning: **Pre-existing.** This hop already conflicts without the change.'
  exit 0
fi

say ':x: **Introduced by this change.** The hop merges cleanly without it.'
say ''
say 'Advisory only. Resolve during the merge-forward, or reshape the change.'
exit 1
