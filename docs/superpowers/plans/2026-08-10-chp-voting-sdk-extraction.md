# CHP VotingSdk Extraction (SDK-side) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the CHP layering violation by adding a public `VotingSdk` API (mirroring the existing `MigrationSdk` pattern) that becomes the *only* path from the app into the voting Rust backend — nothing outside `sdk-lib` may see `VotingRustBackend`/`Jni*` types afterward.

**Architecture:** Three layers, same shape as migration: public `VotingSdk`/`VotingDbSession` interfaces in `cash.z.ecc.android.sdk` (new public model types, no `Jni*`/`internal` leakage) → `VotingSdkImpl`/`VotingDbSessionImpl` (internal, converts between public and JNI-typesafe types) → the existing `internal` `TypesafeVotingBackend`/`TypesafeVotingDb` (extended with two methods this plan adds) → `VotingRustBackend` (unchanged, stays behind its existing `@Deprecated(ERROR)` gate, message refreshed).

**Tech Stack:** Kotlin, JNI, JUnit4 + Mockito (matches `OrchardMigrationSdkImplTest`'s existing convention in this module — no MockK in `sdk-lib`).

## Global Constraints

- Do not touch the Rust `voting/` crate or its Cargo feature gating — out of scope per the design spec.
- Do not touch `backend-lib`'s shared `internal` helpers (dispatchers, `catch_unwind`, DB-handle plumbing) beyond the one `VotingRustBackend` doc-comment refresh in Task 2.
- Every new public type lives under `cash.z.ecc.android.sdk` (interfaces) or `cash.z.ecc.android.sdk.model.voting` (data classes) — never re-export a `cash.z.ecc.android.sdk.internal.*` or `backend-lib` type from `VotingSdk`'s public surface.
- `TypesafeVotingBackend`/`TypesafeVotingDb` stay `internal interface` — this plan only adds two new methods to them (Task 1), it does not make them public.
- Reference spec: `docs/superpowers/specs/2026-08-10-chp-feature-module-extraction-design.md` (this repo) and the fuller design in `zodl-android`'s copy of that file.

---

## Task 1: Extend `TypesafeVotingDb` with `delegationPhases`/`resetVotingSessionState`

The design spec requires these two operations at the typesafe layer — today they exist only on the raw JNI `VotingRustBackend.VotingDb` (`backend-lib/src/main/java/cash/z/ecc/android/sdk/internal/jni/VotingRustBackend.kt:331-336`), which nothing outside `TypesafeVotingBackendImpl` may call once this refactor lands.

**Files:**
- Modify: `sdk-lib/src/main/java/cash/z/ecc/android/sdk/internal/TypesafeVotingBackend.kt`
- Modify: `sdk-lib/src/main/java/cash/z/ecc/android/sdk/internal/TypesafeVotingBackendImpl.kt`
- Test: `sdk-lib/src/androidTest/java/cash/z/ecc/android/sdk/internal/TypesafeVotingBackendImplTest.kt`

**Interfaces:**
- Consumes: `VotingRustBackend.VotingDb.delegationPhases(roundId: String): Array<JniDelegationPhase>` and `.resetVotingSessionState(roundId: String)` (both already exist, unchanged).
- Produces: `TypesafeVotingDb.delegationPhases(roundId: String): List<JniDelegationPhase>` and `TypesafeVotingDb.resetVotingSessionState(roundId: String)` — Task 5/6 (`VotingSdkImpl`) consume these.

- [ ] **Step 1: Add the two methods to the `TypesafeVotingDb` interface**

In `sdk-lib/src/main/java/cash/z/ecc/android/sdk/internal/TypesafeVotingBackend.kt`, add to the end of the `TypesafeVotingDb` interface (right after `addSentServers`, before the closing `}`):

```kotlin
    /**
     * The canonical, per-bundle delegation phase for every bundle with recorded progress in
     * [roundId] — see [cash.z.ecc.android.sdk.internal.model.voting.JniDelegationPhase]'s doc.
     */
    suspend fun delegationPhases(roundId: String): List<JniDelegationPhase>

    /**
     * Clears a bundle's unsigned delegation setup fields (PCZT/rk/sighash) and any stale proof
     * row so a subsequent construct call starts clean. Does not delete round-level state.
     */
    suspend fun resetVotingSessionState(roundId: String)
```

Add the missing import at the top of the file, alongside the other `internal.model.voting` imports:

```kotlin
import cash.z.ecc.android.sdk.internal.model.voting.JniDelegationPhase
```

- [ ] **Step 2: Add the two methods to the `VotingDbBackend` seam interface**

In `sdk-lib/src/main/java/cash/z/ecc/android/sdk/internal/TypesafeVotingBackendImpl.kt`, add to the end of the `VotingDbBackend` interface (right after its last method, mirroring the same signature shape used throughout that interface — note it uses `Array`, not `List`, matching every other method on this interface):

```kotlin
    suspend fun delegationPhases(roundId: String): Array<JniDelegationPhase>

    suspend fun resetVotingSessionState(roundId: String)
```

Add the import: `import cash.z.ecc.android.sdk.internal.model.voting.JniDelegationPhase`.

- [ ] **Step 3: Implement them on `RustVotingDbBackend`**

In the same file, add to `private class RustVotingDbBackend`, mirroring the existing `getVotes`/`clearRound` delegation style exactly:

```kotlin
    override suspend fun delegationPhases(roundId: String): Array<JniDelegationPhase> =
        votingDb.delegationPhases(roundId)

    override suspend fun resetVotingSessionState(roundId: String) =
        votingDb.resetVotingSessionState(roundId)
```

- [ ] **Step 4: Implement them on `TypesafeVotingDbImpl`**

In the same file, add to `internal class TypesafeVotingDbImpl`, converting `Array` → `List` the same way `getVotes` does:

```kotlin
    override suspend fun delegationPhases(roundId: String): List<JniDelegationPhase> =
        votingDb.delegationPhases(roundId).asList()

    override suspend fun resetVotingSessionState(roundId: String) =
        votingDb.resetVotingSessionState(roundId)
```

- [ ] **Step 5: Write the failing test**

Open `sdk-lib/src/androidTest/java/cash/z/ecc/android/sdk/internal/TypesafeVotingBackendImplTest.kt`. It already contains a `RecordingVotingDbBackend` fake implementing `VotingDbBackend` (used across the existing tests in this file) — find its class body and add the two new overrides (they will fail to compile until this step, which is the point):

```kotlin
    var delegationPhasesResult: Array<JniDelegationPhase> = emptyArray()
    var resetVotingSessionStateCalls = mutableListOf<String>()

    override suspend fun delegationPhases(roundId: String): Array<JniDelegationPhase> =
        delegationPhasesResult

    override suspend fun resetVotingSessionState(roundId: String) {
        resetVotingSessionStateCalls.add(roundId)
    }
```

Add the import: `import cash.z.ecc.android.sdk.internal.model.voting.JniDelegationPhase`.

Then add two new `@Test` methods in the same file, near the other `TypesafeVotingDbImpl`-level tests:

```kotlin
    @Test
    fun delegationPhases_returns_backend_result_as_list() =
        runTest {
            val backend = RecordingVotingDbBackend()
            backend.delegationPhasesResult =
                arrayOf(
                    JniDelegationPhase(bundleIndex = 0, phase = "proved"),
                    JniDelegationPhase(bundleIndex = 1, phase = "prepared")
                )
            val db = TypesafeVotingDbImpl(backend)

            val result = db.delegationPhases("round-1")

            assertEquals(2, result.size)
            assertEquals("proved", result[0].phase)
            assertEquals(1, result[1].bundleIndex)
        }

    @Test
    fun resetVotingSessionState_forwards_round_id() =
        runTest {
            val backend = RecordingVotingDbBackend()
            val db = TypesafeVotingDbImpl(backend)

            db.resetVotingSessionState("round-1")

            assertEquals(listOf("round-1"), backend.resetVotingSessionStateCalls)
        }
```

Check the top of the file for the exact `runTest`/coroutines-test import already in use there and reuse it (this file already has coroutine tests, so the import exists — do not add a second, conflicting one).

- [ ] **Step 6: Run the tests to verify they compile and pass**

Run: `./gradlew :sdk-lib:connectedAndroidTest --tests "cash.z.ecc.android.sdk.internal.TypesafeVotingBackendImplTest"` (or via the project's existing androidTest runner if this file requires an emulator — check the file's other tests for the exact invocation the project uses; it is an `androidTest` source set, so it needs a connected device/emulator like the DIT device used elsewhere this session).

Expected: both new tests PASS, no other test in the file regresses.

- [ ] **Step 7: Commit**

```bash
git add sdk-lib/src/main/java/cash/z/ecc/android/sdk/internal/TypesafeVotingBackend.kt \
        sdk-lib/src/main/java/cash/z/ecc/android/sdk/internal/TypesafeVotingBackendImpl.kt \
        sdk-lib/src/androidTest/java/cash/z/ecc/android/sdk/internal/TypesafeVotingBackendImplTest.kt
git commit -m "$(cat <<'EOF'
Add delegationPhases/resetVotingSessionState to TypesafeVotingDb

These already exist on the raw JNI VotingRustBackend.VotingDb; the
typesafe layer was missing them, which the VotingSdk extraction needs
since nothing outside TypesafeVotingBackendImpl will be able to reach
the JNI class directly once it lands.
EOF
)"
```

---

## Task 2: Refresh `VotingRustBackend`'s stale `@Deprecated` message

Its current doc/message says "the native library exports none of these symbols... the `zcash_voting` dependency is commented out of `backend-lib/Cargo.toml`" — no longer true on any branch that already re-enabled voting (`backend-lib/build.gradle.kts` sets `RUSTFLAGS="--cfg zcash_voting"` unconditionally, and `Cargo.toml`'s `zcash_voting` dependency is uncommented). Refresh it to describe its real, ongoing purpose: gating direct access to the JNI class now that `VotingSdk` exists.

**Files:**
- Modify: `backend-lib/src/main/java/cash/z/ecc/android/sdk/internal/jni/VotingRustBackend.kt:66-81`

**Interfaces:**
- Consumes: nothing new.
- Produces: nothing new (doc/message-only change, no signature changes).

- [ ] **Step 1: Replace the class doc comment and `@Deprecated` message**

Replace this block (currently at `backend-lib/src/main/java/cash/z/ecc/android/sdk/internal/jni/VotingRustBackend.kt:66-81`):

```kotlin
/**
 * Bindings to the native shielded-voting backend.
 *
 * Every method here binds to a JNI symbol that the native library does not export on this
 * branch: the Rust voting module is gated behind `cfg(zcash_voting)` and the `zcash_voting`
 * dependency is commented out of `backend-lib/Cargo.toml`. Calling any of them throws
 * [UnsatisfiedLinkError].
 *
 * This is a compile error rather than a deprecation warning so that the failure lands
 * at build time instead of at runtime in a wallet. Kotlin `internal` cannot express
 * this: `sdk-lib` is a separate Gradle module and would lose access along with
 * consumers.
 */
@Keep
@Suppress("TooManyFunctions", "LongParameterList")
@Deprecated(
    message =
        "Shielded voting is unavailable in this release: the native library exports none of " +
            "these symbols, so every call throws UnsatisfiedLinkError. Do not call this class.",
    level = DeprecationLevel.ERROR
)
class VotingRustBackend private constructor() {
```

with:

```kotlin
/**
 * Raw JNI bindings to the native shielded-voting backend.
 *
 * The only permitted caller is `sdk-lib`'s `TypesafeVotingBackendImpl` — every other consumer,
 * including the app, must go through the public `cash.z.ecc.android.sdk.VotingSdk` API instead.
 * That boundary is enforced two ways: this `@Deprecated(ERROR)` (which forces any legitimate
 * caller to carry an explicit, grep-able `@Suppress("DEPRECATION_ERROR")`), and — once the app
 * finishes migrating off direct `VotingRustBackend` usage — dropping this module's JNI artifact
 * from the app's compile classpath entirely, per the CHP feature-module extraction design
 * (`docs/superpowers/specs/2026-08-10-chp-feature-module-extraction-design.md`).
 *
 * Kotlin `internal` cannot express this restriction on its own: `sdk-lib` is a separate Gradle
 * module from `backend-lib` and would lose access along with every other consumer, which is why
 * this class stays a public class carrying an error-level deprecation instead.
 *
 * Whether the native library actually exports these JNI symbols in a given build depends on
 * `backend-lib/build.gradle.kts`'s `RUSTFLAGS` (the `--cfg zcash_voting` gate) and
 * `backend-lib/Cargo.toml`'s `zcash_voting`/`unstable-voting-circuits` entries — independent of
 * this annotation. If they disagree with a caller's expectation, calls here throw
 * [UnsatisfiedLinkError] rather than failing gracefully; `VotingSdk` callers should use its
 * `isAvailable()` probe rather than assuming this class is safe to call just because the
 * `@Suppress` compiles.
 */
@Keep
@Suppress("TooManyFunctions", "LongParameterList")
@Deprecated(
    message =
        "Direct access to VotingRustBackend is restricted to sdk-lib's TypesafeVotingBackendImpl " +
            "— use cash.z.ecc.android.sdk.VotingSdk instead. See this class's doc comment.",
    level = DeprecationLevel.ERROR
)
class VotingRustBackend private constructor() {
```

- [ ] **Step 2: Compile-check**

Run: `./gradlew :backend-lib:compileDebugKotlin`

Expected: BUILD SUCCESSFUL — this is a comment/message-only change, no signature touched, so nothing downstream should break. If `sdk-lib`'s `TypesafeVotingBackendImpl` fails to compile, its existing `@file:Suppress("TooManyFunctions", "DEPRECATION_ERROR")` (top of that file) already covers the (unchanged) deprecation level, so a failure here would indicate this step accidentally changed something other than text — re-check the diff against the exact replacement above.

- [ ] **Step 3: Commit**

```bash
git add backend-lib/src/main/java/cash/z/ecc/android/sdk/internal/jni/VotingRustBackend.kt
git commit -m "$(cat <<'EOF'
Refresh VotingRustBackend's stale deprecation message

It described a state (native symbols not built) that stopped being
true once this branch re-enabled voting. Rewritten to describe its
real, ongoing purpose: gating direct JNI access now that VotingSdk
exists as the sanctioned public entry point.
EOF
)"
```

---

## Task 3: Define the public voting model types

New file holding every public data class `VotingSdk`/`VotingDbSession` need in place of the `internal.model.voting.Jni*` types — field-for-field mirrors of the JNI DTOs (see Task 4/5/6 for where each is used), so `VotingSdk`'s public surface never references a `backend-lib` or `internal` type.

**Files:**
- Create: `sdk-lib/src/main/java/cash/z/ecc/android/sdk/model/voting/VotingModels.kt`
- Test: `sdk-lib/src/test/java/cash/z/ecc/android/sdk/model/voting/VotingModelsTest.kt`

**Interfaces:**
- Consumes: nothing (pure data classes).
- Produces: every type below, under `cash.z.ecc.android.sdk.model.voting`, consumed by `VotingSdk`/`VotingDbSession` (Task 4) and `VotingSdkImpl`/`VotingDbSessionImpl` (Task 5/6).

- [ ] **Step 1: Write the file**

```kotlin
package cash.z.ecc.android.sdk.model.voting

/** Public mirror of the internal `VotingNoteScope` — which shielded-address scope a note came from. */
enum class VotingNoteScope {
    EXTERNAL,
    INTERNAL
}

/** A shielded note eligible for voting weight, as read from wallet state. */
data class VotingNoteInfo(
    val commitment: ByteArray,
    val nullifier: ByteArray,
    val value: Long,
    val position: Long,
    val diversifier: ByteArray,
    val rho: ByteArray,
    val rseed: ByteArray,
    val scope: VotingNoteScope,
    val ufvk: String
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VotingNoteInfo) return false
        return commitment.contentEquals(other.commitment) &&
            nullifier.contentEquals(other.nullifier) &&
            value == other.value &&
            position == other.position &&
            diversifier.contentEquals(other.diversifier) &&
            rho.contentEquals(other.rho) &&
            rseed.contentEquals(other.rseed) &&
            scope == other.scope &&
            ufvk == other.ufvk
    }

    override fun hashCode(): Int {
        var result = commitment.contentHashCode()
        result = 31 * result + nullifier.contentHashCode()
        result = 31 * result + value.hashCode()
        result = 31 * result + position.hashCode()
        result = 31 * result + diversifier.contentHashCode()
        result = 31 * result + rho.contentHashCode()
        result = 31 * result + rseed.contentHashCode()
        result = 31 * result + scope.hashCode()
        result = 31 * result + ufvk.hashCode()
        return result
    }
}

/** A merkle authentication path + root for one note, used for PCZT witness construction. */
data class VotingWitness(
    val noteCommitment: ByteArray,
    val position: Long,
    val root: ByteArray,
    val authPath: List<ByteArray>
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VotingWitness) return false
        return noteCommitment.contentEquals(other.noteCommitment) &&
            position == other.position &&
            root.contentEquals(other.root) &&
            authPath.size == other.authPath.size &&
            authPath.zip(other.authPath).all { (a, b) -> a.contentEquals(b) }
    }

    override fun hashCode(): Int {
        var result = noteCommitment.contentHashCode()
        result = 31 * result + position.hashCode()
        result = 31 * result + root.contentHashCode()
        result = 31 * result + authPath.size
        return result
    }
}

/** The vote-authority-note (VAN) merkle witness needed to build a vote commitment. */
data class VotingVanWitness(
    val authPath: List<ByteArray>,
    val position: Long,
    val anchorHeight: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VotingVanWitness) return false
        return authPath.size == other.authPath.size &&
            authPath.zip(other.authPath).all { (a, b) -> a.contentEquals(b) } &&
            position == other.position &&
            anchorHeight == other.anchorHeight
    }

    override fun hashCode(): Int {
        var result = authPath.size
        result = 31 * result + position.hashCode()
        result = 31 * result + anchorHeight.hashCode()
        return result
    }
}

/** One encrypted vote share, wire-ready for a helper server. */
data class VotingEncryptedShare(
    val c1: ByteArray,
    val c2: ByteArray,
    val shareIndex: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VotingEncryptedShare) return false
        return c1.contentEquals(other.c1) && c2.contentEquals(other.c2) && shareIndex == other.shareIndex
    }

    override fun hashCode(): Int {
        var result = c1.contentHashCode()
        result = 31 * result + c2.contentHashCode()
        result = 31 * result + shareIndex
        return result
    }
}

/**
 * The result of building an unsubmitted vote commitment. `shareBlinds`, `rVpk`, and `alphaV` are
 * sensitive reveal/signing inputs — callers must not log or expose them outside the voting
 * recovery path.
 */
data class VotingCommitmentResult(
    val vanNullifier: ByteArray,
    val voteAuthorityNoteNew: ByteArray,
    val voteCommitment: ByteArray,
    val proposalId: Int,
    val bundleIndex: Int,
    val proof: ByteArray,
    val encShares: List<VotingEncryptedShare>,
    val anchorHeight: Long,
    val voteRoundId: String,
    val sharesHash: ByteArray,
    val shareBlinds: List<ByteArray>,
    val shareComms: List<ByteArray>,
    val rVpk: ByteArray,
    val alphaV: ByteArray
) {
    override fun toString(): String = "VotingCommitmentResult(redacted)"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VotingCommitmentResult) return false
        return proposalId == other.proposalId &&
            bundleIndex == other.bundleIndex &&
            anchorHeight == other.anchorHeight &&
            voteRoundId == other.voteRoundId &&
            vanNullifier.contentEquals(other.vanNullifier) &&
            voteAuthorityNoteNew.contentEquals(other.voteAuthorityNoteNew) &&
            voteCommitment.contentEquals(other.voteCommitment) &&
            proof.contentEquals(other.proof) &&
            sharesHash.contentEquals(other.sharesHash) &&
            rVpk.contentEquals(other.rVpk) &&
            alphaV.contentEquals(other.alphaV) &&
            encShares == other.encShares &&
            shareBlinds.size == other.shareBlinds.size &&
            shareBlinds.zip(other.shareBlinds).all { (a, b) -> a.contentEquals(b) } &&
            shareComms.size == other.shareComms.size &&
            shareComms.zip(other.shareComms).all { (a, b) -> a.contentEquals(b) }
    }

    override fun hashCode(): Int {
        var result = vanNullifier.contentHashCode()
        result = 31 * result + proposalId
        result = 31 * result + bundleIndex
        result = 31 * result + voteRoundId.hashCode()
        return result
    }
}

/** The signed result of a one-shot `vote::commit` call, ready to broadcast and share out. */
data class VotingCommitResult(
    val bundleIndex: Int,
    val proposalId: Int,
    val choice: Int,
    val voteRoundId: String,
    val vanNullifier: ByteArray,
    val voteAuthorityNoteNew: ByteArray,
    val voteCommitment: ByteArray,
    val proof: ByteArray,
    val encShares: List<VotingEncryptedShare>,
    val anchorHeight: Long,
    val sharesHash: ByteArray,
    val shareComms: List<ByteArray>,
    val rVpk: ByteArray,
    val voteAuthSig: ByteArray,
    val sharePayloads: List<VotingSharePayload>
) {
    override fun toString(): String = "VotingCommitResult(redacted)"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VotingCommitResult) return false
        return bundleIndex == other.bundleIndex &&
            proposalId == other.proposalId &&
            choice == other.choice &&
            voteRoundId == other.voteRoundId &&
            anchorHeight == other.anchorHeight &&
            vanNullifier.contentEquals(other.vanNullifier) &&
            voteCommitment.contentEquals(other.voteCommitment) &&
            proof.contentEquals(other.proof) &&
            voteAuthSig.contentEquals(other.voteAuthSig) &&
            encShares == other.encShares &&
            sharePayloads == other.sharePayloads
    }

    override fun hashCode(): Int {
        var result = bundleIndex
        result = 31 * result + proposalId
        result = 31 * result + voteRoundId.hashCode()
        return result
    }
}

/** One share payload ready to send to a helper server for delegated submission. */
data class VotingSharePayload(
    val sharesHash: ByteArray,
    val proposalId: Int,
    val voteDecision: Int,
    val encShare: VotingEncryptedShare,
    val treePosition: Long,
    val allEncShares: List<VotingEncryptedShare>,
    val shareComms: List<ByteArray>,
    val primaryBlind: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VotingSharePayload) return false
        return sharesHash.contentEquals(other.sharesHash) &&
            proposalId == other.proposalId &&
            voteDecision == other.voteDecision &&
            encShare == other.encShare &&
            treePosition == other.treePosition &&
            allEncShares == other.allEncShares &&
            primaryBlind.contentEquals(other.primaryBlind)
    }

    override fun hashCode(): Int {
        var result = sharesHash.contentHashCode()
        result = 31 * result + proposalId
        result = 31 * result + voteDecision
        return result
    }
}

/** A stored voting hotkey identity. `storedSecret` is sensitive and must not be logged. */
data class VotingHotkey(
    val storedSecret: ByteArray,
    val rawAddress: ByteArray,
    val address: String
) {
    override fun toString(): String = "VotingHotkey(redacted)"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VotingHotkey) return false
        return storedSecret.contentEquals(other.storedSecret) &&
            rawAddress.contentEquals(other.rawAddress) &&
            address == other.address
    }

    override fun hashCode(): Int = address.hashCode()
}

/** The count/weight summary produced when a round's bundles are first laid out. */
data class VotingBundleSetupResult(
    val bundleCount: Int,
    val eligibleWeight: Long,
    val bundleWeights: List<Long>
)

/** An unsigned governance PCZT and its extracted extraction metadata. */
data class VotingGovernancePczt(
    val pcztBytes: ByteArray,
    val rk: ByteArray,
    val sighash: ByteArray,
    val actionIndex: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VotingGovernancePczt) return false
        return pcztBytes.contentEquals(other.pcztBytes) &&
            rk.contentEquals(other.rk) &&
            sighash.contentEquals(other.sighash) &&
            actionIndex == other.actionIndex
    }

    override fun hashCode(): Int = actionIndex
}

/** The canonical per-round phase, matching `zcash_voting::phases`' round-level model. */
enum class VotingRoundPhase {
    INITIALIZED,
    HOTKEY_GENERATED,
    DELEGATION_CONSTRUCTED,
    DELEGATION_PROVED,
    VOTE_READY
}

data class VotingRoundState(
    val roundId: String,
    val phase: VotingRoundPhase,
    val snapshotHeight: Long,
    val hotkeyAddress: String?,
    val delegatedWeight: Long?,
    val proofGenerated: Boolean
)

data class VotingRoundSummary(
    val roundId: String,
    val phase: VotingRoundPhase,
    val snapshotHeight: Long,
    val createdAt: Long
)

data class VotingVoteRecord(
    val proposalId: Int,
    val bundleIndex: Int,
    val choice: Int,
    val submitted: Boolean
)

/**
 * The canonical, per-bundle delegation phase (`prepared`, `pczt_built`, `proved`, `submitted`,
 * `confirmed`) — derived on read from persisted artifacts, distinct from the coarser
 * [VotingRoundState.phase]. Kept as a raw wire string (matching
 * `zcash_voting::phases::DelegationPhase::as_str`) rather than an enum here — callers that need
 * a typed enum define their own mapping, since the set of valid strings is owned by the Rust
 * crate, not this SDK.
 */
data class VotingDelegationPhase(
    val bundleIndex: Int,
    val phase: String
)

data class VotingDelegationPirPrecomputeResult(
    val cachedCount: Long,
    val fetchedCount: Long
)

data class VotingDelegationProofResult(
    val proof: ByteArray,
    val publicInputs: List<ByteArray>,
    val nfSigned: ByteArray,
    val cmxNew: ByteArray,
    val govNullifiers: List<ByteArray>,
    val vanComm: ByteArray,
    val rk: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VotingDelegationProofResult) return false
        return proof.contentEquals(other.proof) &&
            nfSigned.contentEquals(other.nfSigned) &&
            cmxNew.contentEquals(other.cmxNew) &&
            vanComm.contentEquals(other.vanComm) &&
            rk.contentEquals(other.rk) &&
            govNullifiers.size == other.govNullifiers.size &&
            govNullifiers.zip(other.govNullifiers).all { (a, b) -> a.contentEquals(b) }
    }

    override fun hashCode(): Int = rk.contentHashCode()
}

data class VotingDelegationSubmissionResult(
    val proof: ByteArray,
    val rk: ByteArray,
    val spendAuthSig: ByteArray,
    val sighash: ByteArray,
    val tx1Effects: ByteArray,
    val nfSigned: ByteArray,
    val cmxNew: ByteArray,
    val govComm: ByteArray,
    val govNullifiers: List<ByteArray>,
    val voteRoundId: String
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VotingDelegationSubmissionResult) return false
        return proof.contentEquals(other.proof) &&
            rk.contentEquals(other.rk) &&
            tx1Effects.contentEquals(other.tx1Effects) &&
            voteRoundId == other.voteRoundId
    }

    override fun hashCode(): Int = voteRoundId.hashCode()
}

/** Lookup result for a stored transaction hash — mirrors `TypesafeVotingBackend`'s internal `VotingTxHashLookup`. */
sealed interface VotingTxHashLookup {
    data object Missing : VotingTxHashLookup

    data class Found(val txHash: String) : VotingTxHashLookup
}

data class VotingCommitmentBundleRecord(
    val commitment: VotingCommitmentResult,
    val vcTreePosition: Long
)

data class VotingCommittedVoteRecord(
    val commit: VotingCommitResult,
    val vcTreePosition: Long
)

data class VotingShareDelegationRecord(
    val roundId: String,
    val bundleIndex: Int,
    val proposalId: Int,
    val shareIndex: Int,
    val sentToUrls: List<String>,
    val nullifier: ByteArray,
    val confirmed: Boolean,
    val submitAt: Long,
    val createdAt: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VotingShareDelegationRecord) return false
        return roundId == other.roundId &&
            bundleIndex == other.bundleIndex &&
            proposalId == other.proposalId &&
            shareIndex == other.shareIndex &&
            sentToUrls == other.sentToUrls &&
            nullifier.contentEquals(other.nullifier) &&
            confirmed == other.confirmed &&
            submitAt == other.submitAt &&
            createdAt == other.createdAt
    }

    override fun hashCode(): Int {
        var result = roundId.hashCode()
        result = 31 * result + bundleIndex
        result = 31 * result + proposalId
        result = 31 * result + shareIndex
        return result
    }
}
```

- [ ] **Step 2: Write a test asserting the enum/wire mappings this plan will rely on**

This file has no logic to test in isolation (pure data classes), but `VotingRoundPhase`'s ordinal order matters (Task 5 maps `JniRoundPhase.fromInt`'s ordering onto it) — pin it down now so a later accidental enum-reorder is caught immediately:

```kotlin
package cash.z.ecc.android.sdk.model.voting

import kotlin.test.Test
import kotlin.test.assertEquals

class VotingModelsTest {
    @Test
    fun votingRoundPhase_ordinal_order_matches_JniRoundPhase() {
        val expectedOrder =
            listOf(
                VotingRoundPhase.INITIALIZED,
                VotingRoundPhase.HOTKEY_GENERATED,
                VotingRoundPhase.DELEGATION_CONSTRUCTED,
                VotingRoundPhase.DELEGATION_PROVED,
                VotingRoundPhase.VOTE_READY
            )
        assertEquals(expectedOrder, VotingRoundPhase.entries)
    }
}
```

- [ ] **Step 3: Run the test**

Run: `./gradlew :sdk-lib:testDebugUnitTest --tests "cash.z.ecc.android.sdk.model.voting.VotingModelsTest"`

Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add sdk-lib/src/main/java/cash/z/ecc/android/sdk/model/voting/VotingModels.kt \
        sdk-lib/src/test/java/cash/z/ecc/android/sdk/model/voting/VotingModelsTest.kt
git commit -m "$(cat <<'EOF'
Add public voting model types for VotingSdk

Field-for-field public mirrors of the internal.model.voting.Jni*
DTOs, so VotingSdk's public surface (next commits) never leaks a
backend-lib or internal type onto the app's classpath.
EOF
)"
```

---

## Task 4: Define the `VotingSdk` and `VotingDbSession` public interfaces

**Files:**
- Create: `sdk-lib/src/main/java/cash/z/ecc/android/sdk/VotingSdk.kt`

**Interfaces:**
- Consumes: every type from Task 3 (`cash.z.ecc.android.sdk.model.voting.*`).
- Produces: `VotingSdk` (top-level, DB-independent ops + `openDb`/`isAvailable`) and `VotingDbSession` (per-round-DB ops) — `VotingSdkImpl`/`VotingDbSessionImpl` (Task 5/6) implement these; `feature-voting` (app-side plan) will be the only consumer.

- [ ] **Step 1: Write the file**

```kotlin
package cash.z.ecc.android.sdk

