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
./scripts/ci-local.sh quick    # fast + rust + unit tests (~5m)
./scripts/ci-local.sh full     # everything, including androidTest (~15-30m)

# Or a single stage when iterating:
./scripts/ci-local.sh detekt
./scripts/ci-local.sh rust
./scripts/ci-local.sh lint
./scripts/ci-local.sh demoapp
```

`quick` is what actually compiles the Kotlin: `fast` only runs static
analysis, so it will pass on code that does not compile. Run at least `quick`
after any change to a Kotlin source file.

The `rust` stage runs `cargo test` against `backend-lib`. The first invocation
builds around 640 crates and is slow; later runs are incremental.

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

| Change                                             | Minimum stages        |
| -------------------------------------------------- | --------------------- |
| Style / rename / doc                               | `fast`                |
| Logic or refactor                                  | `quick`               |
| Rust-only change under `backend-lib/src/main/rust` | `fast` then `rust`    |
| New public API, new module, JNI/Rust boundary      | `full`                |
| Demo-app only                                      | `fast` then `demoapp` |

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

## Security-critical API rules

These rules exist because violations have shipped before. They are not
stylistic.

### Semantic types for public APIs (MUST)

Every public API parameter that carries a domain value — key material,
seeds, addresses, memos, account identifiers — MUST use its semantic
wrapper type. Never accept or pass bare `String` or `ByteArray` for these
values in the public surface. A primitive-typed parameter lets a typo or
autocomplete slip a key into a memo field, and lets invalid input travel
all the way to the Rust backend before failing.

- Gold-standard pattern to copy: `UnifiedSpendingKey`
  (`sdk-lib/.../sdk/model/UnifiedSpendingKey.kt`) — private constructor,
  rust-backed validation on construction (`validateUnifiedSpendingKey`),
  bytes held in `FirstClassByteArray`, and `toString()` redacted so the key
  can never leak through logs. New key-material types MUST follow it.
- Known weak spot: `UnifiedFullViewingKey` is currently a bare
  `data class(val encoding: String)` with no construction-time validation.
  Do not treat it as a pattern to copy; new or changed types must validate
  on construction through the backend so invalid values are rejected before
  reaching deeper API calls.
- **All parsing and validation is librustzcash's job, surfaced through the
  JNI layer.** NEVER implement your own inline parser, regex, prefix check,
  or encoding check for key material or addresses in Kotlin. If the
  validation entry point you need does not exist, expose the librustzcash
  function through `backend-lib` (`Backend.kt`) and `TypesafeBackend`, and
  call that.
- Known gaps — do not extend them: seeds as raw `ByteArray`
  (`DerivationTool`), addresses and memos as `String` on `Synchronizer`
  (`proposeTransfer`, `validateAddress`, ...), and the UFVK as `String` in
  `DerivationTool.deriveUnifiedAddress` / `derivePrivateUseMetadataKey`.
  Any new API takes the semantic type; conversion from raw input happens at
  the app-facing edge.
- Reduction to raw `String`/`ByteArray` happens only in
  `TypesafeBackendImpl` at the JNI boundary (e.g. `setup.ufvk.encoding`).
  That is the existing pattern — keep it there and nowhere else.
- This does not conflict with the "soft validation for `Jni*` classes" note
  below: that note covers data flowing _out of_ Rust into `Jni*` holders;
  this section covers caller input flowing _toward_ Rust.

### Key material in errors and logs (MUST)

- Rust side: NEVER interpolate caller-supplied input into `anyhow!` /
  exception messages. Counterexample not to repeat: `parse_ufvk`
  (`backend-lib/src/main/rust/lib.rs`) embeds the full input in
  `Value "{ufvk_string}" did not decode as a valid UFVK`. The correct
  pattern, used elsewhere in `lib.rs`, embeds only the error (`{:?}` on
  `e`), never the input.
- Kotlin side: exception messages must not splice `cause?.message` from JNI
  `RuntimeException`s — those messages originate in Rust and may echo key
  material. Counterexample: `InitializeException.ImportAccountException`
  (`sdk-lib/.../exception/Exceptions.kt`) builds
  `"... due to: ${cause?.message}"`. Keep the cause as the chained
  throwable; keep the message fixed and generic.
- Do not pass backend throwables to `Twig` whole (e.g.
  `Twig.error(it) { ... }` on a JNI failure) — log a fixed message instead.
  Users copy logs and error dialogs into support emails without knowing
  what is embedded in them.
- Changes to error paths that cross the JNI boundary must be checked on
  both the Rust and Kotlin sides in lockstep, same as any other JNI change.

## CHANGELOG discipline

`CHANGELOG.md` exists for consumers of the published library, and nothing else.

- Update it for any **public API change, bug fix, or semantic change**. The entry
  **must** be part of the same commit that makes the change, not a follow-up.
- Entries carry **only** what a consumer needs in order to adapt: the public symbol
  by name, the precise shape of the change, what breaks at their call site, and the
  edit to make (or that none is needed).
- **Never** describe implementation details, or contracts that are not visible
  through the public API. In particular, do not narrate branch or release topology
  -- which line merged into which, which version numbers were skipped, why the
  ordering in the file looks the way it does. None of that is actionable for a
  consumer.
- Record **only completed changes since the last release**, never the interstitial
  states of an API that was changed several times since then. If a symbol was added
  and then renamed before release, the entry describes the final name only.
- **Never modify an entry under an already-published version heading** (a dated
  `## [x.y.z] - DATE` section). Those are the historical record of what that release
  shipped, and must not be altered even to clarify or correct. New information goes
  under `## [Unreleased]`.
