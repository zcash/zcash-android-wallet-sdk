test_valid_version_accepts_release() {
    assert_succeeds "release version accepted" valid_version 2.7.1
}

test_valid_version_accepts_prerelease() {
    assert_succeeds "prerelease accepted" valid_version 2.8.0-rc.1
}

test_valid_version_rejects_branch_syntax() {
    assert_fails "slash rejected" valid_version 2.7.1/topic
}

test_version_sort_ranks_prerelease_first() {
    local got
    got="$(printf '2.7.0\n2.7.0-rc.1\n' | version_sort | tr '\n' ' ')"
    assert_eq "2.7.0-rc.1 2.7.0 " "$got" "prerelease ordering"
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