import cash.z.ecc.android.sdk.model.voting.VotingBundleSetupResult
import cash.z.ecc.android.sdk.model.voting.VotingCommitResult
import cash.z.ecc.android.sdk.model.voting.VotingCommitmentBundleRecord
import cash.z.ecc.android.sdk.model.voting.VotingCommitmentResult
import cash.z.ecc.android.sdk.model.voting.VotingCommittedVoteRecord
import cash.z.ecc.android.sdk.model.voting.VotingDelegationPhase
import cash.z.ecc.android.sdk.model.voting.VotingDelegationPirPrecomputeResult
import cash.z.ecc.android.sdk.model.voting.VotingDelegationProofResult
import cash.z.ecc.android.sdk.model.voting.VotingDelegationSubmissionResult
import cash.z.ecc.android.sdk.model.voting.VotingGovernancePczt
import cash.z.ecc.android.sdk.model.voting.VotingHotkey
import cash.z.ecc.android.sdk.model.voting.VotingNoteInfo
import cash.z.ecc.android.sdk.model.voting.VotingRoundState
import cash.z.ecc.android.sdk.model.voting.VotingRoundSummary
import cash.z.ecc.android.sdk.model.voting.VotingShareDelegationRecord
import cash.z.ecc.android.sdk.model.voting.VotingSharePayload
import cash.z.ecc.android.sdk.model.voting.VotingTxHashLookup
import cash.z.ecc.android.sdk.model.voting.VotingVanWitness
import cash.z.ecc.android.sdk.model.voting.VotingVoteRecord
import cash.z.ecc.android.sdk.model.voting.VotingWitness
import cash.z.ecc.android.sdk.model.AccountUuid
import cash.z.ecc.android.sdk.model.BlockHeight

