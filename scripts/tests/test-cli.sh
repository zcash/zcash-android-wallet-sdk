_prepare() { "$REPO_ROOT/scripts/prepare-release.sh" "$@"; }

test_prepare_release_is_executable() {
    assert_succeeds "prepare-release.sh is executable" \
        test -x "$REPO_ROOT/scripts/prepare-release.sh"
}

test_help_succeeds() {
    assert_succeeds "--help succeeds" _prepare --help
}

test_help_names_prepare_release() {
    assert_succeeds "help names the current script" \
        sh -c "'$REPO_ROOT/scripts/prepare-release.sh' --help | grep -q prepare-release.sh"
}

test_without_issue_fails_before_network() {
    assert_fails "missing issue fails" _prepare upstream 2.7.1
}

test_non_numeric_issue_fails_before_network() {
    assert_fails "non-numeric issue fails" _prepare --issue MOB-1 upstream 2.7.1
}

test_invalid_version_fails_before_network() {
    assert_fails "invalid version fails" _prepare --issue 1 upstream release
}

test_extra_argument_fails_before_network() {
    assert_fails "extra argument fails" \
        _prepare --issue 1 upstream 2.7.1 HEAD extra
}

test_help_mentions_draft_pr() {
    assert_succeeds "help mentions draft PR" \
        sh -c "'$REPO_ROOT/scripts/prepare-release.sh' --help | grep -qi 'draft pull request'"
}