- Do **not** add a separate "Breaking changes" section. `### Changed` already is the
  breaking-change section -- everything under it is breaking, whether semver,
  dependency, or otherwise. Non-breaking additions go under `### Added`, fixes under
  `### Fixed`. Each `### Changed` entry should read as the consumer meets the break:
  "positional construction will not compile", "exhaustive `when` stops compiling until
  the new case is handled", "any implementer or test fake must now provide this".
- Privacy, security, and cost properties are user-facing even when they are documented
  only in KDoc or rustdoc. Wallet teams design confirmation UI from the changelog, so
  a feature that reveals data on-chain, costs a fee, or fails at runtime belongs here
  too.

When preparing a release, audit the public surface by diffing the release range rather
than trusting the file to be complete. Behavior-only changes with no signature change
-- altered equality semantics, stricter validation, a previously fixed value becoming
settable -- are the ones most often missed.

## Commit message conventions

Tracked work uses ticket-prefixed commit messages. Two ticket systems are
in use, and they are equivalent in format — reference whichever ticket the
work was assigned from:

- `MOB-1100: Fixed runtime crashes` — Linear tickets, internal to the
  wallet team. A MOB- ticket may or may not have a correlated GitHub issue.
- `[#258] Fixed runtime crashes` — GitHub issues (the format described in
  `CONTRIBUTING.md`); use this when the work references a GitHub issue.

For untracked fixes, a short imperative prefix is acceptable (`fix: ...`,
`chore: ...`). Keep the first line under 72 characters; include context in
the body.

## PRs and changelog

- PRs that reference a GitHub issue should link it (`[#issue]` in the
  title/commits per `CONTRIBUTING.md`); PRs for Linear-tracked work
  reference the MOB- ticket instead.
- All enhancements and bug fixes need an entry in `CHANGELOG.md`
  (see `CONTRIBUTING.md` and `docs/CODE_REVIEW_GUIDELINES.md`).

## Database access: views only, everything else through the FFI

`data.db` is owned by Rust. `zcash_client_sqlite` defines both its tables and
a set of `v_*` views, and only the views are a supported interface. The tables
are an implementation detail that upstream reshapes freely, and a schema
migration that leaves a view's columns intact can still rename, split or drop
the tables underneath it.