/**
 * The public SDK entry point for shielded voting (CHP). The only sanctioned path from the app
 * into the voting Rust backend — see `VotingRustBackend`'s doc comment for the enforcement story.
 *
 * DB-independent crypto operations live directly here; anything scoped to one round's on-disk
 * state is behind [openDb]'s [VotingDbSession].
 */
@Suppress("TooManyFunctions", "LongParameterList")
interface VotingSdk {
    /**
     * True if this build's native library actually exports the voting JNI symbols. Callers must
     * check this before any other call — a mismatch between the app's runtime feature flag and
     * how this SDK artifact was compiled otherwise surfaces as an [UnsatisfiedLinkError] crash
     * instead of a graceful no-op. Cheap: does not open a database or touch the network.
     */
    suspend fun isAvailable(): Boolean

    /** Opens (creating if needed) the round database at [dbPath], scoped to one wallet/network. */
    suspend fun openDb(dbPath: String, walletId: String, networkId: Int): VotingDbSession

    suspend fun computeShareNullifier(voteCommitment: ByteArray, shareIndex: Int, blind: ByteArray): ByteArray

    suspend fun computeBundleSetup(notes: List<VotingNoteInfo>): VotingBundleSetupResult

    suspend fun warmProvingCaches()

    /**
     * Computes when a delegated helper share should submit, honoring the ceremony's last-moment
     * buffer window. Returns unix seconds; `0` means "submit immediately".
     */
    suspend fun scheduledShareSubmitAt(
        nowSeconds: Long,
        ceremonyStartSeconds: Long,
        voteEndTimeSeconds: Long,
        singleShare: Boolean
    ): Long

