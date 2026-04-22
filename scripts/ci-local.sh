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
#   3. unit tests   -> test_android_modules_unit
#   4. android lint -> static_analysis_android_lint
#   5. demo app     -> demo_app_release_build
#   6. androidTest  -> (approximation of) test_android_modules_wtf
#
# Stage 6 uses a Gradle Managed Device (pixel2Target, SDK 36). It downloads an
# AVD on first run (~1.5 GB) and is the slowest stage.
#
# Usage:
#   ./scripts/ci-local.sh             # run every stage in sequence
#   ./scripts/ci-local.sh fast        # stages 1-2 only (lint + style)
#   ./scripts/ci-local.sh quick       # stages 1-3 (lint + style + unit tests)
#   ./scripts/ci-local.sh full        # all stages including androidTest (default)
#   ./scripts/ci-local.sh detekt      # run one named stage
#
# Requirements:
#   - JDK 17 or 21 (Android Gradle Plugin 8.13.x does not support JDK 25+).
#     Set JAVA_HOME if your default `java` is a different version.
#   - Android SDK installed at ANDROID_HOME or $HOME/Library/Android/sdk.
#   - For stage 6, an Apple Silicon Mac needs the `aosp` SDK-36 system image.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
cd "${REPO_ROOT}"

GRADLE="./gradlew"

stage_detekt() {
    echo "==> [1/6] detekt (static_analysis_detekt)"
    "${GRADLE}" detektAll
}

stage_ktlint() {
    echo "==> [2/6] ktlint (static_analysis_ktlint)"
    "${GRADLE}" ktlint
}

stage_unit() {
    echo "==> [3/6] unit tests (test_android_modules_unit)"
    "${GRADLE}" test
}

stage_lint() {
    echo "==> [4/6] android lint (static_analysis_android_lint)"
    "${GRADLE}" :sdk-lib:lintRelease :demo-app:lintZcashmainnetRelease
}

stage_demoapp() {
    echo "==> [5/6] demo app release build (demo_app_release_build)"
    "${GRADLE}" assembleRelease
}

stage_androidtest() {
    echo "==> [6/6] android instrumentation tests (test_android_modules_wtf approximation)"
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
    stage_unit
}

case "${1:-full}" in
    fast)         run_fast ;;
    quick)        run_quick ;;
    full)         run_all ;;
    detekt)       stage_detekt ;;
    ktlint)       stage_ktlint ;;
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
