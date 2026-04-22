# AGENTS.md

Guidance for AI coding agents (and anyone else) working in this repository.

## Pre-push validation

Before opening a PR or pushing to an existing PR branch, run the CI checks
locally. CI on this repo is slow (20-25 minutes per run) and uses paid
services (`emulator.wtf`); burning runs on easily-caught errors is wasteful.

Use `scripts/ci-local.sh` to mirror the `.github/workflows/pull-request.yml`
jobs that are runnable on a dev machine:

```bash
./scripts/ci-local.sh fast     # detekt + ktlint (~30s) -- run this first
./scripts/ci-local.sh quick    # fast + unit tests (~2-5m)
./scripts/ci-local.sh full     # everything, including androidTest (~15-30m)

# Or a single stage when iterating:
./scripts/ci-local.sh detekt
./scripts/ci-local.sh lint
./scripts/ci-local.sh demoapp
```

### Environment requirements

- **JDK 17 or 21.** Android Gradle Plugin 8.13.x does not support JDK 25+.
  If your default `java -version` reports 25 or newer, install JDK 21 via
  Homebrew (`brew install openjdk@21`) or SDKMAN! and set `JAVA_HOME` before
  running the script.
- **Android SDK** at `$ANDROID_HOME` or `~/Library/Android/sdk`.
- For the `androidtest` stage on Apple Silicon, the `aosp` SDK-36 Pixel 2
  system image is downloaded on first run (~1.5 GB).

### What cannot be run locally

- `test_android_modules_wtf` uses the `emulator.wtf` cloud service which
  requires a paid `EMULATOR_WTF_API_KEY`. The `ci-local.sh` script
  substitutes `connectedDebugAndroidTest` / Gradle managed devices which
  execute the same tests locally and catch the same regressions.
- `test_android_modules_ftl` (Firebase Test Lab) is skipped by CI for fork
  PRs and also requires cloud credentials.

### When to run which stage

| Change | Minimum stages |
|---|---|
| Style / rename / doc | `fast` |
| Logic or refactor | `quick` |
| New public API, new module, JNI/Rust boundary | `full` |
| Demo-app only | `fast` then `demoapp` |

### Historical note

Several regressions on `main` (MOB-987, MOB-1100) were merged with 4-5
failing CI checks because (a) branch protection does not require CI to be
green before merge, and (b) `pull-request.yml` does not run on `push` events
to `main`, so the broken state is invisible until someone opens a fresh PR
that rebases onto it. Running `ci-local.sh` before pushing protects you from
inheriting that kind of failure; it also prevents you from adding to it.

## Worktree layout

This repo is typically checked out with multiple worktrees so that
long-running feature work (e.g. `feat/*`) does not block quick fixes on
`main`:

```
<parent>/main                      # tracking origin/main, used to cut new branches
<parent>/<feature-branch-name>     # per-feature worktree
<parent>/<fix-branch-name>         # per-fix worktree
```

Create new worktrees from `main` (not from an unrelated feature branch) to
avoid inheriting unrelated WIP:

```bash
cd <parent>/main
git pull origin main
git worktree add ../fix-something -b fix/something main
```

## Commit message conventions

The project uses ticket-prefixed commit messages for tracked work, e.g.
`MOB-1100: Fixed runtime crashes`. For untracked fixes, a short imperative
prefix is acceptable (`fix: ...`, `chore: ...`). Keep the first line under
72 characters; include context in the body.

## Other notes

- The SDK and related libraries are Kotlin + Rust. Changes that cross the
  JNI boundary (`backend-lib/src/main/rust/*` and the Kotlin `Jni*` model
  classes) require updating both sides in lockstep.
- Detekt and ktlint are strict; treat their output as blocking. `detektAll`
  catches `MaxLineLength`, `ReturnCount`, `LongParameterList`, and similar
  issues that won't be apparent from a plain `./gradlew build`.
- When touching `Jni*` data classes that are constructed from Rust via
  `env.new_object`, keep the JVM signature (`(JJJ)V` etc.) in sync and
  avoid adding `require` blocks that crash on edge-case inputs -- the
  Kotlin handler layer (e.g. `ScanRange.new`) is the right place to apply
  soft validation.
