# shellcheck shell=bash

test_valid_version_accepts_release() {
    assert_succeeds "release version accepted" valid_version 2.7.1
}

test_valid_version_accepts_prerelease() {
    assert_succeeds "prerelease accepted" valid_version 2.8.0-rc.1
}

test_valid_version_accepts_alpha_and_beta() {
    assert_succeeds "alpha accepted" valid_version 2.8.0-alpha.1
    assert_succeeds "beta accepted" valid_version 2.8.0-beta.10
}

test_valid_version_rejects_unknown_prerelease() {
    assert_fails "unknown prerelease rejected" valid_version 2.8.0-preview.1
}

test_valid_version_rejects_leading_zero() {
    assert_fails "leading major zero rejected" valid_version 02.8.0
}

test_valid_version_rejects_legacy_prerelease() {
    assert_fails "legacy alpha rejected" valid_version 2.8.0-alpha01
    assert_fails "legacy beta rejected" valid_version 2.8.0-beta10
}

test_valid_version_rejects_branch_syntax() {
    assert_fails "slash rejected" valid_version 2.7.1/topic
}

test_version_comparison_ranks_prerelease_first() {
    assert_succeeds "prerelease precedes release" version_le 2.7.0-rc.1 2.7.0
    assert_fails "release does not precede prerelease" version_le 2.7.0 2.7.0-rc.1
}

test_version_comparison_ranks_suffixes() {
    assert_succeeds "alpha precedes beta" version_le 2.7.0-alpha.1 2.7.0-beta.1
    assert_succeeds "beta precedes rc" version_le 2.7.0-beta.1 2.7.0-rc.1
}

test_version_comparison_ranks_suffix_numbers() {
    assert_succeeds "rc numbers compare numerically" version_le 2.7.0-rc.2 2.7.0-rc.10
    assert_fails "newer rc does not precede older" version_le 2.7.0-rc.10 2.7.0-rc.2
}

test_version_comparison_ranks_core_numerically() {
    assert_succeeds "minor versions compare numerically" version_le 2.9.0 2.10.0
    assert_fails "newer minor does not precede older" version_le 2.10.0 2.9.0
}

test_repo_slug_from_ssh_url() {
    assert_eq "zcash/zcash-android-wallet-sdk" \
        "$(repo_slug_from_url git@github.com:zcash/zcash-android-wallet-sdk.git)" \
        "SSH repository slug"
}

test_repo_slug_from_fork_url() {
    assert_eq "nuttycom/zcash-android-wallet-sdk" \
        "$(repo_slug_from_url https://github.com/nuttycom/zcash-android-wallet-sdk.git)" \
        "fork repository slug"
}

test_set_gradle_property_updates_exact_key() {
    local file="$SCRATCH/gradle.properties"
    printf 'LIBRARY_VERSION=2.7.0\nOTHER_VERSION=1.0\n' > "$file"
    set_gradle_property "$file" LIBRARY_VERSION 2.7.1
    assert_eq "2.7.1" "$(gradle_property_value "$file" LIBRARY_VERSION)" \
        "Gradle version updated"
    assert_eq "1.0" "$(gradle_property_value "$file" OTHER_VERSION)" \
        "other Gradle property preserved"
}

test_bump_cargo_version_scopes_to_package() {
    local file="$SCRATCH/Cargo.toml"
    printf '[dependencies.foo]\nversion = "1.0"\n[package]\nversion = "2.7.0"\n' > "$file"
    bump_cargo_version "$file" 2.7.1
    assert_eq "2.7.1" "$(cargo_package_version "$file")" \
        "Cargo package version updated"
    assert_succeeds "dependency version preserved" grep -q 'version = "1.0"' "$file"
}

_require_gh_auth_fails() (
    # Both of these are consumed by require_gh_auth_for_run, which lives in
    # release-lib.sh; shellcheck cannot see across the source boundary from here.
    # SC2317 and SC2329 are the same finding under different shellcheck versions.
    # shellcheck disable=SC2034
    DRY_RUN="$1"
    # shellcheck disable=SC2317,SC2329
    require_gh_auth() { return 1; }
    require_gh_auth_for_run
)

test_dry_run_skips_gh_authentication() {
    assert_succeeds "dry run skips GitHub authentication" _require_gh_auth_fails true
    assert_fails "real run requires GitHub authentication" _require_gh_auth_fails false
}