    suspend fun buildSharePayloads(
        commitment: VotingCommitmentResult,
        voteDecision: Int,
        numOptions: Int,
        vcTreePosition: Long,
        singleShareMode: Boolean = false
    ): List<VotingSharePayload>

    suspend fun extractOrchardFvkFromUfvk(ufvk: String, networkId: Int): ByteArray

    /**
     * Derives the raw Orchard address for the voting hotkey. The hotkey account index is fixed
     * by the Rust voting backend to match the vote-signing path — do not add an `accountIndex`
     * parameter unless that path changes with it.
     */
    suspend fun deriveHotkeyRawAddress(hotkeySeed: ByteArray, networkId: Int): ByteArray

    suspend fun extractNcRoot(treeStateBytes: ByteArray): ByteArray

    suspend fun verifyWitness(witness: VotingWitness): Boolean

    suspend fun getWalletNotes(
        walletDbPath: String,
        snapshotHeight: BlockHeight,
        networkId: Int,
        accountUuid: AccountUuid
    ): List<VotingNoteInfo>

    suspend fun extractPcztSighash(pcztBytes: ByteArray): ByteArray

    suspend fun extractSpendAuthSig(signedPcztBytes: ByteArray, actionIndex: Int): ByteArray

    companion object {
        /** Constructs the real, Rust-backed [VotingSdk]. No Android [android.content.Context] is needed at this layer. */
        fun new(): VotingSdk = cash.z.ecc.android.sdk.internal.VotingSdkImpl()
    }
}

/** One open round database. Callers must [close] it when done — mirrors [TypesafeVotingDb]'s lifecycle. */
@Suppress("TooManyFunctions", "LongParameterList")
interface VotingDbSession {
    suspend fun close()

    suspend fun initRound(
        roundId: String,
        snapshotHeight: Long,
        eaPK: ByteArray,
        ncRoot: ByteArray,
        nullifierIMTRoot: ByteArray,
        sessionJson: String?
    )

    suspend fun getRoundState(roundId: String): VotingRoundState?

    suspend fun listRounds(): List<VotingRoundSummary>

    suspend fun getBundleCount(roundId: String): Int

    suspend fun getVotes(roundId: String): List<VotingVoteRecord>

    suspend fun clearRound(roundId: String)

    suspend fun deleteSkippedBundles(roundId: String, keepCount: Int): Long

    suspend fun setupBundles(roundId: String, notes: List<VotingNoteInfo>): VotingBundleSetupResult

    /**
     * Mints or reconstructs a voting hotkey. An empty [storedSecret] mints a fresh, app-owned
     * random hotkey; a previously persisted [VotingHotkey.storedSecret] deterministically
     * reconstructs the same hotkey. Not scoped to a round.
     */
    suspend fun generateHotkey(storedSecret: ByteArray): VotingHotkey

    /**
     * Builds a governance PCZT for hardware-wallet flows. Trusts [fvkBytes]/[hotkeySecret] as
     * caller-derived Keystone input — does not validate a wallet seed against them. Software
     * callers holding the wallet seed should use [buildGovernancePcztFromSeed] instead.
     */
    suspend fun buildGovernancePczt(
        roundId: String,
        bundleIndex: Int,
        fvkBytes: ByteArray,
        hotkeySecret: ByteArray,
        accountIndex: Int,
        notes: List<VotingNoteInfo>,
        seedFingerprint: ByteArray,
        roundName: String
    ): VotingGovernancePczt

    /**
     * Builds a governance PCZT for software-wallet flows: derives the Orchard FVK from
     * [walletSeed] and rejects calls where it doesn't match [ufvk].
     */
    suspend fun buildGovernancePcztFromSeed(
        roundId: String,
        bundleIndex: Int,
        ufvk: String,
        networkId: Int,
        accountIndex: Int,
        notes: List<VotingNoteInfo>,
        walletSeed: ByteArray,
        hotkeySecret: ByteArray,
        seedFingerprint: ByteArray,
        roundName: String
    ): VotingGovernancePczt

    suspend fun storeWitnesses(roundId: String, bundleIndex: Int, notes: List<VotingNoteInfo>, witnesses: List<VotingWitness>)

    suspend fun precomputeDelegationPir(
        roundId: String,
        bundleIndex: Int,
        pirServerUrl: String,
        pirDepth: Int,
        pirTier0Layers: Int,
        pirTier1Layers: Int,
        notes: List<VotingNoteInfo>
    ): VotingDelegationPirPrecomputeResult

    suspend fun buildAndProveDelegation(
        roundId: String,
        bundleIndex: Int,
        pirServerUrl: String,
        pirDepth: Int,
        pirTier0Layers: Int,
        pirTier1Layers: Int,
        notes: List<VotingNoteInfo>,
        fvkBytes: ByteArray,
        hotkeySecret: ByteArray,
        seedFingerprint: ByteArray,
        accountIndex: Int,
        roundName: String,
        proofProgress: ((Double) -> Unit)? = null
    ): VotingDelegationProofResult

    suspend fun getDelegationSubmission(
        roundId: String,
        bundleIndex: Int,
        walletDbPath: String,
        accountUuid: String,
        hotkeySecret: ByteArray,
        roundName: String,
        senderSeed: ByteArray
    ): VotingDelegationSubmissionResult

    suspend fun getDelegationSubmissionWithKeystoneSig(
        roundId: String,
        bundleIndex: Int,
        keystoneSig: ByteArray,
        keystoneSighash: ByteArray
    ): VotingDelegationSubmissionResult

    /** The canonical per-bundle delegation phase for every bundle with recorded progress. */
    suspend fun delegationPhases(roundId: String): List<VotingDelegationPhase>

    /** Clears a bundle's unsigned delegation setup fields and any stale proof row. */
    suspend fun resetVotingSessionState(roundId: String)

    suspend fun storeTreeState(roundId: String, treeStateBytes: ByteArray)

    suspend fun generateNoteWitnesses(
        roundId: String,
        bundleIndex: Int,
        walletDbPath: String,
        networkId: Int,
        notes: List<VotingNoteInfo>
    ): List<VotingWitness>

    suspend fun syncVoteTree(roundId: String, nodeUrl: String): Long

    suspend fun resetTreeClient(roundId: String)

    suspend fun resetAllTreeClients()

    suspend fun storeVanPosition(roundId: String, bundleIndex: Int, position: Long)

    suspend fun generateVanWitness(roundId: String, bundleIndex: Int, anchorHeight: Long): VotingVanWitness

    suspend fun buildVoteCommitment(
        roundId: String,
        bundleIndex: Int,
        hotkeySecret: ByteArray,
        proposalId: Int,
        choice: Int,
        numOptions: Int,
        witness: VotingVanWitness,
        singleShare: Boolean = false,
        proofProgress: ((Double) -> Unit)? = null
    ): VotingCommitResult

    suspend fun storeDelegationTxHash(roundId: String, bundleIndex: Int, txHash: String)

    suspend fun getDelegationTxHash(roundId: String, bundleIndex: Int): VotingTxHashLookup

    suspend fun storeVoteTxHash(roundId: String, bundleIndex: Int, proposalId: Int, txHash: String)

    suspend fun markVoteSubmitted(roundId: String, bundleIndex: Int, proposalId: Int)

    suspend fun getVoteTxHash(roundId: String, bundleIndex: Int, proposalId: Int): VotingTxHashLookup

    suspend fun getCommitmentBundle(roundId: String, bundleIndex: Int, proposalId: Int): VotingCommitmentBundleRecord?

    /** Records the confirmed vote-commitment-tree position once a committed vote's tx is mined. */
    suspend fun recordVcPosition(roundId: String, bundleIndex: Int, proposalId: Int, vcTreePosition: Long)

    /** Recovers a signed committed vote together with its confirmed tree position from [recordVcPosition]. */
    suspend fun recoverCommittedVote(roundId: String, bundleIndex: Int, proposalId: Int): VotingCommittedVoteRecord

    suspend fun clearRecoveryState(roundId: String)

    suspend fun recordShareDelegation(
        roundId: String,
        bundleIndex: Int,
        proposalId: Int,
        shareIndex: Int,
        sentToUrls: List<String>,
        nullifier: ByteArray,
        submitAt: Long
    )

    suspend fun getShareDelegations(roundId: String): List<VotingShareDelegationRecord>

    suspend fun getUnconfirmedDelegations(roundId: String): List<VotingShareDelegationRecord>

    suspend fun markShareConfirmed(roundId: String, bundleIndex: Int, proposalId: Int, shareIndex: Int)

    /** Appends [newUrls] to the sent-server list for this share, ignoring duplicates. */
    suspend fun addSentServers(roundId: String, bundleIndex: Int, proposalId: Int, shareIndex: Int, newUrls: List<String>)
}
```

- [ ] **Step 2: Compile-check (interfaces only, no impl exists yet — expect a specific failure)**

Run: `./gradlew :sdk-lib:compileDebugKotlin`

Expected: FAILS with `Unresolved reference: VotingSdkImpl` (the `companion object`'s `new()` references the class Task 5 creates). This confirms the interface file itself is syntactically valid and every model-type reference in it resolves — the only failure should be that one missing class.

- [ ] **Step 3: Commit**

```bash
git add sdk-lib/src/main/java/cash/z/ecc/android/sdk/VotingSdk.kt
git commit -m "$(cat <<'EOF'
Define public VotingSdk/VotingDbSession interfaces