**Kotlin may read `v_transactions` and `v_tx_outputs` directly. Every other
query goes through the FFI.** Never read a table from Kotlin, and never write
anything at all: writes belong to Rust, which owns invariants across tables
that no single statement can preserve.

Those two views are the client-facing read surface. `zcash_client_sqlite`
defines other `v_*` views, but they serve the scanning and note-commitment
machinery inside Rust and are not an interface for this SDK; treat them like
tables. The definitions live in `zcash_client_sqlite/src/wallet/db.rs` in
[librustzcash][lrz].

[lrz]: https://github.com/zcash/librustzcash

### Why those two, and when to ask for another

Everything the FFI returns is serialized and copied across the boundary, so a
query yielding many rows can cost more that way than reading it directly.
That is why `v_transactions` and `v_tx_outputs`, which back the transaction
history, are exempt at all.

Another query with the same bulk property may deserve the same treatment, but
that is not a call to make on your own. Do not add a direct read silently:
flag it to the user, say what the query returns and roughly how much data it
moves, and let them decide. If they agree, record it in the table below so the
next reader sees a sanctioned exception rather than a violation.

### Where direct access lives

All of it is under
`sdk-lib/src/main/java/cash/z/ecc/android/sdk/internal/db/derived/`. Adding a
query anywhere else is a mistake; adding one that names a table is a mistake
wherever it is.

| File                    | Reads            | Status                            |
| ----------------------- | ---------------- | --------------------------------- |
| `AllTransactionView.kt` | `v_transactions` | view, fine                        |
| `TxOutputsView.kt`      | `v_tx_outputs`   | view, fine                        |
| `BlockTable.kt`         | `blocks`         | **table, pre-existing exception** |
| `TransactionTable.kt`   | `transactions`   | **table, pre-existing exception** |

`DerivedDataDb.kt` and `DbDerivedDataRepository.kt` are wiring and open no
entities of their own.

The two exceptions predate this rule and are being migrated to the FFI. Do not
copy them, and do not add to them: if you need something they expose, add an
FFI call rather than a third table reader.

## Other notes

- The SDK and related libraries are Kotlin + Rust. Changes that cross the
  JNI boundary (`backend-lib/src/main/rust/*` and the Kotlin `Jni*` model
  classes) require updating both sides in lockstep.
- The Rust backend is fastest to check on its own, without Gradle or a JDK:

  ```bash
  cd backend-lib && cargo check --lib && cargo fmt --check
  ```

  Note this only proves the Rust compiles. A JNI signature mismatch between
  Rust and Kotlin is a _runtime_ crash, not a compile error on either side,
  so a Rust-only change that touches a `Java_*` export or an
  `env.new_object` type signature still needs `./scripts/ci-local.sh quick`.

- Detekt and ktlint are strict; treat their output as blocking. `detektAll`
  catches `MaxLineLength`, `ReturnCount`, `LongParameterList`, and similar
  issues that won't be apparent from a plain `./gradlew build`. Configs live
  in `tools/detekt.yml` and `tools/.editorconfig`; the authoritative style
  references are the Kotlin Coding Conventions and AOSP Java conventions
  (see `docs/CODE_REVIEW_GUIDELINES.md`).
- When touching `Jni*` data classes that are constructed from Rust via
  `env.new_object`, keep the JVM signature (`(JJJ)V` etc.) in sync and
  avoid adding `require` blocks that crash on edge-case inputs -- the
  Kotlin handler layer (e.g. `ScanRange.new`) is the right place to apply
  soft validation.

## Plans & Design Documents

Plans, design specs, and brainstorming documents produced by agents are working
artifacts of a development session, not repository history. Never commit them.

- Write them to the `.plans/` directory at the repository root, which is listed
  in `.gitignore`.
- If `.plans/` does not exist yet, create it (and ensure `.plans/` appears in
  the checked-in `.gitignore`).
- After writing a plan or spec, report its full absolute path, untruncated, so
  it can be copy-pasted.
