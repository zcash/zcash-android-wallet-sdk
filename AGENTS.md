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
./scripts/ci-local.sh quick    # fast + unit tests (~5m)
./scripts/ci-local.sh full     # everything, including androidTest (~15-30m)

# Or a single stage when iterating:
./scripts/ci-local.sh detekt
./scripts/ci-local.sh lint
./scripts/ci-local.sh demoapp
```

`quick` is what actually compiles the Kotlin: `fast` only runs static
analysis, so it will pass on code that does not compile. Run at least `quick`
after any change to a Kotlin source file.

### Environment requirements

- **JDK 17 or 21.** Android Gradle Plugin 8.13.x does not support JDK 25+.
  If your default `java -version` reports 25 or newer, install JDK 21 via
  Homebrew (`brew install openjdk@21`) or SDKMAN! and set `JAVA_HOME` before
  running the script.

  On a machine where `java` is not on `PATH` at all (common on macOS, where
  the JDK is installed but not linked), export it for the command:

  ```bash
  export JAVA_HOME=/opt/homebrew/opt/openjdk@21          # brew install openjdk@21
  export PATH="$JAVA_HOME/bin:$PATH"
  export ANDROID_HOME="$HOME/Library/Android/sdk"
  ./scripts/ci-local.sh quick
  ```

  Android Studio's bundled runtime works too, if you prefer not to install a
  second JDK: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`.
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

## JNI module layout (Rust)

All `Java_*` JNI exports live under `backend-lib/src/main/rust/zcash_jni/`,
one submodule per area. The sibling modules hold only logic, with no
`extern "C"` functions:

```
backend-lib/src/main/rust/
  lib.rs            crate root: module declarations + non-JNI logic
  zcash_jni/
    mod.rs          submodule declarations + shared marshalling
    wallet.rs       ..._jni_RustBackend_*                       (50)
    derivation.rs   ..._jni_RustDerivationTool_*                 (9)
    tor.rs          ..._model_TorClient_* / _TorWalletClient_*  (17)
    eip681.rs       ..._jni_RustEip681Tool_*                     (2)
  eip681.rs         EIP-681 parsing logic
  tor.rs            Tor runtime and lightwalletd logic
  utils.rs          domain-neutral JNI plumbing (see below)
```

**When adding a new JNI function, put it in the matching `zcash_jni`
submodule, never in the logic module.** Keep the logic in the sibling
module and call into it, so the export stays a thin boundary. If a new area
has no submodule yet, add one rather than parking exports in `lib.rs`.

### Why the module is called `zcash_jni`

A crate-root `mod jni;` would shadow the `jni` crate, so every bare `jni::`
path in `lib.rs` would have to be respelled `::jni::`. The prefix keeps
`jni::` meaning the crate everywhere.

### Where the boundary falls

It is not only the `Java_*` functions that belong in `zcash_jni`. The rule
is *does this symbol speak JNI?*:

| Goes in `zcash_jni` | Stays in the logic module |
|---|---|
| `Java_*` exports | Domain constants and types |
| Anything whose signature mentions `JNIEnv` or a `J*` / `j*` type | Anything expressible in plain Rust types |
| Java class-path descriptors (`"cash/z/ecc/.../JniFoo"`) | Database, planning, proving, protocol logic |
| Helpers that build or parse Java objects (`encode_*` / `decode_*` over `env.new_object`) | Unit tests of that logic |

When a helper mixes both, split it rather than moving it wholesale. The
worked example is `wallet_db`: it decoded a `JString` path and then opened
a database, so the decoding half is `zcash_jni::wallet_db` and it calls the
pure `crate::wallet_db`, which takes a `PathBuf`.

Marshalling lives **beside the exports it serves**, not in a shared bucket.
`zcash_jni/mod.rs` holds only what two or more submodules need; anything
with a single caller lives in that caller's submodule. That is why
`encode_usk` sits in `wallet.rs` and `encode_transaction` in `tor.rs`.

This gives a checkable invariant: **outside `#[cfg(test)]`, a logic module
must not reference the `jni` crate at all.** `crate::utils` is the
exception by design; it is the domain-neutral plumbing (`catch_unwind`,
byte-array and string conversion, exception handling) that every submodule
builds on, not a logic module.

### Verifying the JNI export surface

Kotlin binds JNI by symbol name and resolves lazily, so renaming or dropping
an export is a *runtime* crash, not a compile error. Neither `cargo check`
nor the Kotlin build catches it. `scripts/check-jni-symbols.sh` does:

```bash
./scripts/check-jni-symbols.sh            # verify against the baseline
./scripts/check-jni-symbols.sh --update   # rewrite the baseline
```

It builds the cdylib, extracts the exported `Java_*` names, and diffs them
against `backend-lib/jni-symbols.txt`, which is committed.

**A pure refactor must never need `--update`.** If it does, the refactor
renamed something and the Kotlin side would have failed to resolve it on a
device. Run the script before and after any change that only moves code.

Adding, removing or renaming a JNI function *is* a change to the export
surface, so it legitimately needs `--update`. Commit the regenerated
baseline in the same commit as the code, so the contract change is visible
in review rather than buried in a rebuild.

Only the symbol *name* is compared. `nm`'s address column shifts on every
rebuild, and Mach-O prefixes names with an underscore that ELF does not, so
neither belongs in the baseline. There are no feature-gated modules in this
crate, so one build covers the whole surface.

## Other notes

- The SDK and related libraries are Kotlin + Rust. Changes that cross the
  JNI boundary (`backend-lib/src/main/rust/*` and the Kotlin `Jni*` model
  classes) require updating both sides in lockstep.
- The Rust backend is fastest to check on its own, without Gradle or a JDK:

  ```bash
  cd backend-lib && cargo check --lib && cargo fmt --check
  ```

  Note this only proves the Rust compiles. A JNI signature mismatch between
  Rust and Kotlin is a *runtime* crash, not a compile error on either side,
  so a Rust-only change that touches a `Java_*` export or an
  `env.new_object` type signature still needs `./scripts/ci-local.sh quick`.
- Detekt and ktlint are strict; treat their output as blocking. `detektAll`
  catches `MaxLineLength`, `ReturnCount`, `LongParameterList`, and similar
  issues that won't be apparent from a plain `./gradlew build`.
- When touching `Jni*` data classes that are constructed from Rust via
  `env.new_object`, keep the JVM signature (`(JJJ)V` etc.) in sync and
  avoid adding `require` blocks that crash on edge-case inputs -- the
  Kotlin handler layer (e.g. `ScanRange.new`) is the right place to apply
  soft validation.