Mirrors TypesafeVotingBackend/TypesafeVotingDb's full surface using
only the public model types from the previous commit — no Jni*/
internal type appears here. VotingSdkImpl (next commit) implements
these.
EOF
)"
```

---

## Task 5: Implement `VotingSdkImpl` — session lifecycle, hotkey, PCZT construction

First half of the implementation: the top-level `VotingSdk` methods, `openDb`/`isAvailable`, and every `VotingDbSession` method through PCZT construction. Task 6 covers the rest (PIR/proof/submission, tree sync, vote commitment, share bookkeeping) in the same two files.

**Files:**
- Create: `sdk-lib/src/main/java/cash/z/ecc/android/sdk/internal/VotingSdkImpl.kt`
- Test: `sdk-lib/src/test/java/cash/z/ecc/android/sdk/internal/VotingSdkImplTest.kt`

**Interfaces:**
- Consumes: `TypesafeVotingBackend`/`TypesafeVotingDb` (existing + Task 1's additions), `TypesafeVotingBackendImpl()` (existing, no-arg constructible — see `TypesafeVotingBackendImpl.kt:43-48`'s default `rustBackendFactory` param).
- Produces: `VotingSdkImpl : VotingSdk`, `VotingDbSessionImpl : VotingDbSession` — referenced by `VotingSdk.Companion.new()` (Task 4).

- [ ] **Step 1: Write the file (part 1 — class shell, `isAvailable`/`openDb`, and every top-level method)**

```kotlin
package cash.z.ecc.android.sdk.internal

import cash.z.ecc.android.sdk.VotingDbSession
import cash.z.ecc.android.sdk.VotingSdk
import cash.z.ecc.android.sdk.internal.model.voting.JniDelegationPhase
import cash.z.ecc.android.sdk.internal.model.voting.JniRoundPhase
import cash.z.ecc.android.sdk.model.AccountUuid
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.voting.VotingBundleSetupResult
import cash.z.ecc.android.sdk.model.voting.VotingCommitResult
import cash.z.ecc.android.sdk.model.voting.VotingCommitmentBundleRecord
import cash.z.ecc.android.sdk.model.voting.VotingCommitmentResult
import cash.z.ecc.android.sdk.model.voting.VotingCommittedVoteRecord
import cash.z.ecc.android.sdk.model.voting.VotingDelegationPhase
import cash.z.ecc.android.sdk.model.voting.VotingDelegationPirPrecomputeResult
import cash.z.ecc.android.sdk.model.voting.VotingDelegationProofResult
import cash.z.ecc.android.sdk.model.voting.VotingDelegationSubmissionResult
import cash.z.ecc.android.sdk.model.voting.VotingEncryptedShare
import cash.z.ecc.android.sdk.model.voting.VotingGovernancePczt
import cash.z.ecc.android.sdk.model.voting.VotingHotkey
import cash.z.ecc.android.sdk.model.voting.VotingNoteInfo
import cash.z.ecc.android.sdk.model.voting.VotingNoteScope
import cash.z.ecc.android.sdk.model.voting.VotingRoundPhase
import cash.z.ecc.android.sdk.model.voting.VotingRoundState
import cash.z.ecc.android.sdk.model.voting.VotingRoundSummary
import cash.z.ecc.android.sdk.model.voting.VotingShareDelegationRecord
import cash.z.ecc.android.sdk.model.voting.VotingSharePayload
import cash.z.ecc.android.sdk.model.voting.VotingTxHashLookup
import cash.z.ecc.android.sdk.model.voting.VotingVanWitness
import cash.z.ecc.android.sdk.model.voting.VotingVoteRecord
import cash.z.ecc.android.sdk.model.voting.VotingWitness

@Suppress("TooManyFunctions", "LongParameterList")
internal class VotingSdkImpl(
    private val backend: TypesafeVotingBackend = TypesafeVotingBackendImpl()
) : VotingSdk {
    override suspend fun isAvailable(): Boolean =
        runCatching { backend.warmProvingCaches() }
            .fold(onSuccess = { true }, onFailure = { it !is UnsatisfiedLinkError })

    override suspend fun openDb(dbPath: String, walletId: String, networkId: Int): VotingDbSession =
        VotingDbSessionImpl(backend.openVotingDb(dbPath, walletId, networkId))

    override suspend fun computeShareNullifier(voteCommitment: ByteArray, shareIndex: Int, blind: ByteArray): ByteArray =
        backend.computeShareNullifier(voteCommitment, shareIndex, blind)

    override suspend fun computeBundleSetup(notes: List<VotingNoteInfo>): VotingBundleSetupResult =
        backend.computeBundleSetup(notes.map { it.toInternal() }).toPublic()

    override suspend fun warmProvingCaches() = backend.warmProvingCaches()

    override suspend fun scheduledShareSubmitAt(
        nowSeconds: Long,
        ceremonyStartSeconds: Long,
        voteEndTimeSeconds: Long,
        singleShare: Boolean
    ): Long = backend.scheduledShareSubmitAt(nowSeconds, ceremonyStartSeconds, voteEndTimeSeconds, singleShare)

    override suspend fun buildSharePayloads(
        commitment: VotingCommitmentResult,
        voteDecision: Int,
        numOptions: Int,
        vcTreePosition: Long,
        singleShareMode: Boolean
    ): List<VotingSharePayload> =
        backend
            .buildSharePayloads(commitment.toInternal(), voteDecision, numOptions, vcTreePosition, singleShareMode)
            .map { it.toPublic() }

    override suspend fun extractOrchardFvkFromUfvk(ufvk: String, networkId: Int): ByteArray =
        backend.extractOrchardFvkFromUfvk(ufvk, networkId)

    override suspend fun deriveHotkeyRawAddress(hotkeySeed: ByteArray, networkId: Int): ByteArray =
        backend.deriveHotkeyRawAddress(hotkeySeed, networkId)

    override suspend fun extractNcRoot(treeStateBytes: ByteArray): ByteArray = backend.extractNcRoot(treeStateBytes)

    override suspend fun verifyWitness(witness: VotingWitness): Boolean = backend.verifyWitness(witness.toInternal())

    override suspend fun getWalletNotes(
        walletDbPath: String,
        snapshotHeight: BlockHeight,
        networkId: Int,
        accountUuid: AccountUuid
    ): List<VotingNoteInfo> = backend.getWalletNotes(walletDbPath, snapshotHeight, networkId, accountUuid).map { it.toPublic() }

    override suspend fun extractPcztSighash(pcztBytes: ByteArray): ByteArray = backend.extractPcztSighash(pcztBytes)

    override suspend fun extractSpendAuthSig(signedPcztBytes: ByteArray, actionIndex: Int): ByteArray =
        backend.extractSpendAuthSig(signedPcztBytes, actionIndex)
}
```

- [ ] **Step 2: Append `VotingDbSessionImpl`'s shell + lifecycle/hotkey/PCZT methods to the same file**

```kotlin

@Suppress("TooManyFunctions", "LongParameterList")
internal class VotingDbSessionImpl(
    private val db: TypesafeVotingDb
) : VotingDbSession {
    override suspend fun close() = db.close()

    override suspend fun initRound(
        roundId: String,
        snapshotHeight: Long,
        eaPK: ByteArray,
        ncRoot: ByteArray,
        nullifierIMTRoot: ByteArray,
        sessionJson: String?
    ) = db.initRound(roundId, snapshotHeight, eaPK, ncRoot, nullifierIMTRoot, sessionJson)

    override suspend fun getRoundState(roundId: String): VotingRoundState? = db.getRoundState(roundId)?.toPublic()

    override suspend fun listRounds(): List<VotingRoundSummary> = db.listRounds().map { it.toPublic() }

    override suspend fun getBundleCount(roundId: String): Int = db.getBundleCount(roundId)

    override suspend fun getVotes(roundId: String): List<VotingVoteRecord> = db.getVotes(roundId).map { it.toPublic() }

    override suspend fun clearRound(roundId: String) = db.clearRound(roundId)

    override suspend fun deleteSkippedBundles(roundId: String, keepCount: Int): Long =
        db.deleteSkippedBundles(roundId, keepCount)

    override suspend fun setupBundles(roundId: String, notes: List<VotingNoteInfo>): VotingBundleSetupResult =
        db.setupBundles(roundId, notes.map { it.toInternal() }).toPublic()

    override suspend fun generateHotkey(storedSecret: ByteArray): VotingHotkey = db.generateHotkey(storedSecret).toPublic()

    override suspend fun buildGovernancePczt(
        roundId: String,
        bundleIndex: Int,
        fvkBytes: ByteArray,
        hotkeySecret: ByteArray,
        accountIndex: Int,
        notes: List<VotingNoteInfo>,
        seedFingerprint: ByteArray,
        roundName: String
    ): VotingGovernancePczt =
        db
            .buildGovernancePczt(
                roundId, bundleIndex, fvkBytes, hotkeySecret, accountIndex,
                notes.map { it.toInternal() }, seedFingerprint, roundName
            ).toPublic()

    override suspend fun buildGovernancePcztFromSeed(
        roundId: String,
        bundleIndex: Int,
        ufvk: String,
        networkId: Int,
        accountIndex: Int,
        notes: List<VotingNoteInfo>,
        walletSeed: ByteArray,
        hotkeySecret: ByteArray,
        seedFingerprint: ByteArray,
        roundName: String
    ): VotingGovernancePczt =
        db
            .buildGovernancePcztFromSeed(
                roundId, bundleIndex, ufvk, networkId, accountIndex,
                notes.map { it.toInternal() }, walletSeed, hotkeySecret, seedFingerprint, roundName
            ).toPublic()

    override suspend fun storeWitnesses(
        roundId: String,
        bundleIndex: Int,
        notes: List<VotingNoteInfo>,
        witnesses: List<VotingWitness>
    ) = db.storeWitnesses(roundId, bundleIndex, notes.map { it.toInternal() }, witnesses.map { it.toInternal() })

    override suspend fun delegationPhases(roundId: String): List<VotingDelegationPhase> =
        db.delegationPhases(roundId).map { it.toPublic() }

    override suspend fun resetVotingSessionState(roundId: String) = db.resetVotingSessionState(roundId)
}
```

Note: `Task 6` appends the remaining `VotingDbSessionImpl` overrides (`precomputeDelegationPir` through `addSentServers`) to this same class body — do not close the class here; leave the closing `}` for Task 6 to land after its additions, or (simpler for review) add a `TODO()`-free placeholder is not allowed, so instead: leave this file's `VotingDbSessionImpl` class body exactly as above with its closing `}` present, and Task 6 re-opens the file to insert its methods before that closing brace. Either ordering compiles as long as the final file has every `VotingDbSession` method implemented before Task 6's build-verification step.

- [ ] **Step 3: Write the `toPublic()`/`toInternal()` mapping extensions in a companion file**

**Files:**
- Create: `sdk-lib/src/main/java/cash/z/ecc/android/sdk/internal/VotingSdkMappers.kt`

```kotlin
package cash.z.ecc.android.sdk.internal

