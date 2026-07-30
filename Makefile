# Makefile for the Zcash Android wallet SDK.
#
# Every command the project needs goes through a target here, so that local
# runs and CI runs are identical. The Android side wraps ./gradlew; the Rust
# side wraps cargo in backend-lib/, which lives in this repository.
#
# Run `make help` for the target list, and `make info` for the resolved
# toolchain paths.
#
# Portability
# -----------
# Written for GNU Make 3.81, which is what macOS ships, so it avoids the
# 4.x-only features (`!=`, `$(file ...)`, `.ONESHELL`). The JDK and Android
# SDK probes below cover both macOS and Linux layouts. Nothing here assumes
# GNU coreutils: only sh, grep, awk and sort are used.
#
# Toolchain
# ---------
# The Android Gradle Plugin needs JDK 17 or 21 and does not support JDK 25+.
# A usable JDK is often installed but absent from PATH (Homebrew does not
# symlink into /Library/Java; distro JDKs live under /usr/lib/jvm), so
# JAVA_HOME and ANDROID_HOME are probed. An explicit value in the environment
# always wins; if nothing is found the variable stays empty and Gradle falls
# back to whatever is on PATH, reporting its own error if there is none.
#
# Build variant
# -------------
# The demo app has a network dimension (Zcashmainnet, Zcashtestnet). The
# library modules do not. Override per invocation:
#   make lint-android NETWORK=Zcashtestnet
#
# Detekt and git worktrees
# ------------------------
# detekt scans the whole tree, including any git worktrees left inside it
# (for example under .claude/worktrees/). Those hold other branches' sources,
# so detekt reports failures that have nothing to do with your change. Move
# them aside before running `make detekt` or `make ci-local`; the targets warn
# when they are present.

GRADLE ?= ./gradlew
CARGO ?= cargo

RUST_DIR := backend-lib

# Only the demo app is flavored; the published library modules are not.
NETWORK ?= Zcashmainnet

# Instrumentation tests run on a Gradle Managed Device. Both pixel2Min (API
# ANDROID_MIN_SDK_VERSION) and pixel2Target are defined in
# zcash-sdk.android-conventions.gradle.kts; CI runs pixel2Min, so that is the
# default here. Override to test against the target API:
#   make test-instrumented MANAGED_DEVICE=pixel2Target
MANAGED_DEVICE ?= pixel2Min

# Scoped to the four modules CI covers. The unqualified task would also pull in
# darkside-test-lib, which needs a live darkside server, and the demo-app
# modules.
ANDROID_TEST_MODULES := \
	:sdk-lib:$(MANAGED_DEVICE)DebugAndroidTest \
	:lightwallet-client-lib:$(MANAGED_DEVICE)DebugAndroidTest \
	:sdk-incubator-lib:$(MANAGED_DEVICE)DebugAndroidTest \
	:backend-lib:$(MANAGED_DEVICE)DebugAndroidTest

# maxConcurrentDevices=1 keeps a single emulator booted at a time so the
# machine is not overwhelmed by four; swiftshader_indirect is the software
# renderer a headless runner needs.
MANAGED_DEVICE_FLAGS := \
	-Pandroid.experimental.testOptions.managedDevices.maxConcurrentDevices=1 \
	-Pandroid.testoptions.manageddevices.emulator.gpu=swiftshader_indirect

# Android targets the Rust JNI backend is cross-compiled for.
RUST_ANDROID_TARGETS := \
	armv7-linux-androideabi \
	aarch64-linux-android \
	i686-linux-android \
	x86_64-linux-android

