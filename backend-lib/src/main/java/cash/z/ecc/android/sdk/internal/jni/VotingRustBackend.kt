package cash.z.ecc.android.sdk.internal.jni

import androidx.annotation.Keep
import androidx.annotation.VisibleForTesting
import cash.z.ecc.android.sdk.internal.SdkDispatchers
import cash.z.ecc.android.sdk.internal.model.voting.JniBundleSetupResult
import cash.z.ecc.android.sdk.internal.model.voting.JniCommitmentBundleRecord
import cash.z.ecc.android.sdk.internal.model.voting.JniCommittedVoteRecord
import cash.z.ecc.android.sdk.internal.model.voting.JniDelegationPhase
import cash.z.ecc.android.sdk.internal.model.voting.JniDelegationPirPrecomputeResult
import cash.z.ecc.android.sdk.internal.model.voting.JniDelegationProofResult
import cash.z.ecc.android.sdk.internal.model.voting.JniDelegationSubmissionResult
import cash.z.ecc.android.sdk.internal.model.voting.JniGovernancePczt
import cash.z.ecc.android.sdk.internal.model.voting.JniNoteInfo
import cash.z.ecc.android.sdk.internal.model.voting.JniRoundState
import cash.z.ecc.android.sdk.internal.model.voting.JniRoundSummary
import cash.z.ecc.android.sdk.internal.model.voting.JniShareDelegationRecord
import cash.z.ecc.android.sdk.internal.model.voting.JniSharePayload
import cash.z.ecc.android.sdk.internal.model.voting.JniVanWitness
import cash.z.ecc.android.sdk.internal.model.voting.JniVoteCommitResult
import cash.z.ecc.android.sdk.internal.model.voting.JniVoteCommitmentResult
import cash.z.ecc.android.sdk.internal.model.voting.JniVoteRecord
import cash.z.ecc.android.sdk.internal.model.voting.JniVotingHotkey
import cash.z.ecc.android.sdk.internal.model.voting.JniWitnessData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger

/**
 * Synchronous native proof progress callback.
 *
 * Native proof generation currently reports coarse progress from the proof call
 * thread before and after the spawned Halo2 proving worker. The JNI bridge
 * attaches whichever native thread invokes this callback, so callers must not
 * assume Android main-thread or coroutine-dispatcher affinity.
 *
 * This callback runs while the owning voting DB handle is locked by the in-flight
 * proof operation. Implementations must not call back into this VotingDb's methods.
 * Native code treats callback failures as best-effort progress reporting and
 * continues proof generation after logging the failure.
 */
@Keep
fun interface VotingProofProgressCallback {
    @Keep
    fun onProgress(progress: Double)
}

private const val PROOF_PROGRESS_REENTRY_ERROR =
    "This VotingDb's methods must not be called from its proof progress callback"

/**
 * Minimum entropy, in bytes, [VotingRustBackend.scheduledShareSubmitAt] sources from
 * [SecureRandom] for `scheduledShareSubmitAtNative`'s entropy parameter (which requires at
 * least 8 bytes).
 */