import cash.z.ecc.android.sdk.internal.model.voting.JniBundleSetupResult
import cash.z.ecc.android.sdk.internal.model.voting.JniDelegationPhase
import cash.z.ecc.android.sdk.internal.model.voting.JniGovernancePczt
import cash.z.ecc.android.sdk.internal.model.voting.JniNoteInfo
import cash.z.ecc.android.sdk.internal.model.voting.JniRoundPhase
import cash.z.ecc.android.sdk.internal.model.voting.JniRoundState
import cash.z.ecc.android.sdk.internal.model.voting.JniRoundSummary
import cash.z.ecc.android.sdk.internal.model.voting.JniSharePayload
import cash.z.ecc.android.sdk.internal.model.voting.JniVanWitness
import cash.z.ecc.android.sdk.internal.model.voting.JniVoteCommitResult
import cash.z.ecc.android.sdk.internal.model.voting.JniVoteCommitmentResult
import cash.z.ecc.android.sdk.internal.model.voting.JniVoteRecord
import cash.z.ecc.android.sdk.internal.model.voting.JniVotingHotkey
import cash.z.ecc.android.sdk.internal.model.voting.JniWireEncryptedShare
import cash.z.ecc.android.sdk.internal.model.voting.JniWitnessData
import cash.z.ecc.android.sdk.model.voting.VotingBundleSetupResult
import cash.z.ecc.android.sdk.model.voting.VotingCommitResult
import cash.z.ecc.android.sdk.model.voting.VotingCommitmentResult
import cash.z.ecc.android.sdk.model.voting.VotingEncryptedShare
import cash.z.ecc.android.sdk.model.voting.VotingGovernancePczt
import cash.z.ecc.android.sdk.model.voting.VotingHotkey
import cash.z.ecc.android.sdk.model.voting.VotingNoteInfo
import cash.z.ecc.android.sdk.model.voting.VotingNoteScope
import cash.z.ecc.android.sdk.model.voting.VotingRoundPhase
import cash.z.ecc.android.sdk.model.voting.VotingRoundState
import cash.z.ecc.android.sdk.model.voting.VotingRoundSummary
import cash.z.ecc.android.sdk.model.voting.VotingSharePayload
import cash.z.ecc.android.sdk.model.voting.VotingVanWitness
import cash.z.ecc.android.sdk.model.voting.VotingVoteRecord
import cash.z.ecc.android.sdk.model.voting.VotingWitness

internal fun JniRoundPhase.toPublic(): VotingRoundPhase =
    when (this) {
        JniRoundPhase.INITIALIZED -> VotingRoundPhase.INITIALIZED
        JniRoundPhase.HOTKEY_GENERATED -> VotingRoundPhase.HOTKEY_GENERATED
        JniRoundPhase.DELEGATION_CONSTRUCTED -> VotingRoundPhase.DELEGATION_CONSTRUCTED
        JniRoundPhase.DELEGATION_PROVED -> VotingRoundPhase.DELEGATION_PROVED
        JniRoundPhase.VOTE_READY -> VotingRoundPhase.VOTE_READY
    }

internal fun JniNoteInfo.toPublic(): VotingNoteInfo =
    VotingNoteInfo(
        commitment = commitment,
        nullifier = nullifier,
        value = value,
        position = position,
        diversifier = diversifier,
        rho = rho,
        rseed = rseed,
        scope = if (scope == 0) VotingNoteScope.EXTERNAL else VotingNoteScope.INTERNAL,
        ufvk = ufvk
    )

internal fun JniWitnessData.toPublic(): VotingWitness =
    VotingWitness(noteCommitment = noteCommitment, position = position, root = root, authPath = authPath)

internal fun VotingWitness.toInternal(): JniWitnessData =
    JniWitnessData(noteCommitment = noteCommitment, position = position, root = root, authPath = authPath)

internal fun JniVanWitness.toPublic(): VotingVanWitness =
    VotingVanWitness(authPath = authPath, position = position, anchorHeight = anchorHeight)

internal fun VotingVanWitness.toInternal(): JniVanWitness =
    JniVanWitness(authPath = authPath, position = position, anchorHeight = anchorHeight)

internal fun JniWireEncryptedShare.toPublic(): VotingEncryptedShare =
    VotingEncryptedShare(c1 = c1, c2 = c2, shareIndex = shareIndex)

internal fun VotingEncryptedShare.toInternal(): JniWireEncryptedShare =
    JniWireEncryptedShare(c1 = c1, c2 = c2, shareIndex = shareIndex)

internal fun JniVoteCommitmentResult.toPublic(): VotingCommitmentResult =
    VotingCommitmentResult(
        vanNullifier = vanNullifier,
        voteAuthorityNoteNew = voteAuthorityNoteNew,
        voteCommitment = voteCommitment,
        proposalId = proposalId,
        bundleIndex = bundleIndex,
        proof = proof,
        encShares = encShares.map { it.toPublic() },
        anchorHeight = anchorHeight,
        voteRoundId = voteRoundId,
        sharesHash = sharesHash,
        shareBlinds = shareBlinds,
        shareComms = shareComms,
        rVpk = rVpk,
        alphaV = alphaV
    )

internal fun VotingCommitmentResult.toInternal(): JniVoteCommitmentResult =
    JniVoteCommitmentResult(
        vanNullifier = vanNullifier,
        voteAuthorityNoteNew = voteAuthorityNoteNew,
        voteCommitment = voteCommitment,
        proposalId = proposalId,
        bundleIndex = bundleIndex,
        proof = proof,
        encShares = encShares.map { it.toInternal() },
        anchorHeight = anchorHeight,
        voteRoundId = voteRoundId,
        sharesHash = sharesHash,
        shareBlinds = shareBlinds,
        shareComms = shareComms,
        rVpk = rVpk,
        alphaV = alphaV
    )

internal fun JniVoteCommitResult.toPublic(): VotingCommitResult =
    VotingCommitResult(
        bundleIndex = bundleIndex,
        proposalId = proposalId,
        choice = choice,
        voteRoundId = voteRoundId,
        vanNullifier = vanNullifier,
        voteAuthorityNoteNew = voteAuthorityNoteNew,
        voteCommitment = voteCommitment,
        proof = proof,
        encShares = encShares.map { it.toPublic() },
        anchorHeight = anchorHeight,
        sharesHash = sharesHash,
        shareComms = shareComms,
        rVpk = rVpk,
        voteAuthSig = voteAuthSig,
        sharePayloads = sharePayloads.map { it.toPublic() }
    )

internal fun JniSharePayload.toPublic(): VotingSharePayload =
    VotingSharePayload(
        sharesHash = sharesHash,
        proposalId = proposalId,
        voteDecision = voteDecision,
        encShare = encShare.toPublic(),
        treePosition = treePosition,
        allEncShares = allEncShares.map { it.toPublic() },
        shareComms = shareComms,
        primaryBlind = primaryBlind
    )

internal fun JniVotingHotkey.toPublic(): VotingHotkey =
    VotingHotkey(storedSecret = storedSecret, rawAddress = rawAddress, address = address)

internal fun JniBundleSetupResult.toPublic(): VotingBundleSetupResult =
    VotingBundleSetupResult(bundleCount = bundleCount, eligibleWeight = eligibleWeight, bundleWeights = bundleWeights)

internal fun JniGovernancePczt.toPublic(): VotingGovernancePczt =
    VotingGovernancePczt(pcztBytes = pcztBytes, rk = rk, sighash = sighash, actionIndex = actionIndex)

internal fun JniRoundState.toPublic(): VotingRoundState =
    VotingRoundState(
        roundId = roundId,
        phase = roundPhase.toPublic(),
        snapshotHeight = snapshotHeight,
        hotkeyAddress = hotkeyAddress,
        delegatedWeight = delegatedWeight,
        proofGenerated = proofGenerated
    )

internal fun JniRoundSummary.toPublic(): VotingRoundSummary =
    VotingRoundSummary(roundId = roundId, phase = roundPhase.toPublic(), snapshotHeight = snapshotHeight, createdAt = createdAt)

internal fun JniVoteRecord.toPublic(): cash.z.ecc.android.sdk.model.voting.VotingVoteRecord =
    cash.z.ecc.android.sdk.model.voting.VotingVoteRecord(
        proposalId = proposalId,
        bundleIndex = bundleIndex,
        choice = choice,
        submitted = submitted
    )

internal fun JniDelegationPhase.toPublic(): cash.z.ecc.android.sdk.model.voting.VotingDelegationPhase =
    cash.z.ecc.android.sdk.model.voting.VotingDelegationPhase(bundleIndex = bundleIndex, phase = phase)

internal fun VotingNoteInfo.toInternal(): cash.z.ecc.android.sdk.internal.VotingNoteInfo =
    cash.z.ecc.android.sdk.internal.VotingNoteInfo(
        commitment = commitment,
        nullifier = nullifier,
        value = value,
        position = position,
        diversifier = diversifier,
        rho = rho,
        rseed = rseed,
        scope =
            if (scope == VotingNoteScope.EXTERNAL) {
                cash.z.ecc.android.sdk.internal.VotingNoteScope.EXTERNAL
            } else {
                cash.z.ecc.android.sdk.internal.VotingNoteScope.INTERNAL
            },
        ufvk = ufvk
    )
```

`TypesafeVotingBackend.kt` already defines an `internal data class VotingNoteInfo` and `internal enum class VotingNoteScope` in the `cash.z.ecc.android.sdk.internal` package — same names as this plan's new public types in `cash.z.ecc.android.sdk.model.voting`, in a different package. That's why the function above fully qualifies every reference to the internal ones (`cash.z.ecc.android.sdk.internal.VotingNoteInfo`/`.VotingNoteScope`): a bare `VotingNoteInfo`/`VotingNoteScope` would resolve to the public ones already imported at the top of this file for the public side of each mapping, not the internal ones this function needs to construct.

- [ ] **Step 4: Compile-check**

Run: `./gradlew :sdk-lib:compileDebugKotlin`

Expected: still fails — `VotingDbSessionImpl` doesn't yet implement every `VotingDbSession` method (Task 6 finishes it). Confirm the *only* errors are "class VotingDbSessionImpl is not abstract and does not implement abstract member" for the specific methods Task 6 adds (list them against `VotingDbSdk.kt`'s interface to confirm nothing from Task 5's own scope — lifecycle/hotkey/PCZT — is among the errors).

- [ ] **Step 5: Commit**

```bash
git add sdk-lib/src/main/java/cash/z/ecc/android/sdk/internal/VotingSdkImpl.kt \
        sdk-lib/src/main/java/cash/z/ecc/android/sdk/internal/VotingSdkMappers.kt
