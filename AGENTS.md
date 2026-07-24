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

## JNI module layout (Rust)

All `Java_*` JNI exports live under `backend-lib/src/main/rust/jni/`, one
submodule per logical area. The sibling modules hold only logic, with no
`extern "C"` functions:

```
backend-lib/src/main/rust/
  lib.rs            crate root: module declarations + non-JNI logic
  jni/
    mod.rs          submodule declarations
    wallet.rs       ..._jni_RustBackend_*                  (50)
    derivation.rs   ..._jni_RustDerivationTool_*            (9)
    tor.rs          ..._model_TorClient_* / _TorWalletClient_*  (17)
    migration.rs    ..._jni_MigrationRustBackend_*         (32)
    eip681.rs       ..._jni_RustEip681Tool_*                (2)
    slipstream.rs   com_zodl_slipstream_SlipstreamNative_* (17)  [slipstream]
    voting/         ..._jni_VotingRustBackend_*            (60)  [chp-voting]
  migration.rs      migration logic + its tests
  tor.rs            Tor logic
  voting/           voting logic (helpers.rs, progress.rs, ...)
  slipstream/       Slipstream engine logic
```

Counts are the exported symbols per class as of the split (187 total). The
Java class name determines the submodule, not the file the logic lives in.

**When adding a new JNI function, put it in the matching `jni/` submodule,
never in the logic module.** Keep the logic in the sibling module and call
into it, so the export stays a thin boundary. If a new area has no `jni/`
submodule yet, add one rather than parking exports in `lib.rs`.

### Where the boundary falls

It is not only the `Java_*` functions that belong in `jni/`. The rule is
*does this symbol speak JNI?*:

| Goes in `jni/` | Stays in the logic module |
|---|---|
| `Java_*` exports | Domain constants and types |
| Anything whose signature mentions `JNIEnv` or a `J*` / `j*` type | Anything expressible in plain Rust types |
| Java class-path descriptors (`"cash/z/ecc/.../JniFoo"`) | Database, planning, proving, protocol logic |
| Helpers that build or parse Java objects (`encode_*` / `decode_*` over `env.new_object`) | Unit tests of that logic |

When a helper mixes both -- e.g. one that decodes a `JString` path and then
opens a database -- split it rather than moving it wholesale: the decoding
half goes in `jni/`, and it calls a pure function in the logic module.

Marshalling code lives **beside the exports it serves**, not in a shared
bucket: `jni/migration/encode.rs` serves `jni/migration.rs`. A submodule
becomes a directory when it grows, as `migration` has.

Do not create a `jni/helpers.rs` (or `common`, `util`, `shared`) holding
several areas' marshalling. Those modules are named for what their contents
*are* rather than what they *serve*, so they accrete unrelated code and
become an import magnet. A helper that genuinely is domain-neutral goes in
`crate::utils`, which already holds `java_bytes_to_rust`,
`rust_bytes_to_java`, `rust_vec_to_java`, and `catch_unwind`.

This yields a mechanically checkable invariant: **outside `#[cfg(test)]`, a
logic module must not reference the `jni` crate at all.**

```bash
cd backend-lib/src/main/rust
grep -n 'jni::\|JNIEnv\|JString\|JByteArray\|JObject\|jlong\|jint' \
  migration.rs eip681.rs tor.rs
```

Any hit outside a test block means the boundary is in the wrong place.

### Gotcha: `mod jni` shadows the `jni` crate

Because the module is named `jni` and is declared in `lib.rs`, a bare
`jni::...` path **inside `lib.rs`** resolves to the module, not the external
crate. Write `::jni::` there when the crate is meant. Submodules are
unaffected, since a bare path in a submodule already resolves to the crate.

### The symbol set is the Kotlin binding contract

Kotlin binds to these functions by exported symbol name, so:

- Moving a `#[unsafe(no_mangle)] pub extern "C"` function between Rust
  modules is link-safe -- the module path does not affect the symbol name.
- **Renaming one is a runtime crash**, not a compile error. The Kotlin
  `external fun` declaration resolves lazily at call time.

### Verifying a change to the JNI surface

`chp-voting` and `slipstream` are **off by default**, so a plain
`cargo check` silently skips 76 of the 187 exports. Always use
`--all-features`:

```bash
cd backend-lib
cargo check --lib --all-features

# Symbol-set check: capture before your change, compare after.
cargo build --lib --all-features
nm -gU target/debug/libzcashwalletsdk.dylib | awk '{print $3}' \
  | sed 's/^_//' | grep '^Java_' | sort -u > /tmp/nm-after.txt
diff /tmp/nm-before.txt /tmp/nm-after.txt
```

For a refactor that is *only* meant to move code, that diff must be empty.
When intentionally adding an export, it should show exactly the additions.

## Other notes

- The SDK and related libraries are Kotlin + Rust. Changes that cross the
  JNI boundary (`backend-lib/src/main/rust/jni/*` and the Kotlin `Jni*`
  model classes) require updating both sides in lockstep.
- Detekt and ktlint are strict; treat their output as blocking. `detektAll`
  catches `MaxLineLength`, `ReturnCount`, `LongParameterList`, and similar
  issues that won't be apparent from a plain `./gradlew build`.
- When touching `Jni*` data classes that are constructed from Rust via
  `env.new_object`, keep the JVM signature (`(JJJ)V` etc.) in sync and
  avoid adding `require` blocks that crash on edge-case inputs -- the
  Kotlin handler layer (e.g. `ScanRange.new`) is the right place to apply
  soft validation.