private const val SCHEDULED_SHARE_SUBMIT_AT_ENTROPY_BYTES = 32

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
    @Throws(RuntimeException::class)
    suspend fun computeShareNullifier(
        voteCommitment: ByteArray,
        shareIndex: Int,
        blind: ByteArray
    ): ByteArray =
        withContext(Dispatchers.IO) {
            computeShareNullifierNative(voteCommitment, shareIndex, blind)
        }

    @Throws(RuntimeException::class)
    suspend fun computeBundleSetup(notes: List<JniNoteInfo>): JniBundleSetupResult =
        withContext(Dispatchers.IO) {
            computeBundleSetupNative(notes.toTypedArray())
                ?: error("computeBundleSetup returned null")
        }

    @Throws(RuntimeException::class)
    suspend fun warmProvingCaches() =
        withContext(Dispatchers.IO) {
            warmProvingCachesNative()
        }

    @Throws(RuntimeException::class)
    suspend fun buildSharePayloads(
        commitment: JniVoteCommitmentResult,
        voteDecision: Int,
        numOptions: Int,
        vcTreePosition: Long,
        singleShareMode: Boolean
    ): Array<JniSharePayload> =
        withContext(Dispatchers.IO) {
            buildSharePayloadsNative(
                commitment,
                voteDecision,
                numOptions,
                vcTreePosition,
                singleShareMode
            ) ?: error("buildSharePayloads returned null")
        }

    /**
     * Computes when a delegated helper share should submit, honoring the ceremony's
     * last-moment buffer window.
     *
     * Sources its own entropy from [SecureRandom] so callers cannot forget to supply it.
     * Returns unix seconds; `0` means "submit immediately".
     */
    @Throws(RuntimeException::class)
    suspend fun scheduledShareSubmitAt(
        nowSeconds: Long,
        ceremonyStartSeconds: Long,
        voteEndTimeSeconds: Long,
        singleShare: Boolean
    ): Long =
        withContext(Dispatchers.IO) {
            scheduledShareSubmitAtNative(
                nowSeconds,
                ceremonyStartSeconds,
                voteEndTimeSeconds,
                singleShare,
                SecureRandom().generateSeed(SCHEDULED_SHARE_SUBMIT_AT_ENTROPY_BYTES)
            )
        }

    @Throws(RuntimeException::class)
    suspend fun extractOrchardFvkFromUfvk(
        ufvk: String,
        networkId: Int
    ): ByteArray =
        withContext(Dispatchers.IO) {
            extractOrchardFvkFromUfvkNative(ufvk, networkId)
                ?: error("extractOrchardFvkFromUfvk returned null")
        }

    /**
     * Derives the raw Orchard address for the voting hotkey.
     *
     * The hotkey account index is intentionally fixed by the Rust voting backend to match the
     * vote-signing path. Do not add an `accountIndex` parameter unless that path changes with it.
     */
    @Throws(RuntimeException::class)
    suspend fun deriveHotkeyRawAddress(
        hotkeySeed: ByteArray,
        networkId: Int
    ): ByteArray =
        withContext(Dispatchers.IO) {
            deriveHotkeyRawAddressNative(hotkeySeed, networkId)
                ?: error("deriveHotkeyRawAddress returned null")
        }

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    internal suspend fun deriveHotkeyRawAddressForAccountFixture(
        hotkeySeed: ByteArray,
        networkId: Int,
        accountIndex: Int
    ): ByteArray =
        withContext(Dispatchers.IO) {
            deriveHotkeyRawAddressForAccountFixtureNative(hotkeySeed, networkId, accountIndex)
                ?: error("deriveHotkeyRawAddressForAccountFixture returned null")
        }

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    internal suspend fun extractPcztOutputRecipientFixture(
        pcztBytes: ByteArray,
        actionIndex: Int
    ): ByteArray =
        withContext(Dispatchers.IO) {
            extractPcztOutputRecipientFixtureNative(pcztBytes, actionIndex)
                ?: error("extractPcztOutputRecipientFixture returned null")
        }

    @Throws(RuntimeException::class)
    suspend fun extractNcRoot(treeStateBytes: ByteArray): ByteArray =
        withContext(Dispatchers.IO) {
            extractNcRootNative(treeStateBytes)
                ?: error("extractNcRoot returned null")
        }

    @Throws(RuntimeException::class)
    suspend fun verifyWitness(witness: JniWitnessData): Boolean =
        withContext(Dispatchers.IO) {
            verifyWitnessNative(witness)
        }

    @Throws(RuntimeException::class)
    suspend fun getWalletNotes(
        walletDbPath: String,
        snapshotHeight: Long,
        networkId: Int,
        accountUuidBytes: ByteArray
    ): Array<JniNoteInfo> =
        withContext(SdkDispatchers.DATABASE_IO) {
            getWalletNotesNative(
                walletDbPath,
                snapshotHeight,
                networkId,
                accountUuidBytes
            ) ?: error("getWalletNotes returned null")
        }

    @Throws(RuntimeException::class)
    suspend fun extractPcztSighash(pcztBytes: ByteArray): ByteArray =
        withContext(Dispatchers.IO) {
            extractPcztSighashNative(pcztBytes)
                ?: error("extractPcztSighash returned null")
        }

    @Throws(RuntimeException::class)
    suspend fun extractSpendAuthSig(
        signedPcztBytes: ByteArray,
        actionIndex: Int
    ): ByteArray =
        withContext(Dispatchers.IO) {
            extractSpendAuthSigNative(signedPcztBytes, actionIndex)
                ?: error("extractSpendAuthSig returned null")
        }

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    internal suspend fun delegationProofResultFixtureForTesting(): JniDelegationProofResult =
        withContext(Dispatchers.IO) {
            delegationProofResultFixtureNative()
                ?: error("delegationProofResultFixture returned null")
        }

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    internal suspend fun noteInfoArrayFixtureForTesting(): Array<JniNoteInfo> =
        withContext(Dispatchers.IO) {
            noteInfoArrayFixtureNative()
                ?: error("noteInfoArrayFixture returned null")
        }

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    internal suspend fun witnessDataArrayFixtureForTesting(): Array<JniWitnessData> =
        withContext(Dispatchers.IO) {
            witnessDataArrayFixtureNative()
                ?: error("witnessDataArrayFixture returned null")
        }

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    internal suspend fun treeStateFixtureForTesting(): ByteArray =
        withContext(Dispatchers.IO) {
            treeStateFixtureNative()
                ?: error("treeStateFixture returned null")
        }

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    internal suspend fun nonEmptyTreeStateFixtureForTesting(): ByteArray =
        withContext(Dispatchers.IO) {
            nonEmptyTreeStateFixtureNative()
                ?: error("nonEmptyTreeStateFixture returned null")
        }

    suspend fun openVotingDb(dbPath: String, walletId: String, networkId: Int): VotingDb =
        withContext(SdkDispatchers.DATABASE_IO) {
            openVotingDbNative(dbPath, walletId, networkId).let { dbHandle ->
                check(dbHandle != 0L) {
                    "openVotingDb failed for dbPath=$dbPath"
                }
                VotingDb(dbHandle)
            }
        }

    @Suppress("TooManyFunctions", "LongParameterList")
    class VotingDb internal constructor(
        private var dbHandle: Long?
    ) {
        private val accessMutex = Mutex()
        private val proofProgressCallbackDepth = AtomicInteger(0)

        suspend fun close() {
            checkNotInProofProgressCallback()

            accessMutex.withLock {
                dbHandle?.let { handle ->
                    withContext(SdkDispatchers.DATABASE_IO) {
                        closeVotingDbNative(handle)
                    }
                    dbHandle = null
                }
            }
        }

        @Throws(RuntimeException::class)
        suspend fun initRound(
            roundId: String,
            snapshotHeight: Long,
            eaPK: ByteArray,
            ncRoot: ByteArray,
            nullifierIMTRoot: ByteArray,
            sessionJson: String?
        ) = withHandle { handle ->
            initRoundNative(
                handle,
                roundId,
                snapshotHeight,
                eaPK,
                ncRoot,
                nullifierIMTRoot,
                sessionJson
            )
        }

        @Throws(RuntimeException::class)
        suspend fun getRoundState(roundId: String): JniRoundState? =
            withHandle { handle -> getRoundStateNative(handle, roundId) }

        @Throws(RuntimeException::class)
        suspend fun delegationPhases(roundId: String): Array<JniDelegationPhase> =
            withHandle { handle -> delegationPhasesNative(handle, roundId) }

        @Throws(RuntimeException::class)
        suspend fun resetVotingSessionState(roundId: String) =
            withHandle { handle -> resetVotingSessionStateNative(handle, roundId) }

        @Throws(RuntimeException::class)
        suspend fun listRounds(): Array<JniRoundSummary> =
            withHandle { handle -> listRoundsNative(handle) }

        @Throws(RuntimeException::class)
        suspend fun getBundleCount(roundId: String): Int =
            withHandle { handle -> getBundleCountNative(handle, roundId) }

        @Throws(RuntimeException::class)
        suspend fun getVotes(roundId: String): Array<JniVoteRecord> =
            withHandle { handle -> getVotesNative(handle, roundId) }

        @Throws(RuntimeException::class)
        suspend fun clearRound(roundId: String) =
            withHandle { handle -> clearRoundNative(handle, roundId) }

        @Throws(RuntimeException::class)
        suspend fun deleteSkippedBundles(
            roundId: String,
            keepCount: Int
        ): Long =
            withHandle { handle -> deleteSkippedBundlesNative(handle, roundId, keepCount) }

        @Throws(RuntimeException::class)
        suspend fun setupBundles(
            roundId: String,
            notes: List<JniNoteInfo>
        ): JniBundleSetupResult =
            withHandle { handle ->
                setupBundlesNative(handle, roundId, notes.toTypedArray())
                    ?: error("setupBundles returned null for roundId=$roundId")
            }

        /**
         * Mints or reconstructs a voting hotkey.
         *
         * An empty [storedSecret] mints a fresh, app-owned random hotkey; a 64-byte
         * [storedSecret] (previously persisted from a prior call's returned
         * [JniVotingHotkey.storedSecret]) deterministically reconstructs the same hotkey. This
         * call is not scoped to a round.
         */
        @Throws(RuntimeException::class)
        suspend fun generateHotkey(storedSecret: ByteArray): JniVotingHotkey =
            withHandle { handle ->
                generateHotkeyNative(handle, storedSecret)
                    ?: error("generateHotkey returned null")
            }

        /**
         * Builds a governance PCZT for hardware-wallet flows.
         *
         * This explicit form trusts [fvkBytes] and [hotkeySecret] as caller-derived Keystone
         * input. It does not validate a wallet seed against [fvkBytes]. Software-wallet callers that
         * have the wallet seed should use [buildGovernancePcztFromSeed] to retain that invariant.
         */
        @Throws(RuntimeException::class)
        suspend fun buildGovernancePczt(
            roundId: String,
            bundleIndex: Int,
            fvkBytes: ByteArray,
            hotkeySecret: ByteArray,
            accountIndex: Int,
            notes: List<JniNoteInfo>,
            seedFingerprint: ByteArray,
            roundName: String
        ): JniGovernancePczt =
            withHandle { handle ->
                buildGovernancePcztNative(
                    handle,
                    roundId,
                    bundleIndex,
                    fvkBytes,
                    hotkeySecret,
                    accountIndex,
                    notes.toTypedArray(),
                    seedFingerprint,
                    roundName
                ) ?: error("buildGovernancePczt returned null")
            }

        /**
         * Builds a governance PCZT for software-wallet flows.
         *
         * This path derives the Orchard FVK from [walletSeed] and rejects calls where it does not
         * match [ufvk]. It also reconstructs the hotkey from [hotkeySecret] using the fixed
         * hotkey account index expected by the vote-signing path.
         */
        @Throws(RuntimeException::class)
        suspend fun buildGovernancePcztFromSeed(
            roundId: String,
            bundleIndex: Int,
            ufvk: String,
            networkId: Int,
            accountIndex: Int,
            notes: List<JniNoteInfo>,
            walletSeed: ByteArray,
            hotkeySecret: ByteArray,
            seedFingerprint: ByteArray,
            roundName: String
        ): JniGovernancePczt =
            withHandle { handle ->
                buildGovernancePcztFromSeedNative(
                    handle,
                    roundId,
                    bundleIndex,
                    ufvk,
                    networkId,
                    accountIndex,
                    notes.toTypedArray(),
                    walletSeed,
                    hotkeySecret,
                    seedFingerprint,
                    roundName
                ) ?: error("buildGovernancePcztFromSeed returned null")
            }

        @Throws(RuntimeException::class)
        suspend fun storeWitnesses(
            roundId: String,
            bundleIndex: Int,
            notes: List<JniNoteInfo>,
            witnesses: List<JniWitnessData>
        ) = withHandle { handle ->
            storeWitnessesNative(
                handle,
                roundId,
                bundleIndex,
                notes.toTypedArray(),
                witnesses.toTypedArray()
            )
        }

        @Throws(RuntimeException::class)
        suspend fun precomputeDelegationPir(
            roundId: String,
            bundleIndex: Int,
            pirServerUrl: String,
            pirDepth: Int,
            pirTier0Layers: Int,
            pirTier1Layers: Int,
            notes: List<JniNoteInfo>
        ): JniDelegationPirPrecomputeResult =
            withHandle { handle ->
                precomputeDelegationPirNative(
                    handle,
                    roundId,
                    bundleIndex,
                    pirServerUrl,
                    pirDepth,
                    pirTier0Layers,
                    pirTier1Layers,
                    notes.toTypedArray()
                ) ?: error("precomputeDelegationPir returned null")
            }

        @Throws(RuntimeException::class)
        suspend fun buildAndProveDelegation(
            roundId: String,
            bundleIndex: Int,
            pirServerUrl: String,
            pirDepth: Int,
            pirTier0Layers: Int,
            pirTier1Layers: Int,
            notes: List<JniNoteInfo>,
            fvkBytes: ByteArray,
            hotkeySecret: ByteArray,
            seedFingerprint: ByteArray,
            accountIndex: Int,
            roundName: String,
            proofProgress: VotingProofProgressCallback?
        ): JniDelegationProofResult =
            withHandle { handle ->
                buildAndProveDelegationNative(
                    handle,
                    roundId,
                    bundleIndex,
                    pirServerUrl,
                    pirDepth,
                    pirTier0Layers,
                    pirTier1Layers,
                    notes.toTypedArray(),
                    fvkBytes,
                    hotkeySecret,
                    seedFingerprint,
                    accountIndex,
                    roundName,
                    proofProgress?.withVotingDbReentryGuard()
                ) ?: error("buildAndProveDelegation returned null")
            }

        /**
         * Reconstructs the delegation signing keys from wallet state at [walletDbPath] and returns
         * a spend-authorization-signed delegation submission.
         *
         * This mirrors the same `DelegationKeys` construction [buildGovernancePczt] used at PCZT
         * setup time, so [hotkeySecret] and [roundName] must match those originally used to build
         * this bundle's governance PCZT.
         */
        @Throws(RuntimeException::class)
        suspend fun getDelegationSubmission(
            roundId: String,
            bundleIndex: Int,
            walletDbPath: String,
            accountUuid: String,
            hotkeySecret: ByteArray,
            roundName: String,
            senderSeed: ByteArray
        ): JniDelegationSubmissionResult =
            withHandle { handle ->
                getDelegationSubmissionNative(
                    handle,
                    roundId,
                    bundleIndex,
                    walletDbPath,
                    accountUuid,
                    hotkeySecret,
                    roundName,
                    senderSeed
                ) ?: error("getDelegationSubmission returned null")
            }

        @Throws(RuntimeException::class)
        suspend fun getDelegationSubmissionWithKeystoneSig(
            roundId: String,
            bundleIndex: Int,
            keystoneSig: ByteArray,
            keystoneSighash: ByteArray
        ): JniDelegationSubmissionResult =
            withHandle { handle ->
                getDelegationSubmissionWithKeystoneSigNative(
                    handle,
                    roundId,
                    bundleIndex,
                    keystoneSig,
                    keystoneSighash
                ) ?: error("getDelegationSubmissionWithKeystoneSig returned null")
            }

        @Throws(RuntimeException::class)
        suspend fun storeTreeState(
            roundId: String,
            treeStateBytes: ByteArray
        ) = withHandle { handle ->
            storeTreeStateNative(handle, roundId, treeStateBytes)
        }

        @Throws(RuntimeException::class)
        suspend fun generateNoteWitnesses(
            roundId: String,
            bundleIndex: Int,
            walletDbPath: String,
            networkId: Int,
            notes: List<JniNoteInfo>
        ): Array<JniWitnessData> =
            withHandle { handle ->
                generateNoteWitnessesNative(
                    handle,
                    roundId,
                    bundleIndex,
                    walletDbPath,
                    networkId,
                    notes.toTypedArray()
                ) ?: error("generateNoteWitnesses returned null")
            }

        @Throws(RuntimeException::class)
        suspend fun syncVoteTree(roundId: String, nodeUrl: String): Long =
            withHandle { handle ->
                syncVoteTreeNative(handle, roundId, nodeUrl).also { height ->
                    check(height >= 0) {
                        "syncVoteTree failed for roundId=$roundId"
                    }
                }
            }

        @Throws(RuntimeException::class)
        suspend fun resetTreeClient(roundId: String) =
            withHandle { handle ->
                check(resetTreeClientNative(handle, roundId)) {
                    "resetTreeClient failed for roundId=$roundId"
                }
            }

        @Throws(RuntimeException::class)
        suspend fun storeVanPosition(
            roundId: String,
            bundleIndex: Int,
            position: Long
        ) = withHandle { handle ->
            check(storeVanPositionNative(handle, roundId, bundleIndex, position)) {
                "storeVanPosition failed for roundId=$roundId bundleIndex=$bundleIndex"
            }
        }

        @Throws(RuntimeException::class)
        suspend fun generateVanWitness(
            roundId: String,
            bundleIndex: Int,
            anchorHeight: Long
        ): JniVanWitness =
            withHandle { handle ->
                generateVanWitnessNative(handle, roundId, bundleIndex, anchorHeight)
                    ?: error("generateVanWitness returned null")
            }

        @Throws(RuntimeException::class)
        suspend fun buildVoteCommitment(
            roundId: String,
            bundleIndex: Int,
            hotkeySecret: ByteArray,
            proposalId: Int,
            choice: Int,
            numOptions: Int,
            witness: JniVanWitness,
            singleShare: Boolean,
            proofProgress: VotingProofProgressCallback?
        ): JniVoteCommitResult =
            withHandle { handle ->
                buildVoteCommitmentNative(
                    handle,
                    roundId,
                    bundleIndex,
                    hotkeySecret,
                    proposalId,
                    choice,
                    numOptions,
                    witness,
                    singleShare,
                    proofProgress?.withVotingDbReentryGuard()
                ) ?: error("buildVoteCommitment returned null")
            }

        @Throws(RuntimeException::class)
        suspend fun storeDelegationTxHash(
            roundId: String,
            bundleIndex: Int,
            txHash: String
        ) = withHandle { handle ->
            check(storeDelegationTxHashNative(handle, roundId, bundleIndex, txHash)) {
                "storeDelegationTxHash failed for roundId=$roundId bundleIndex=$bundleIndex"
            }
        }

        @Throws(RuntimeException::class)
        suspend fun getDelegationTxHash(
            roundId: String,
            bundleIndex: Int
        ): String? =
            withHandle { handle ->
                getDelegationTxHashNative(handle, roundId, bundleIndex)
            }

        /**
         * Records [txHash] and marks this vote as submitted in one atomic step.
         */
        @Throws(RuntimeException::class)
        suspend fun storeVoteTxHash(
            roundId: String,
            bundleIndex: Int,
            proposalId: Int,
            txHash: String
        ) = withHandle { handle ->
            check(storeVoteTxHashNative(handle, roundId, bundleIndex, proposalId, txHash)) {
                "storeVoteTxHash failed for roundId=$roundId bundleIndex=$bundleIndex proposalId=$proposalId"
            }
        }

        /**
         * Idempotently re-marks this vote as submitted using the tx hash [storeVoteTxHash] already
         * recorded. Fails if no tx hash has been recorded yet for this vote.
         */
        @Throws(RuntimeException::class)
        suspend fun markVoteSubmitted(
            roundId: String,
            bundleIndex: Int,
            proposalId: Int
        ) = withHandle { handle ->
            check(markVoteSubmittedNative(handle, roundId, bundleIndex, proposalId)) {
                "markVoteSubmitted failed for roundId=$roundId bundleIndex=$bundleIndex proposalId=$proposalId"
            }
        }

        @Throws(RuntimeException::class)
        suspend fun getVoteTxHash(
            roundId: String,
            bundleIndex: Int,
            proposalId: Int
        ): String? =
            withHandle { handle ->
                getVoteTxHashNative(handle, roundId, bundleIndex, proposalId)
            }

        @Throws(RuntimeException::class)
        suspend fun getCommitmentBundle(
            roundId: String,
            bundleIndex: Int,
            proposalId: Int
        ): JniCommitmentBundleRecord? =
            withHandle { handle ->
                getCommitmentBundleNative(handle, roundId, bundleIndex, proposalId)
            }

        /**
         * Records the confirmed vote-commitment-tree position for an already-committed vote, once
         * its cast-vote transaction has been mined.
         */
        @Throws(RuntimeException::class)
        suspend fun recordVcPosition(
            roundId: String,
            bundleIndex: Int,
            proposalId: Int,
            vcTreePosition: Long
        ) = withHandle { handle ->
            check(
                recordVcPositionNative(
                    handle,
                    roundId,
                    bundleIndex,
                    proposalId,
                    vcTreePosition
                )
            ) {
                "recordVcPosition failed for roundId=$roundId bundleIndex=$bundleIndex proposalId=$proposalId"
            }
        }

        /**
         * Recovers the signed `vote::commit` result for an already-committed vote, together with
         * its confirmed vote-commitment-tree position recorded by [recordVcPosition].
         */
        @Throws(RuntimeException::class)
        suspend fun recoverCommittedVote(
            roundId: String,
            bundleIndex: Int,
            proposalId: Int
        ): JniCommittedVoteRecord =
            withHandle { handle ->
                recoverCommittedVoteNative(handle, roundId, bundleIndex, proposalId)
                    ?: error("recoverCommittedVote returned null")
            }

        @Throws(RuntimeException::class)
        suspend fun clearRecoveryState(roundId: String) =
            withHandle { handle ->
                check(clearRecoveryStateNative(handle, roundId)) {
                    "clearRecoveryState failed for roundId=$roundId"
                }
            }

        /**
         * Records that share [shareIndex] was sent to [sentToUrls].
         *
         * The native side derives and persists the authoritative nullifier from the vote's own
         * recovery state; [nullifier] is only shape-validated when non-empty and is never itself
         * stored. An empty [nullifier] is the normal case for callers that do not have it yet.
         */
        @Throws(RuntimeException::class)
        suspend fun recordShareDelegation(
            roundId: String,
            bundleIndex: Int,
            proposalId: Int,
            shareIndex: Int,
            sentToUrls: List<String>,
            nullifier: ByteArray,
            submitAt: Long
        ) = withHandle { handle ->
            check(
                recordShareDelegationNative(
                    handle,
                    roundId,
                    bundleIndex,
                    proposalId,
                    shareIndex,
                    sentToUrls.toTypedArray(),
                    nullifier,
                    submitAt
                )
            ) {
                "recordShareDelegation failed for roundId=$roundId " +
                    "bundleIndex=$bundleIndex proposalId=$proposalId shareIndex=$shareIndex"
            }
        }

        @Throws(RuntimeException::class)
        suspend fun getShareDelegations(roundId: String): Array<JniShareDelegationRecord> =
            withHandle { handle ->
                getShareDelegationsNative(handle, roundId)
                    ?: error("getShareDelegations returned null")
            }

        @Throws(RuntimeException::class)
        suspend fun getUnconfirmedDelegations(roundId: String): Array<JniShareDelegationRecord> =
            withHandle { handle ->
                getUnconfirmedDelegationsNative(handle, roundId)
                    ?: error("getUnconfirmedDelegations returned null")
            }

        @Throws(RuntimeException::class)
        suspend fun markShareConfirmed(
            roundId: String,
            bundleIndex: Int,
            proposalId: Int,
            shareIndex: Int
        ) = withHandle { handle ->
            check(markShareConfirmedNative(handle, roundId, bundleIndex, proposalId, shareIndex)) {
                "markShareConfirmed failed for roundId=$roundId " +
                    "bundleIndex=$bundleIndex proposalId=$proposalId shareIndex=$shareIndex"
            }
        }

        /**
         * Appends [newUrls] to the stored sent-server list for this share, ignoring duplicates.
         */
        @Throws(RuntimeException::class)
        suspend fun addSentServers(
            roundId: String,
            bundleIndex: Int,
            proposalId: Int,
            shareIndex: Int,
            newUrls: List<String>
        ) = withHandle { handle ->
            check(
                addSentServersNative(
                    handle,
                    roundId,
                    bundleIndex,
                    proposalId,
                    shareIndex,
                    newUrls.toTypedArray()
                )
            ) {
                "addSentServers failed for roundId=$roundId " +
                    "bundleIndex=$bundleIndex proposalId=$proposalId shareIndex=$shareIndex"
            }
        }

        @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
        internal suspend fun storeDelegationProofFixtureForTesting(
            roundId: String,
            bundleIndex: Int,
            proof: ByteArray
        ) = withHandle { handle ->
            storeDelegationProofFixtureNative(handle, roundId, bundleIndex, proof)
        }

        @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
        internal suspend fun storeVoteFixtureForTesting(
            roundId: String,
            bundleIndex: Int,
            proposalId: Int,
            choice: Int
        ) = withHandle { handle ->
            storeVoteFixtureNative(handle, roundId, bundleIndex, proposalId, choice)
        }

        private suspend fun <T> withHandle(block: (Long) -> T): T {
            checkNotInProofProgressCallback()

            return accessMutex.withLock {
                val handle =
                    checkNotNull(dbHandle) {
                        "Voting DB handle is closed"
                    }
                withContext(SdkDispatchers.DATABASE_IO) {
                    block(handle)
                }
            }
        }

        private fun checkNotInProofProgressCallback() {
            check(proofProgressCallbackDepth.get() == 0) {
                PROOF_PROGRESS_REENTRY_ERROR
            }
        }

        private fun VotingProofProgressCallback.withVotingDbReentryGuard() =
            VotingProofProgressCallback { progress ->
                proofProgressCallbackDepth.incrementAndGet()
                try {
                    onProgress(progress)
                } finally {
                    proofProgressCallbackDepth.decrementAndGet()
                }
            }
    }

    companion object {
        // The factory is part of the deprecated surface; suppressing here only lets the
        // class construct itself, and does not reopen it to callers.
        @Suppress("DEPRECATION_ERROR")
        suspend fun new(): VotingRustBackend {
            RustBackend.loadLibrary()

            return VotingRustBackend()
        }

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun computeShareNullifierNative(
            voteCommitment: ByteArray,
            shareIndex: Int,
            blind: ByteArray
        ): ByteArray

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun warmProvingCachesNative()

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun scheduledShareSubmitAtNative(
            nowSeconds: Long,
            ceremonyStartSeconds: Long,
            voteEndTimeSeconds: Long,
            singleShare: Boolean,
            entropy: ByteArray
        ): Long

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun buildSharePayloadsNative(
            commitment: JniVoteCommitmentResult,
            voteDecision: Int,
            numOptions: Int,
            vcTreePosition: Long,
            singleShareMode: Boolean
        ): Array<JniSharePayload>?

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun extractOrchardFvkFromUfvkNative(
            ufvk: String,
            networkId: Int
        ): ByteArray?

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun deriveHotkeyRawAddressNative(
            hotkeySeed: ByteArray,
            networkId: Int
        ): ByteArray?

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun deriveHotkeyRawAddressForAccountFixtureNative(
            hotkeySeed: ByteArray,
            networkId: Int,
            accountIndex: Int
        ): ByteArray?

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun extractPcztOutputRecipientFixtureNative(
            pcztBytes: ByteArray,
            actionIndex: Int
        ): ByteArray?

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun extractNcRootNative(treeStateBytes: ByteArray): ByteArray?

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun verifyWitnessNative(witness: JniWitnessData): Boolean

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun getWalletNotesNative(
            walletDbPath: String,
            snapshotHeight: Long,
            networkId: Int,
            accountUuidBytes: ByteArray
        ): Array<JniNoteInfo>?

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun openVotingDbNative(dbPath: String, walletId: String, networkId: Int): Long

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun closeVotingDbNative(dbHandle: Long)

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun initRoundNative(
            dbHandle: Long,
            roundId: String,
            snapshotHeight: Long,
            eaPK: ByteArray,
            ncRoot: ByteArray,
            nullifierIMTRoot: ByteArray,
            sessionJson: String?
        )

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun getRoundStateNative(dbHandle: Long, roundId: String): JniRoundState?

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun delegationPhasesNative(dbHandle: Long, roundId: String): Array<JniDelegationPhase>

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun resetVotingSessionStateNative(dbHandle: Long, roundId: String)

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun listRoundsNative(dbHandle: Long): Array<JniRoundSummary>

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun getBundleCountNative(dbHandle: Long, roundId: String): Int

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun getVotesNative(dbHandle: Long, roundId: String): Array<JniVoteRecord>

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun clearRoundNative(dbHandle: Long, roundId: String)

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun deleteSkippedBundlesNative(
            dbHandle: Long,
            roundId: String,
            keepCount: Int
        ): Long

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun computeBundleSetupNative(notes: Array<JniNoteInfo>): JniBundleSetupResult?

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun setupBundlesNative(
            dbHandle: Long,
            roundId: String,
            notes: Array<JniNoteInfo>
        ): JniBundleSetupResult?

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun generateHotkeyNative(
            dbHandle: Long,
            storedSecret: ByteArray
        ): JniVotingHotkey?

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun buildGovernancePcztNative(
            dbHandle: Long,
            roundId: String,
            bundleIndex: Int,
            fvkBytes: ByteArray,
            hotkeySecret: ByteArray,
            accountIndex: Int,
            notes: Array<JniNoteInfo>,
            seedFingerprint: ByteArray,
            roundName: String
        ): JniGovernancePczt?

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun buildGovernancePcztFromSeedNative(
            dbHandle: Long,
            roundId: String,
            bundleIndex: Int,
            ufvk: String,
            networkId: Int,
            accountIndex: Int,
            notes: Array<JniNoteInfo>,
            walletSeed: ByteArray,
            hotkeySecret: ByteArray,
            seedFingerprint: ByteArray,
            roundName: String
        ): JniGovernancePczt?

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun extractPcztSighashNative(pcztBytes: ByteArray): ByteArray?

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun extractSpendAuthSigNative(
            signedPcztBytes: ByteArray,
            actionIndex: Int
        ): ByteArray?

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun delegationProofResultFixtureNative(): JniDelegationProofResult?

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun noteInfoArrayFixtureNative(): Array<JniNoteInfo>?

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun witnessDataArrayFixtureNative(): Array<JniWitnessData>?

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun treeStateFixtureNative(): ByteArray?

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun nonEmptyTreeStateFixtureNative(): ByteArray?

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun storeWitnessesNative(
            dbHandle: Long,
            roundId: String,
            bundleIndex: Int,
            notes: Array<JniNoteInfo>,
            witnesses: Array<JniWitnessData>
        )

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun precomputeDelegationPirNative(
            dbHandle: Long,
            roundId: String,
            bundleIndex: Int,
            pirServerUrl: String,
            pirDepth: Int,
            pirTier0Layers: Int,
            pirTier1Layers: Int,
            notes: Array<JniNoteInfo>
        ): JniDelegationPirPrecomputeResult?

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun buildAndProveDelegationNative(
            dbHandle: Long,
            roundId: String,
            bundleIndex: Int,
            pirServerUrl: String,
            pirDepth: Int,
            pirTier0Layers: Int,
            pirTier1Layers: Int,
            notes: Array<JniNoteInfo>,
            fvkBytes: ByteArray,
            hotkeySecret: ByteArray,
            seedFingerprint: ByteArray,
            accountIndex: Int,
            roundName: String,
            proofProgress: VotingProofProgressCallback?
        ): JniDelegationProofResult?

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun getDelegationSubmissionNative(
            dbHandle: Long,
            roundId: String,
            bundleIndex: Int,
            walletDbPath: String,
            accountUuid: String,
            hotkeySecret: ByteArray,
            roundName: String,
            senderSeed: ByteArray
        ): JniDelegationSubmissionResult?

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun getDelegationSubmissionWithKeystoneSigNative(
            dbHandle: Long,
            roundId: String,
            bundleIndex: Int,
            keystoneSig: ByteArray,
            keystoneSighash: ByteArray
        ): JniDelegationSubmissionResult?

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun storeTreeStateNative(
            dbHandle: Long,
            roundId: String,
            treeStateBytes: ByteArray
        )

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun generateNoteWitnessesNative(
            dbHandle: Long,
            roundId: String,
            bundleIndex: Int,
            walletDbPath: String,
            networkId: Int,
            notes: Array<JniNoteInfo>
        ): Array<JniWitnessData>?

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun syncVoteTreeNative(
            dbHandle: Long,
            roundId: String,
            nodeUrl: String
        ): Long

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun resetTreeClientNative(
            dbHandle: Long,
            roundId: String
        ): Boolean

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun storeVanPositionNative(
            dbHandle: Long,
            roundId: String,
            bundleIndex: Int,
            position: Long
        ): Boolean

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun generateVanWitnessNative(
            dbHandle: Long,
            roundId: String,
            bundleIndex: Int,
            anchorHeight: Long
        ): JniVanWitness?

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun buildVoteCommitmentNative(
            dbHandle: Long,
            roundId: String,
            bundleIndex: Int,
            hotkeySecret: ByteArray,
            proposalId: Int,
            choice: Int,
            numOptions: Int,
            witness: JniVanWitness,
            singleShare: Boolean,
            proofProgress: VotingProofProgressCallback?
        ): JniVoteCommitResult?

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun storeDelegationTxHashNative(
            dbHandle: Long,
            roundId: String,
            bundleIndex: Int,
            txHash: String
        ): Boolean

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun getDelegationTxHashNative(
            dbHandle: Long,
            roundId: String,
            bundleIndex: Int
        ): String?

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun storeVoteTxHashNative(
            dbHandle: Long,
            roundId: String,
            bundleIndex: Int,
            proposalId: Int,
            txHash: String
        ): Boolean

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun markVoteSubmittedNative(
            dbHandle: Long,
            roundId: String,
            bundleIndex: Int,
            proposalId: Int
        ): Boolean

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun getVoteTxHashNative(
            dbHandle: Long,
            roundId: String,
            bundleIndex: Int,
            proposalId: Int
        ): String?

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun getCommitmentBundleNative(
            dbHandle: Long,
            roundId: String,
            bundleIndex: Int,
            proposalId: Int
        ): JniCommitmentBundleRecord?

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun recordVcPositionNative(
            dbHandle: Long,
            roundId: String,
            bundleIndex: Int,
            proposalId: Int,
            vcTreePosition: Long
        ): Boolean

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun recoverCommittedVoteNative(
            dbHandle: Long,
            roundId: String,
            bundleIndex: Int,
            proposalId: Int
        ): JniCommittedVoteRecord?

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun clearRecoveryStateNative(dbHandle: Long, roundId: String): Boolean

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun recordShareDelegationNative(
            dbHandle: Long,
            roundId: String,
            bundleIndex: Int,
            proposalId: Int,
            shareIndex: Int,
            sentToUrls: Array<String>,
            nullifier: ByteArray,
            submitAt: Long
        ): Boolean

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun getShareDelegationsNative(
            dbHandle: Long,
            roundId: String
        ): Array<JniShareDelegationRecord>?

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun getUnconfirmedDelegationsNative(
            dbHandle: Long,
            roundId: String
        ): Array<JniShareDelegationRecord>?

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun markShareConfirmedNative(
            dbHandle: Long,
            roundId: String,
            bundleIndex: Int,
            proposalId: Int,
            shareIndex: Int
        ): Boolean

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun addSentServersNative(
            dbHandle: Long,
            roundId: String,
            bundleIndex: Int,
            proposalId: Int,
            shareIndex: Int,
            newUrls: Array<String>
        ): Boolean

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun storeDelegationProofFixtureNative(
            dbHandle: Long,
            roundId: String,
            bundleIndex: Int,
            proof: ByteArray
        )

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun storeVoteFixtureNative(
            dbHandle: Long,
            roundId: String,
            bundleIndex: Int,
            proposalId: Int,
            choice: Int
        )
    }
}