git commit -m "$(cat <<'EOF'
Implement VotingSdkImpl part 1: lifecycle, hotkey, PCZT construction

Top-level VotingSdk methods plus VotingDbSession's session/hotkey/
governance-PCZT operations, delegating to TypesafeVotingBackend and
converting through the new public model types. Not yet compilable on
its own — VotingDbSessionImpl is completed by the next commit.
EOF
)"
```

---

## Task 6: Implement `VotingSdkImpl` — PIR/proof/submission, tree sync, vote commitment, share bookkeeping

Completes `VotingDbSessionImpl` with the remaining `VotingDbSession` methods, and adds their model-mapping extensions.

**Files:**
- Modify: `sdk-lib/src/main/java/cash/z/ecc/android/sdk/internal/VotingSdkImpl.kt`
- Modify: `sdk-lib/src/main/java/cash/z/ecc/android/sdk/internal/VotingSdkMappers.kt`

**Interfaces:**
- Consumes: `TypesafeVotingDb`'s remaining methods (`precomputeDelegationPir` through `addSentServers`), Task 5's mapping extensions.
- Produces: a fully-implemented `VotingDbSessionImpl` — Task 7's tests and the app-side plan both depend on this being complete.

- [ ] **Step 1: Insert the remaining `VotingDbSessionImpl` overrides**

In `VotingSdkImpl.kt`, insert the following inside `VotingDbSessionImpl`'s class body, right before its closing `}` (after `resetVotingSessionState`, added by Task 5):

```kotlin

    override suspend fun precomputeDelegationPir(
        roundId: String,
        bundleIndex: Int,
        pirServerUrl: String,
        pirDepth: Int,
        pirTier0Layers: Int,
        pirTier1Layers: Int,
        notes: List<VotingNoteInfo>
    ): VotingDelegationPirPrecomputeResult =
        db
            .precomputeDelegationPir(
                roundId, bundleIndex, pirServerUrl, pirDepth, pirTier0Layers, pirTier1Layers,
                notes.map { it.toInternal() }
            ).toPublic()

    override suspend fun buildAndProveDelegation(
        roundId: String,
        bundleIndex: Int,
        pirServerUrl: String,
        pirDepth: Int,
        pirTier0Layers: Int,
        pirTier1Layers: Int,
        notes: List<VotingNoteInfo>,
        fvkBytes: ByteArray,
        hotkeySecret: ByteArray,
        seedFingerprint: ByteArray,
        accountIndex: Int,
        roundName: String,
        proofProgress: ((Double) -> Unit)?
    ): VotingDelegationProofResult =
        db
            .buildAndProveDelegation(
                roundId, bundleIndex, pirServerUrl, pirDepth, pirTier0Layers, pirTier1Layers,
                notes.map { it.toInternal() }, fvkBytes, hotkeySecret, seedFingerprint, accountIndex,
                roundName, proofProgress
            ).toPublic()

    override suspend fun getDelegationSubmission(
        roundId: String,
        bundleIndex: Int,
        walletDbPath: String,
        accountUuid: String,
        hotkeySecret: ByteArray,
        roundName: String,
        senderSeed: ByteArray
    ): VotingDelegationSubmissionResult =
        db
            .getDelegationSubmission(roundId, bundleIndex, walletDbPath, accountUuid, hotkeySecret, roundName, senderSeed)
            .toPublic()

    override suspend fun getDelegationSubmissionWithKeystoneSig(
        roundId: String,
        bundleIndex: Int,
        keystoneSig: ByteArray,
        keystoneSighash: ByteArray
    ): VotingDelegationSubmissionResult =
        db.getDelegationSubmissionWithKeystoneSig(roundId, bundleIndex, keystoneSig, keystoneSighash).toPublic()

    override suspend fun storeTreeState(roundId: String, treeStateBytes: ByteArray) = db.storeTreeState(roundId, treeStateBytes)

    override suspend fun generateNoteWitnesses(
        roundId: String,
        bundleIndex: Int,
        walletDbPath: String,
        networkId: Int,
        notes: List<VotingNoteInfo>
    ): List<VotingWitness> =
        db.generateNoteWitnesses(roundId, bundleIndex, walletDbPath, networkId, notes.map { it.toInternal() })
            .map { it.toPublic() }

    override suspend fun syncVoteTree(roundId: String, nodeUrl: String): Long = db.syncVoteTree(roundId, nodeUrl)

    override suspend fun resetTreeClient(roundId: String) = db.resetTreeClient(roundId)

    override suspend fun resetAllTreeClients() = db.resetAllTreeClients()

    override suspend fun storeVanPosition(roundId: String, bundleIndex: Int, position: Long) =
        db.storeVanPosition(roundId, bundleIndex, position)

    override suspend fun generateVanWitness(roundId: String, bundleIndex: Int, anchorHeight: Long): VotingVanWitness =
        db.generateVanWitness(roundId, bundleIndex, anchorHeight).toPublic()

    override suspend fun buildVoteCommitment(
        roundId: String,
        bundleIndex: Int,
        hotkeySecret: ByteArray,
        proposalId: Int,
        choice: Int,
        numOptions: Int,
        witness: VotingVanWitness,
        singleShare: Boolean,
        proofProgress: ((Double) -> Unit)?
    ): VotingCommitResult =
        db
            .buildVoteCommitment(
                roundId, bundleIndex, hotkeySecret, proposalId, choice, numOptions,
                witness.toInternal(), singleShare, proofProgress
            ).toPublic()

    override suspend fun storeDelegationTxHash(roundId: String, bundleIndex: Int, txHash: String) =
        db.storeDelegationTxHash(roundId, bundleIndex, txHash)

    override suspend fun getDelegationTxHash(roundId: String, bundleIndex: Int): VotingTxHashLookup =
        db.getDelegationTxHash(roundId, bundleIndex).toPublic()

    override suspend fun storeVoteTxHash(roundId: String, bundleIndex: Int, proposalId: Int, txHash: String) =
        db.storeVoteTxHash(roundId, bundleIndex, proposalId, txHash)

    override suspend fun markVoteSubmitted(roundId: String, bundleIndex: Int, proposalId: Int) =
        db.markVoteSubmitted(roundId, bundleIndex, proposalId)

    override suspend fun getVoteTxHash(roundId: String, bundleIndex: Int, proposalId: Int): VotingTxHashLookup =
        db.getVoteTxHash(roundId, bundleIndex, proposalId).toPublic()

    override suspend fun getCommitmentBundle(roundId: String, bundleIndex: Int, proposalId: Int): VotingCommitmentBundleRecord? =
        db.getCommitmentBundle(roundId, bundleIndex, proposalId)?.toPublic()

    override suspend fun recordVcPosition(roundId: String, bundleIndex: Int, proposalId: Int, vcTreePosition: Long) =
        db.recordVcPosition(roundId, bundleIndex, proposalId, vcTreePosition)

    override suspend fun recoverCommittedVote(roundId: String, bundleIndex: Int, proposalId: Int): VotingCommittedVoteRecord =
        db.recoverCommittedVote(roundId, bundleIndex, proposalId).toPublic()

    override suspend fun clearRecoveryState(roundId: String) = db.clearRecoveryState(roundId)

    override suspend fun recordShareDelegation(
        roundId: String,
        bundleIndex: Int,
        proposalId: Int,
        shareIndex: Int,
        sentToUrls: List<String>,
        nullifier: ByteArray,
        submitAt: Long
    ) = db.recordShareDelegation(roundId, bundleIndex, proposalId, shareIndex, sentToUrls, nullifier, submitAt)

    override suspend fun getShareDelegations(roundId: String): List<VotingShareDelegationRecord> =
        db.getShareDelegations(roundId).map { it.toPublic() }

    override suspend fun getUnconfirmedDelegations(roundId: String): List<VotingShareDelegationRecord> =
        db.getUnconfirmedDelegations(roundId).map { it.toPublic() }

    override suspend fun markShareConfirmed(roundId: String, bundleIndex: Int, proposalId: Int, shareIndex: Int) =
        db.markShareConfirmed(roundId, bundleIndex, proposalId, shareIndex)

    override suspend fun addSentServers(
        roundId: String,
        bundleIndex: Int,
        proposalId: Int,
        shareIndex: Int,
        newUrls: List<String>
    ) = db.addSentServers(roundId, bundleIndex, proposalId, shareIndex, newUrls)
```

- [ ] **Step 2: Add the remaining mapping extensions to `VotingSdkMappers.kt`**

```kotlin
internal fun cash.z.ecc.android.sdk.internal.DelegationPirPrecomputeResult.toPublic():
    cash.z.ecc.android.sdk.model.voting.VotingDelegationPirPrecomputeResult =
    cash.z.ecc.android.sdk.model.voting.VotingDelegationPirPrecomputeResult(
        cachedCount = cachedCount,
        fetchedCount = fetchedCount
    )

internal fun cash.z.ecc.android.sdk.internal.DelegationProofResult.toPublic():
    cash.z.ecc.android.sdk.model.voting.VotingDelegationProofResult =
    cash.z.ecc.android.sdk.model.voting.VotingDelegationProofResult(
        proof = proof,
        publicInputs = publicInputs,
        nfSigned = nfSigned,
        cmxNew = cmxNew,
        govNullifiers = govNullifiers,
        vanComm = vanComm,
        rk = rk
    )

internal fun cash.z.ecc.android.sdk.internal.DelegationSubmissionResult.toPublic():
    cash.z.ecc.android.sdk.model.voting.VotingDelegationSubmissionResult =
    cash.z.ecc.android.sdk.model.voting.VotingDelegationSubmissionResult(
        proof = proof,
        rk = rk,
        spendAuthSig = spendAuthSig,
        sighash = sighash,
        tx1Effects = tx1Effects,
        nfSigned = nfSigned,
        cmxNew = cmxNew,
        govComm = govComm,
        govNullifiers = govNullifiers,
        voteRoundId = voteRoundId
    )

internal fun cash.z.ecc.android.sdk.internal.VotingTxHashLookup.toPublic():
    cash.z.ecc.android.sdk.model.voting.VotingTxHashLookup =
    when (this) {
        is cash.z.ecc.android.sdk.internal.VotingTxHashLookup.Missing ->
            cash.z.ecc.android.sdk.model.voting.VotingTxHashLookup.Missing
        is cash.z.ecc.android.sdk.internal.VotingTxHashLookup.Found ->
            cash.z.ecc.android.sdk.model.voting.VotingTxHashLookup.Found(txHash)
    }

internal fun cash.z.ecc.android.sdk.internal.CommitmentBundleRecord.toPublic():
    cash.z.ecc.android.sdk.model.voting.VotingCommitmentBundleRecord =
    cash.z.ecc.android.sdk.model.voting.VotingCommitmentBundleRecord(
        commitment = commitment.toPublic(),
        vcTreePosition = vcTreePosition
    )

