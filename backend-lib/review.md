● Review: PR #2021 — android-slipstream-ironwood-chp → prerelease/snapshot-v2.6.6

  Reviewed from the local checkout (HEAD 64aa849d matches the PR head exactly; the diff exceeds GitHub's API limit). Six parallel deep-review passes were run over the
  subsystems, with findings verified against file contents and the pinned upstream crates (librustzcash 3a10e7f, zcash_voting 1.0.0 @ a4daaf7).

  Overview

  12 commits, ~17.4k additions / 1.9k deletions across 131 files, in four workstreams:

  1. ZIP 318 Orchard→Ironwood migration engine — new Rust JNI layer (migration.rs ~3k lines, migration_engine.rs, migration_keystone.rs for Keystone UR/QR signing,
  migration_plan_cache.rs), Kotlin JNI bindings (MigrationRustBackend.kt), and a new public SDK API (MigrationSdk.kt / OrchardMigrationSdkImpl.kt / TypesafeMigrationBackend*).
  2. Slipstream light-sync engine — Rust bindings (slipstream/{mod,host_read,read_query,summary}.rs, feature-gated) plus a Kotlin adapter under com.zodl.slipstream inside
  sdk-lib (SlipstreamSynchronizer.kt ~1.1k lines plus internals and tests).
  3. Voting port to zcash_voting 1.0.0 — hotkeys move to app-owned stored secrets, typed delegation/vote APIs, recovery persistence delegated upstream, Orchard→Ironwood pool
  switch.
  4. Ironwood plumbing + CI — proto/ShieldedProtocolEnum/CompactBlockProcessor/balance additions; a cargo fmt CI job and toolchain pinning via rust-toolchain.toml (both clean).

  Overall this is unusually well-documented, carefully layered code — JNI discipline (catch_unwind, parameterized SQL, local-frame management, descriptor-pinning tests) is
  consistently good, and the pure-logic cores are well tested. The risk is concentrated in a handful of cross-boundary contracts and in the orchestration layers that have little
  or no test coverage. Below, ordered by severity.

  Critical / funds- and privacy-affecting

  - Approve-schedule-X, sign-schedule-Y hazard — MigrationRustBackend.kt:258-274 marshals the user-reviewed schedule into 5 arrays that Rust ignores (migration.rs:1236-1250);
  commit signs whatever the process-global plan cache last held. Read-only-looking calls (isNoteSplitNeededNative at migration.rs:1122, prepareNoteSplitNative,
  restartCurrentMigrationStepNative) silently overwrite that cache with a freshly randomized plan. Sequence: propose (user reviews S1) → any intervening query re-plans (S2) →
  commit signs and persists S2 with no error. Rust should verify the passed ids/amounts against the cached plan, or the dead parameters should be dropped and the cache contract
  documented.
  - Successful broadcasts can be permanently invalidated — OrchardMigrationSdkImpl.kt:630-644: every non-gRPC SendResponse rejection maps to TransferResult.InvalidNote
  (permanent). Process death between broadcast and recordTransferResult means the resubmit gets "already have transaction" → the successful transfer is marked invalid → user is
  pushed into restartCurrentMigrationStep, potentially double-spending against an in-mempool tx. Transient mempool/fee rejections are likewise made permanent.
  - Clearnet leak on account import with Tor enabled — SlipstreamSynchronizer.kt:295-304 calls restoreAnchor(torDir = null) even when Tor is on (cold-start provisioning
  correctly passes engineTorDir at :875/:897), linking the user's IP to their wallet at import time.
  - Expired-transfer recovery is unreachable end-to-end — migration.rs:1011 treats the Expired tag as a no-op, nothing calls upstream's rebuild_expired_transfer, and no
  production path ever sets MigrationStatus::Failed. A device offline past all broadcast windows leaves the migration permanently InProgress with hasInvalidTransfers false
  forever; the Kotlin-documented recovery path can never fire. Only the debug-only clearMigrationNative escapes.

  Major — correctness

  - Unit confusion: epoch-seconds min'd against block height — OrchardMigrationSdkImpl.kt:450-456: minOf(nowSeconds + interval, expiryHeight - 1) always yields expiryHeight - 1
  (seconds ~1.8e9 vs heights ~3e6), so the "reschedule one cadence later" branch is dead and rescheduled transfers land at the edge of anchor expiry. The raw-Long public API
  (MigrationSdk.kt:86-119 — no Zatoshi/BlockHeight value types) is what let this compile; a trivial unit test would have caught it.
  - One exception kills sync permanently — OrchardMigrationSdkImpl.kt:414-426, 506-525: the isSyncBlocked() flow does unguarded JNI/SQLite work (including when the DB file
  doesn't exist yet — exactly the pre-wallet gate case) and feeds WalletCoordinator.kt:96-116's combine→stateIn; one throw terminates the synchronizer StateFlow until process
  restart.
  - Voting: multi-bundle delegation wedges — voting/delegation.rs:147: the new update_round_phase_forward(HotkeyGenerated) errors with "refusing to regress round phase" once a
  successful build advances the round to DelegationConstructed, so bundle 1 of any ≥2-bundle round (or any rebuild of bundle 0) permanently fails. The deleted range guard
  explicitly allowed this.
  - chp-voting feature is never enabled — build.gradle.kts:133-140 builds only slipstream, while this PR newly gates the entire voting JNI surface behind chp-voting (lib.rs:111,
  default = []). Every VotingRustBackend call in a shipped .so throws UnsatisfiedLinkError, and the rewritten 337-line VotingRustBackendTest suite cannot pass as configured. If
  deliberate for this testing cycle, it needs an explicit callout.
  - Ironwood proving key rebuilt per proof — lib.rs:2619-2626 builds ProvingKey::build(PostNu6_3) on every call, right next to the Orchard branch that was converted to
  cached_orchard_proving_key for exactly this reason. Multi-second, high-memory halo2 key build per transaction on mobile.
  - Slipstream tx state freezes at Pending — TransactionsController.kt:29-43 + PollGate.kt:21-28: chain-tip advance never triggers a transaction re-query, so the 10-confirmation
  Pending→Confirmed transition is never re-evaluated in an idle wallet (upstream folds networkHeight into the flow for this).
  - Slipstream lifecycle gaps (all in untested orchestration code): rewindToHeight/deleteAccount skip the engine-restart invariant on failure and ignore the quiescence verdict
  before destructive writes (SlipstreamSynchronizer.kt:559-582, 722-729); construction failure after engine.open() leaks the native handle while releasing the single-instance
  guard, enabling the WAL-corrupting dual-instance scenario the code itself documents (:974-1049); erase lacks the shutdown-await/mutex upstream SdkSynchronizer.erase has
  (:1104-1118, TOCTOU); onForeground racing close() can crash the process via unhandled IllegalStateException (:686-694).
  - remainingOrchardValueZatoshi is a disguised constant 0 — migration.rs:453-457, 1110 (.and_then(|_| None).unwrap_or(ZERO)), unmarked as a stub; migration-progress UI shows 0
  remaining from day one. Similarly next_transfer_ready_at_height never reports the actual future scheduled height (migration.rs:459-461), so ETA display degenerates to "now or
  unknown".
  - NULL-column brittleness in slipstream host reads — host_read.rs:274-295 (getTransactionRaw) and :430-451 (listResubmissionCandidates) decode nullable raw/expiry_height
  non-optionally; one un-enhanced or unmined row throws instead of returning null — the resubmission case disables resubmission for all candidates.

  Major — supply chain & resources

  - Unpinned git dependency in the hardware-wallet signing path — Cargo.toml:187: ur-registry floats on the default branch of KeystoneHQ/keystone-sdk-rust (no rev); ur is pinned
  by mutable tag only (and the tag's crate version mismatches). Any cargo update pulls arbitrary upstream code into the Keystone signing envelope. Pin both by rev.
  - Migration Tor runtime is never disposed — OrchardMigrationSdkImpl.kt:99-121, 546-563: each instance lazily builds a private RustBackend + TorClient, no dispose API exists,
  and the class doc prescribes a fresh instance per call site — repeated Tor-mode broadcasts accumulate live Tor runtimes contending on the same tor_migration directory. Related
  smaller race: LazyTorClient.ifCreated invokes the action outside the mutex, racing dispose() (LazyTorClient.kt:45-55).

  API surface & packaging

  - Three source-incompatible public changes needing release-note treatment: AccountBalance gains ironwood mid-parameter-list; Synchronizer gains abstract suspend members
  (Synchronizer.kt:639, 673); WalletCoordinator gains a required isSyncBlocked constructor param.
  - com.zodl.slipstream inside the cash.z.ecc artifact: the placement exists to piggyback on module-scoped internal visibility — defensible for a fork, but the published AAR now
  ships public third-party-namespace classes including SlipstreamWalletDb, a public object exposing free-form SQL over the wallet DB. Everything except the
  SlipstreamSynchronizer facade can and should be internal (JNI reflection ignores Kotlin visibility; @Keep + proguard rules already protect the carriers). Worth documenting
  that this branch is unmergeable upstream as-is.
  - String-typed error channels at exactly the points callers need discrimination (recurring theme, against the errors-as-ADTs rule): the retryable-import marker is
  string-matched on both sides of the FFI (lib.rs:504-536 ↔ ImportAccountErrors.kt:20-22) with no test pinning the marker; InsufficientFunds retry classification by
  e.message?.contains(...) (OrchardMigrationSdkImpl.kt:161-176); upstream typed errors (StalePlan, CommitError) flattened to {:?} strings. A librustzcash message reword silently
  breaks retry behavior with no compile- or test-time signal.

  Privacy & security notes

  - MIGRATION_DIAG debug logs emit per-note zatoshi values and every transfer's broadcast/expiry heights (migration.rs:283-330, 1399-1414) — enough to reconstruct the randomized
  broadcast schedule ZIP 318's delay-drawing exists to hide. Debug-gated, but logcat/bugreports leak it.
  - Clean elsewhere: all SQL parameterized across all new Rust; no key material logged; delegation sighash now signed FFI-side so the seed never enters zcash_voting (good); Tor
  restoreAnchor correctly falls back offline rather than clearnet (except the import path above); dangerously_trust_everyone = false; SaplingParams enforces HTTPS with
  upstream-matching pins (minor: size check runs after the full download, .part not cleaned on failure).

  Tests

  - Strong: pure reducers/mappers in slipstream (PollGate incl. flood-invariant test, state mapping, transaction-state port), JNI descriptor-pinning via reflection (a genuinely
  good pattern), LazyTorClient, voting carrier size-validation.
  - Weak where it matters: OrchardMigrationSdkImplTest.kt is 295 lines with exactly one test (a passthrough) — none of the Kotlin-owned logic where the majors above live is
  covered. SlipstreamSynchronizer/SlipstreamEngine/TransactionsController are untested; both slipstream androidTest classes are self-documented as never having been run.
  VotingRustBackendTest genuinely lost positive-path coverage (delegation-submission and vote-recovery round trips now assert only that calls throw) — partly forced by the new
  API's real-wallet-DB requirement, but worth acknowledging. One committed Rust test (migration.rs:2724-2781) asserts a bug the pinned upstream has fixed and will spuriously
  fail.

  Smaller items (selection)

  - summary.rs:379-381 opens the wallet DB read-write (with CREATE), contradicting the module's read-only rule and its own open_read_only helper.
  - Invalid network_id/negative heights silently coerce (TestNetwork / height 0 = full rescan) instead of erroring (slipstream/mod.rs:300-305, 667, 841-847).
  - Stale docs: slipstream's "never built via cargo-ndk / see README" block (no README exists; a javap verification pass should be a merge gate if descriptors really are
  hand-derived); the "Cargo cycle makes typed access IMPOSSIBLE" rationale for duplicated raw SQL is obsolete now that slipstream-jni is a module of the same crate; several
  stale Cargo.toml comments.
  - recordTransferResult's documented retryable semantics don't exist — tags 1/2/3 are no-ops Rust-side (migration.rs:975-1017), and tag 0 can regress Mined → Broadcast on a
  stale callback.
  - SubmitPlanStore persisted prefs: no version field, unbounded growth, and it's write-only (no production reader); erase leaves the file behind.
  - EncryptedPreferenceKeys.kt:14 KDoc says millis, value is seconds; key not namespaced per wallet/alias.
  - Global MIGRATION_DB_ACCESS_MUTEX held across Tor bootstrap + network broadcast stalls every migration state query for the duration; Rust-side nothing enforces the mutex
  precondition its read-modify-write transitions depend on.
  - Proto changes hardcode field numbers (ironwoodActions = 9, ironwoodTree = 7, ironwood = 2) claimed to match lightwalletd — worth a pointer to the lightwalletd commit that
  assigns them, since a mismatch is silent data loss. CompactBlockUnsafe.ironwoodOutputsCount is a self-described temporary diagnostic shipping in a public class.
  - gradle.properties: org.gradle.tooling.parallel is not a documented Gradle property (the standard flag is org.gradle.parallel) — verify it does anything.
  - PR hygiene: the description is the untouched template with no summary of a 17k-line change, and the title is just the branch name — for a change of this scope, a real
  description mapping the four workstreams to commits would materially help review.

  Verdict

  The engineering fundamentals are strong — JNI discipline, documentation, and the pure-logic cores are above the bar for this codebase. But I'd hold merge for: the plan-cache
  signing contract (approve-X-sign-Y), the InvalidNote submit mapping, the seconds-vs-height reschedule bug, the unguarded isSyncBlocked flow, the delegation phase-regression
  wedge, the chp-voting feature/test mismatch, the clearnet import leak, and pinning ur-registry. All are locally fixable without redesign. The migration Kotlin impl
  additionally needs a hardening pass (typed errors, lifecycle/dispose, unit tests for its owned logic) before the API is production-ready.

