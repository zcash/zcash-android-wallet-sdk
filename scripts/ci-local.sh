#!/usr/bin/env bash
#
# ci-local.sh: run the CI checks locally before pushing.
#
# Mirrors the `.github/workflows/pull-request.yml` jobs that can be executed on
# a developer machine without paid external services. Catches failures like
# MaxLineLength, ReturnCount, lint errors, or unit-test regressions without
# burning CI minutes.
#
# Stages (fast -> slow):
#   1. detekt       -> static_analysis_detekt
#   2. ktlint       -> static_analysis_ktlint
#   3. rust         -> static_analysis_clippy + test_rust_unit
#   4. unit tests   -> test_android_modules_unit
#   5. android lint -> static_analysis_android_lint
#   6. demo app     -> demo_app_release_build
#   7. androidTest  -> (approximation of) test_android_modules_wtf
#
# Stage 7 uses a Gradle Managed Device (pixel2Target, SDK 36). It downloads an
# AVD on first run (~1.5 GB) and is the slowest stage.
#
# Usage:
#   ./scripts/ci-local.sh             # run every stage in sequence
#   ./scripts/ci-local.sh fast        # stages 1-2 only (lint + style)
#   ./scripts/ci-local.sh quick       # stages 1-4 (lint + style + rust + unit tests)
#   ./scripts/ci-local.sh full        # all stages including androidTest (default)
#   ./scripts/ci-local.sh detekt      # run one named stage
#
# Requirements:
#   - JDK 17 or 21 (Android Gradle Plugin 8.13.x does not support JDK 25+).
#     Set JAVA_HOME if your default `java` is a different version.
#   - Android SDK installed at ANDROID_HOME or $HOME/Library/Android/sdk.
#   - For stage 3, a Rust toolchain matching rust-toolchain.toml (rustup installs
#     it automatically on first cargo invocation). The first run is slow because
#     it builds ~640 crates; later runs are incremental.
#   - For stage 7, an Apple Silicon Mac needs the `aosp` SDK-36 system image.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
cd "${REPO_ROOT}"

GRADLE="./gradlew"

stage_detekt() {
    echo "==> [1/7] detekt (static_analysis_detekt)"
    "${GRADLE}" detektAll
}

stage_ktlint() {
    echo "==> [2/7] ktlint (static_analysis_ktlint)"
    "${GRADLE}" ktlint
}

# Mirrors both Rust CI jobs. Clippy only compiles the test targets, so
# `cargo test` is what actually runs them.
#
# Clippy uses `--all-targets`, not `--tests`: `--tests` lints only the lib built
# as a test harness, leaving the plain staticlib/cdylib build (the one that ships
# to Android) unlinted. `--all-targets` covers both.
#
# `cargo test` deliberately does NOT use `--all-targets`, which would exclude
# doctests (cargo#6669).
#
# `--locked` is deliberately absent here and in CI: backend-lib/Cargo.lock
# currently cannot satisfy Cargo.toml. Add it once the lockfile is reconciled.
stage_rust() {
    echo "==> [3/7] rust (static_analysis_clippy + test_rust_unit)"
    (
        cd "${REPO_ROOT}/backend-lib"
        cargo clippy --all-targets --all-features -- -W clippy::all -D warnings
        cargo test --all-features
    )
}

stage_unit() {
    echo "==> [4/7] unit tests (test_android_modules_unit)"
    "${GRADLE}" test
}

stage_lint() {
    echo "==> [5/7] android lint (static_analysis_android_lint)"
    "${GRADLE}" :sdk-lib:lintRelease :demo-app:lintZcashmainnetRelease
}

stage_demoapp() {
    echo "==> [6/7] demo app release build (demo_app_release_build)"
    "${GRADLE}" assembleRelease
}

stage_androidtest() {
    echo "==> [7/7] android instrumentation tests (test_android_modules_wtf approximation)"
    echo "    Note: CI uses testDebugWithEmulatorWtf (cloud). Local approximation runs the"
    echo "    same tests on a Gradle managed Pixel 2 (SDK 36) virtual device."
    "${GRADLE}" \
        :sdk-incubator-lib:pixel2TargetDebugAndroidTest \
        :sdk-lib:pixel2TargetDebugAndroidTest \
        :lightwallet-client-lib:pixel2TargetDebugAndroidTest \
        :backend-lib:pixel2TargetDebugAndroidTest
}

run_all() {
    stage_detekt
    stage_ktlint
    stage_rust
    stage_unit
    stage_lint
    stage_demoapp
    stage_androidtest
}

run_fast() {
    stage_detekt
    stage_ktlint
}

run_quick() {
    run_fast
    stage_rust
    stage_unit
}

case "${1:-full}" in
    fast)         run_fast ;;
    quick)        run_quick ;;
    full)         run_all ;;
    detekt)       stage_detekt ;;
    ktlint)       stage_ktlint ;;
    rust)         stage_rust ;;
    unit)         stage_unit ;;
    lint)         stage_lint ;;
    demoapp)      stage_demoapp ;;
    androidtest)  stage_androidtest ;;
    -h|--help|help)
        grep -E '^# ' "$0" | sed 's/^# \{0,1\}//'
        exit 0
        ;;
    *)
        echo "error: unknown stage '$1'" >&2
        echo "run '$0 help' for usage" >&2
        exit 2
        ;;
esac

echo
echo "==> ci-local.sh: all requested stages passed"