internal fun cash.z.ecc.android.sdk.internal.CommittedVoteRecord.toPublic():
    cash.z.ecc.android.sdk.model.voting.VotingCommittedVoteRecord =
    cash.z.ecc.android.sdk.model.voting.VotingCommittedVoteRecord(
        commit = commit.toPublic(),
        vcTreePosition = vcTreePosition
    )

internal fun cash.z.ecc.android.sdk.internal.ShareDelegationRecord.toPublic():
    cash.z.ecc.android.sdk.model.voting.VotingShareDelegationRecord =
    cash.z.ecc.android.sdk.model.voting.VotingShareDelegationRecord(
        roundId = roundId,
        bundleIndex = bundleIndex,
        proposalId = proposalId,
        shareIndex = shareIndex,
        sentToUrls = sentToUrls,
        nullifier = nullifier,
        confirmed = confirmed,
        submitAt = submitAt,
        createdAt = createdAt
    )
```

- [ ] **Step 3: Run the full compile**

Run: `./gradlew :sdk-lib:compileDebugKotlin`

Expected: BUILD SUCCESSFUL. If it fails, the error will name exactly which `VotingDbSession`/`VotingSdk` method is still unimplemented or which mapping extension is missing/mistyped — cross-check the failing member name against `VotingSdk.kt`'s interface list from Task 4 and this task's/Task 5's overrides list; every interface member must have exactly one matching `override` and every type it returns must have a `toPublic()`/`toInternal()` defined for it somewhere in `VotingSdkMappers.kt`.

- [ ] **Step 4: Commit**

```bash
git add sdk-lib/src/main/java/cash/z/ecc/android/sdk/internal/VotingSdkImpl.kt \
        sdk-lib/src/main/java/cash/z/ecc/android/sdk/internal/VotingSdkMappers.kt
git commit -m "$(cat <<'EOF'
Implement VotingSdkImpl part 2: PIR/proof/submission, tree sync,
vote commitment, share bookkeeping

Completes VotingDbSessionImpl — VotingSdk's full ~46-method surface
now compiles end to end against TypesafeVotingBackend.
EOF
)"
```

---

## Task 7: Unit tests for `VotingSdkImpl`

Mirrors `OrchardMigrationSdkImplTest`'s conventions (JUnit4, Mockito `mock()`/`when`, `sdk-lib/src/test`, no emulator needed). Covers representative behavior across every group of methods added in Tasks 5–6 — not a re-test of `TypesafeVotingBackend` itself (that's covered by `TypesafeVotingBackendImplTest`), but proof that `VotingSdkImpl`'s delegation and type-mapping are correct.

**Files:**
- Create: `sdk-lib/src/test/java/cash/z/ecc/android/sdk/internal/VotingSdkImplTest.kt`

**Interfaces:**
- Consumes: `VotingSdkImpl`, `VotingDbSessionImpl` (Task 5/6), `TypesafeVotingBackend`/`TypesafeVotingDb` (mocked).
- Produces: nothing new — this is the terminal task for the SDK-side plan.

- [ ] **Step 1: Write the failing tests**

```kotlin
package cash.z.ecc.android.sdk.internal

import cash.z.ecc.android.sdk.internal.model.voting.JniDelegationPhase
import cash.z.ecc.android.sdk.internal.model.voting.JniRoundPhase
import cash.z.ecc.android.sdk.internal.model.voting.JniRoundState
import cash.z.ecc.android.sdk.internal.model.voting.JniVoteRecord
import cash.z.ecc.android.sdk.model.voting.VotingRoundPhase
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VotingSdkImplTest {
    @Test
    fun isAvailable_returns_true_when_warmProvingCaches_succeeds() =
        runTest {
            val backend = mock(TypesafeVotingBackend::class.java)
            val sdk = VotingSdkImpl(backend)

            assertTrue(sdk.isAvailable())
        }

    @Test
    fun isAvailable_returns_false_on_UnsatisfiedLinkError() =
        runTest {
            val backend = mock(TypesafeVotingBackend::class.java)
            `when`(backend.warmProvingCaches()).thenThrow(UnsatisfiedLinkError("no symbol"))
            val sdk = VotingSdkImpl(backend)

            assertFalse(sdk.isAvailable())
        }

    @Test
    fun openDb_wraps_the_returned_TypesafeVotingDb() =
        runTest {
            val backend = mock(TypesafeVotingBackend::class.java)
            val votingDb = mock(TypesafeVotingDb::class.java)
            `when`(backend.openVotingDb("path", "wallet-1", 0)).thenReturn(votingDb)
            val sdk = VotingSdkImpl(backend)

            val session = sdk.openDb("path", "wallet-1", 0)

            session.close()
            org.mockito.Mockito.verify(votingDb).close()
        }

    @Test
    fun getRoundState_maps_phase_and_fields() =
        runTest {
            val backend = mock(TypesafeVotingBackend::class.java)
            val votingDb = mock(TypesafeVotingDb::class.java)
            `when`(backend.openVotingDb("path", "wallet-1", 0)).thenReturn(votingDb)
            `when`(votingDb.getRoundState("round-1")).thenReturn(
                JniRoundState(
                    roundId = "round-1",
                    phase = JniRoundPhase.VOTE_READY.value,
                    snapshotHeight = 100L,
                    hotkeyAddress = "addr",
                    delegatedWeight = 5L,
                    proofGenerated = true
                )
            )
            val session = VotingSdkImpl(backend).openDb("path", "wallet-1", 0)

            val state = session.getRoundState("round-1")

            assertEquals(VotingRoundPhase.VOTE_READY, state?.phase)
            assertEquals("round-1", state?.roundId)
            assertEquals(5L, state?.delegatedWeight)
        }

    @Test
    fun getRoundState_returns_null_when_backend_returns_null() =
        runTest {
            val backend = mock(TypesafeVotingBackend::class.java)
            val votingDb = mock(TypesafeVotingDb::class.java)
            `when`(backend.openVotingDb("path", "wallet-1", 0)).thenReturn(votingDb)
            `when`(votingDb.getRoundState("round-1")).thenReturn(null)
            val session = VotingSdkImpl(backend).openDb("path", "wallet-1", 0)

            assertEquals(null, session.getRoundState("round-1"))
        }

    @Test
    fun getVotes_maps_every_record() =
        runTest {
            val backend = mock(TypesafeVotingBackend::class.java)
            val votingDb = mock(TypesafeVotingDb::class.java)
            `when`(backend.openVotingDb("path", "wallet-1", 0)).thenReturn(votingDb)
            `when`(votingDb.getVotes("round-1")).thenReturn(
                listOf(
                    JniVoteRecord(proposalId = 1, bundleIndex = 0, choice = 1, submitted = true),
                    JniVoteRecord(proposalId = 2, bundleIndex = 1, choice = 0, submitted = false)
                )
            )
            val session = VotingSdkImpl(backend).openDb("path", "wallet-1", 0)

            val votes = session.getVotes("round-1")

            assertEquals(2, votes.size)
            assertTrue(votes[0].submitted)
            assertFalse(votes[1].submitted)
        }

    @Test
    fun delegationPhases_maps_every_bundle() =
        runTest {
            val backend = mock(TypesafeVotingBackend::class.java)
            val votingDb = mock(TypesafeVotingDb::class.java)
            `when`(backend.openVotingDb("path", "wallet-1", 0)).thenReturn(votingDb)
            `when`(votingDb.delegationPhases("round-1")).thenReturn(
                listOf(
                    JniDelegationPhase(bundleIndex = 0, phase = "proved"),
                    JniDelegationPhase(bundleIndex = 1, phase = "prepared")
                )
            )
            val session = VotingSdkImpl(backend).openDb("path", "wallet-1", 0)

            val phases = session.delegationPhases("round-1")

            assertEquals(2, phases.size)
            assertEquals("proved", phases[0].phase)
            assertEquals(1, phases[1].bundleIndex)
        }

    @Test
    fun resetVotingSessionState_forwards_to_backend() =
        runTest {
            val backend = mock(TypesafeVotingBackend::class.java)
            val votingDb = mock(TypesafeVotingDb::class.java)
            `when`(backend.openVotingDb("path", "wallet-1", 0)).thenReturn(votingDb)
            val session = VotingSdkImpl(backend).openDb("path", "wallet-1", 0)

            session.resetVotingSessionState("round-1")

            org.mockito.Mockito.verify(votingDb).resetVotingSessionState("round-1")
        }
}
```

- [ ] **Step 2: Verify `TypesafeVotingBackend.openVotingDb`'s exact signature matches the mocks above**

Cross-check against `TypesafeVotingBackend.kt`'s interface (Task 1's file): `suspend fun openVotingDb(dbPath: String, walletId: String, networkId: Int): TypesafeVotingDb` — the mock stubs above use `("path", "wallet-1", 0)` in that argument order; confirm the `mock(TypesafeVotingBackend::class.java)` Mockito call correctly proxies a Kotlin `interface` with `suspend fun` members (Mockito's inline mock maker, already in use by `OrchardMigrationSdkImplTest`, handles suspend functions — if this project's Mockito version does not, the test will fail with a "cannot mock" error rather than an assertion failure, which would indicate a project-wide Mockito config gap unrelated to this plan; check `sdk-lib/build.gradle.kts`'s `mockito-junit` version and `OrchardMigrationSdkImplTest`'s own use of `mock()` on a suspend-function interface as a working precedent before debugging further).

- [ ] **Step 3: Run the tests**

Run: `./gradlew :sdk-lib:testDebugUnitTest --tests "cash.z.ecc.android.sdk.internal.VotingSdkImplTest"`

Expected: all 8 tests PASS.

- [ ] **Step 4: Run the full `sdk-lib` unit test suite to confirm no regression**

Run: `./gradlew :sdk-lib:testDebugUnitTest`

Expected: BUILD SUCCESSFUL, including the pre-existing `OrchardMigrationSdkImplTest` and `VotingModelsTest` (Task 3).

- [ ] **Step 5: Commit**

```bash
git add sdk-lib/src/test/java/cash/z/ecc/android/sdk/internal/VotingSdkImplTest.kt
git commit -m "$(cat <<'EOF'
Add VotingSdkImpl unit tests

Covers isAvailable's UnsatisfiedLinkError handling, session
open/close delegation, and representative type-mapping correctness
(round state phase, vote records, delegation phases) across the
surface added in the previous two commits.
EOF
)"
```

---

## What this plan does not cover (by design)

- The app-side `feature-voting` module, `VotingContracts.kt`, and rewiring `VotingCryptoClient`'s call sites onto `VotingSdk` — a separate plan, written once this one is available to consume (either via a released SDK version or a local `SDK_INCLUDED_BUILD_PATH` override during development, per the design spec's phasing section).
- Dropping the `libs.zcash.sdk.backend` dependency from `feature-voting`/`ui-lib` (the enforcement step) — only possible after the app-side plan fully migrates off direct `VotingRustBackend` usage; premature removal here would break the still-unmigrated app.
- The mapping-parity test comparing old `VotingCryptoClient` output to the new split (SDK + app adapter) — belongs to the app-side plan, since it needs both sides to exist.