JAVA_HOME ?= $(shell \
	for d in \
		"$$(/usr/libexec/java_home -v 21 2>/dev/null)" \
		"$$(/usr/libexec/java_home -v 17 2>/dev/null)" \
		"$$HOME/.sdkman/candidates/java/current" \
		/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
		/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
		/usr/local/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
		/usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
		/usr/lib/jvm/*-21-* /usr/lib/jvm/*-17-* \
		/usr/lib/jvm/java-21-* /usr/lib/jvm/java-17-* \
		/opt/android-studio/jbr "$$HOME/android-studio/jbr" \
		"/Applications/Android Studio.app/Contents/jbr/Contents/Home"; \
	do \
		[ -n "$$d" ] || continue; \
		[ -x "$$d/bin/javac" ] || continue; \
		echo "$$d"; break; \
	done)
export JAVA_HOME

ANDROID_HOME ?= $(shell \
	for d in "$$ANDROID_SDK_ROOT" "$$HOME/Library/Android/sdk" \
		"$$HOME/Android/Sdk" /usr/lib/android-sdk /opt/android-sdk; \
	do \
		[ -n "$$d" ] || continue; \
		[ -d "$$d/platform-tools" ] || continue; \
		echo "$$d"; break; \
	done)
export ANDROID_HOME

# Gradle and Cargo parallelize internally; running two of them at once only
# contends on their own locks, so never run these targets concurrently.
.NOTPARALLEL:

.DEFAULT_GOAL := help

.PHONY: help
help: ## Ask for help!
	@grep -E '^[a-zA-Z0-9_-]+:.*?## .*$$' $(MAKEFILE_LIST) | sort | \
		awk 'BEGIN {FS = ":.*?## "}; \
		{printf "\033[36m%-30s\033[0m %s\n", $$1, $$2}'

.PHONY: info
info: ## Print resolved paths and tool versions
	@echo "Network:      $(NETWORK)"
	@echo "Rust crate:   $(RUST_DIR)"
	@echo "JAVA_HOME:    $(if $(JAVA_HOME),$(JAVA_HOME),<none; using PATH>)"
	@echo "ANDROID_HOME: $(if $(ANDROID_HOME),$(ANDROID_HOME),<not found>)"
	@printf "Java:         "; \
		if [ -n "$(JAVA_HOME)" ] && [ -x "$(JAVA_HOME)/bin/java" ]; then \
			"$(JAVA_HOME)/bin/java" -version 2>&1 | head -1; \
		elif command -v java >/dev/null 2>&1; then \
			java -version 2>&1 | head -1; \
		else echo "absent"; fi
	@printf "Cargo:        "; \
		if command -v $(CARGO) >/dev/null 2>&1; then $(CARGO) --version; \
		else echo "absent"; fi

# Warns rather than fails: a worktree inside the repo is legitimate, it just
# makes a whole-tree scan report other branches' code as your failures.
.PHONY: warn-worktrees
warn-worktrees:
	@if [ -d .claude/worktrees ] && \
		[ -n "$$(ls -A .claude/worktrees 2>/dev/null)" ]; then \
		echo "WARNING: .claude/worktrees/ is non-empty."; \
		echo "         Whole-tree scans (detekt, ktlint) will read those"; \
		echo "         branches' sources and may fail on code that is not"; \
		echo "         yours. Move the directory aside before trusting a"; \
		echo "         failure."; \
		echo ""; \
	fi

# ---------------------------------------------------------------------------
# Aggregate targets
# ---------------------------------------------------------------------------

.PHONY: build
build: ## Build every module in debug mode
	$(GRADLE) assembleDebug

.PHONY: build-release
build-release: ## Build every module in release mode
	$(GRADLE) assembleRelease

# The plain aggregates cover the Android side only, which is what most changes
# touch. The *-all variants additionally cover the Rust crate.

.PHONY: check
check: check-properties check-format lint test ## Run all checks

.PHONY: check-all
check-all: check check-format-rust lint-rust test-rust ## All checks, incl. Rust

.PHONY: check-format
check-format: ktlint ## Check formatting (Kotlin)

.PHONY: check-format-all
check-format-all: check-format check-format-rust ## Check formatting, incl. Rust

.PHONY: format
format: ktlint-format ## Format code (Kotlin)

.PHONY: format-all
format-all: format format-rust ## Format code, incl. Rust

.PHONY: lint
lint: detekt ktlint lint-android ## Run all linters (Kotlin)

.PHONY: lint-all
lint-all: lint lint-rust ## Run all linters, incl. Rust

.PHONY: test
test: test-unit ## Run unit tests

.PHONY: test-all
test-all: test test-rust ## Run unit tests, incl. Rust

.PHONY: clean
clean: ## Clean Gradle build artifacts
	$(GRADLE) clean

.PHONY: clean-all
clean-all: clean clean-rust ## Clean Gradle and Cargo build artifacts

.PHONY: setup
setup: ## Set up the development environment
	@$(MAKE) --no-print-directory info
	$(GRADLE) --version
	@echo "To also set up Rust, run: make setup-rust"

# ---------------------------------------------------------------------------
# CI parity
# ---------------------------------------------------------------------------
#
# These wrap scripts/ci-local.sh, which mirrors the pull-request.yml jobs that
# can run on a developer machine. Keep the wrapping thin: the script remains
# the single definition of the stage list.

.PHONY: ci-local
ci-local: warn-worktrees ## Run every local CI stage (slowest; includes device)
	./scripts/ci-local.sh full

.PHONY: ci-local-fast
ci-local-fast: warn-worktrees ## Run the lint and style CI stages only
	./scripts/ci-local.sh fast

.PHONY: ci-local-quick
ci-local-quick: warn-worktrees ## Run lint, style and unit-test CI stages
	./scripts/ci-local.sh quick

# ---------------------------------------------------------------------------
# Android: static analysis and formatting
# ---------------------------------------------------------------------------

.PHONY: detekt
detekt: warn-worktrees ## Static analysis with detekt
	$(GRADLE) detektAll

.PHONY: detekt-baseline
detekt-baseline: ## Regenerate the detekt baseline
	$(GRADLE) detektGenerateBaseline

.PHONY: ktlint
ktlint: warn-worktrees ## Check Kotlin code style with ktlint
	$(GRADLE) ktlint

.PHONY: ktlint-format
ktlint-format: ## Apply Kotlin code style with ktlint
	$(GRADLE) ktlintFormat

.PHONY: lint-android
lint-android: ## Static analysis with Android Lint
	$(GRADLE) :sdk-lib:lintRelease :demo-app:lint$(NETWORK)Release

.PHONY: check-properties
check-properties: ## Validate the Gradle properties
	$(GRADLE) checkProperties

# ---------------------------------------------------------------------------
# Android: test
# ---------------------------------------------------------------------------

.PHONY: test-unit
test-unit: ## Run JVM unit tests for every module
	$(GRADLE) test

# Downloads a system image on first run. On an Apple Silicon Mac this needs
# the `aosp` system image for the device's API level.
.PHONY: test-instrumented
test-instrumented: ## Run instrumentation tests on a managed virtual device
	$(GRADLE) $(ANDROID_TEST_MODULES) $(MANAGED_DEVICE_FLAGS)

# Runs against Firebase Test Lab, so it needs FTL credentials and cannot run
# offline. Present so the CI job has a target like every other one.
.PHONY: test-robo
test-robo: ## Run the demo-app robo test on Firebase Test Lab
	$(GRADLE) :demo-app:runFlankSanityConfig

# ---------------------------------------------------------------------------
# Demo app
# ---------------------------------------------------------------------------

.PHONY: build-demo-app
build-demo-app: ## Build the demo app in debug mode
	$(GRADLE) :demo-app:assemble$(NETWORK)Debug

.PHONY: install-demo-app
install-demo-app: ## Install the demo app on the connected device
	$(GRADLE) :demo-app:install$(NETWORK)Debug

# ---------------------------------------------------------------------------
# Rust (backend-lib/)
# ---------------------------------------------------------------------------
#
# The cargo invocations mirror pull-request.yml exactly. --all-features
# matters: code behind a cargo feature is not compiled by a default-feature
# check, so a merge can break it while a plain `cargo check` stays green.
#
# Note this repository formats with its own pinned toolchain. Do NOT add
# `+nightly` to the fmt targets; nightly rustfmt produces different output and
# fails the CI format check.

.PHONY: setup-rust
setup-rust: ## Install the Rust toolchain and Android targets
	@command -v rustup >/dev/null 2>&1 || { \
		echo "rustup not found. Install it from https://rustup.rs"; \
		exit 1; }
	@# Run rustup inside the crate so the toolchain pinned by its
	@# rust-toolchain.toml is the one that gets the targets and components.
	cd $(RUST_DIR) && rustup show
	cd $(RUST_DIR) && rustup target add $(RUST_ANDROID_TARGETS)
	cd $(RUST_DIR) && rustup component add rustfmt clippy

.PHONY: build-rust
build-rust: ## Build the Rust crate for the host (debug)
	cd $(RUST_DIR) && $(CARGO) build

.PHONY: build-rust-release
build-rust-release: ## Build the Rust crate for the host (release)
	cd $(RUST_DIR) && $(CARGO) build --release

.PHONY: build-rust-android
build-rust-android: ## Build the Rust JNI libs for all Android ABIs
	$(GRADLE) \
		:backend-lib:cargoBuild \
		:backend-lib:cargoBuildArm64 \
		:backend-lib:cargoBuildX86 \
		:backend-lib:cargoBuildX86_64

.PHONY: check-rust
check-rust: ## Type-check the Rust crate without building it
	cd $(RUST_DIR) && $(CARGO) check --all-targets --all-features

.PHONY: test-rust
test-rust: ## Run the Rust unit tests
	cd $(RUST_DIR) && $(CARGO) test --all-features

.PHONY: lint-rust
lint-rust: ## Lint the Rust crate with clippy
	cd $(RUST_DIR) && $(CARGO) clippy --tests --all-features -- \
		-W clippy::all -D warnings

.PHONY: format-rust
format-rust: ## Format the Rust crate with rustfmt
	cd $(RUST_DIR) && $(CARGO) fmt --all

.PHONY: check-format-rust
check-format-rust: ## Check the Rust formatting
	cd $(RUST_DIR) && $(CARGO) fmt --all --check

.PHONY: clean-rust
clean-rust: ## Clean the Cargo build artifacts
	cd $(RUST_DIR) && $(CARGO) clean
