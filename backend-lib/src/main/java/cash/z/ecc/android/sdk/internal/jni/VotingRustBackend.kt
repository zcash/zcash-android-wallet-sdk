package cash.z.ecc.android.sdk.internal.jni

import androidx.annotation.Keep
import androidx.annotation.VisibleForTesting
import cash.z.ecc.android.sdk.internal.SdkDispatchers
import cash.z.ecc.android.sdk.internal.model.voting.JniBundleSetupResult
import cash.z.ecc.android.sdk.internal.model.voting.JniCommitmentBundleRecord
import cash.z.ecc.android.sdk.internal.model.voting.JniDelegationPirPrecomputeResult
import cash.z.ecc.android.sdk.internal.model.voting.JniDelegationProofResult
import cash.z.ecc.android.sdk.internal.model.voting.JniDelegationSubmissionResult
import cash.z.ecc.android.sdk.internal.model.voting.JniGovernancePczt
import cash.z.ecc.android.sdk.internal.model.voting.JniNoteInfo
import cash.z.ecc.android.sdk.internal.model.voting.JniRoundState
import cash.z.ecc.android.sdk.internal.model.voting.JniRoundSummary
import cash.z.ecc.android.sdk.internal.model.voting.JniShareDelegationRecord
import cash.z.ecc.android.sdk.internal.model.voting.JniVanWitness
import cash.z.ecc.android.sdk.internal.model.voting.JniVoteCommitResult
import cash.z.ecc.android.sdk.internal.model.voting.JniVoteRecord
import cash.z.ecc.android.sdk.internal.model.voting.JniVoteSubmission
import cash.z.ecc.android.sdk.internal.model.voting.JniVotingHotkey
import cash.z.ecc.android.sdk.internal.model.voting.JniWitnessData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
 * Bindings to the native shielded-voting backend, built on `zcash_voting` 2.0.
 *
 * Every `external fun` in the companion object must correspond, by name and by
 * parameter list, to a `Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_*`
 * entry point in `backend-lib/src/main/rust/voting*`. A mismatch is invisible to the
 * Kotlin compiler and surfaces only as an [UnsatisfiedLinkError] (or a corrupted call)
 * at the moment the method is invoked, so both sides have to be changed together and
 * cross-checked against the exported symbol list.
 */
@Keep
@Suppress("TooManyFunctions", "LongParameterList")
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
    suspend fun extractOrchardFvkFromUfvk(
        ufvk: String,
        networkId: Int
    ): ByteArray =
        withContext(Dispatchers.IO) {
            extractOrchardFvkFromUfvkNative(ufvk, networkId)
                ?: error("extractOrchardFvkFromUfvk returned null")
        }

    /**
     * Recomputes the raw Orchard address of the voting hotkey owning [hotkeyStoredSecret].
     *
     * This is the only way to recover a hotkey's address: the round state does not report it
     * (see [JniRoundState]), and it cannot be derived from the wallet seed. It requires the
     * secret the application persisted when the hotkey was generated — see [JniVotingHotkey].
     *
     * The hotkey address index is fixed by `zcash_voting` to match the vote-signing path and is
     * not caller-selectable.
     */
    @Throws(RuntimeException::class)
    suspend fun deriveHotkeyRawAddress(
        hotkeyStoredSecret: ByteArray,
        networkId: Int
    ): ByteArray =
        withContext(Dispatchers.IO) {
            deriveHotkeyRawAddressNative(hotkeyStoredSecret, networkId)
                ?: error("deriveHotkeyRawAddress returned null")
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

    suspend fun openVotingDb(dbPath: String, walletId: String): VotingDb =
        withContext(SdkDispatchers.DATABASE_IO) {
            openVotingDbNative(dbPath, walletId).let { dbHandle ->
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

        /**
         * Creates a round and binds it to [networkId].
         *
         * [roundId] must be 64 lowercase hex characters encoding a canonical Pallas field
         * element; the native side rejects anything else.
         */
        @Throws(RuntimeException::class)
        suspend fun initRound(
            roundId: String,
            snapshotHeight: Long,
            eaPK: ByteArray,
            ncRoot: ByteArray,
            nullifierIMTRoot: ByteArray,
            networkId: Int,
            sessionJson: String?
        ) = withHandle { handle ->
            initRoundNative(
                handle,
                roundId,
                snapshotHeight,
                eaPK,
                ncRoot,
                nullifierIMTRoot,
                networkId,
                sessionJson
            )
        }

        @Throws(RuntimeException::class)
        suspend fun getRoundState(roundId: String): JniRoundState? =
            withHandle { handle -> getRoundStateNative(handle, roundId) }

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

        /**
         * Chunks [notes] into voting bundles for the round.
         *
         * Rejects an empty note set rather than returning a zero-bundle result.
         */
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
         * Generates a fresh voting hotkey for the round.
         *
         * The hotkey is app-owned random material, not a derivation of the wallet seed, so
         * every call returns a different one. **The caller must persist the returned
         * [JniVotingHotkey.storedSecret] in platform secure storage before delegating to it**;
         * it cannot be recovered from the seed phrase, and losing it forfeits the voting power
         * delegated to the hotkey. See [JniVotingHotkey].
         */
        @Throws(RuntimeException::class)
        suspend fun generateHotkey(
            roundId: String,
            networkId: Int
        ): JniVotingHotkey =
            withHandle { handle ->
                generateHotkeyNative(handle, roundId, networkId)
                    ?: error("generateHotkey returned null for roundId=$roundId")
            }

        /**
         * Builds a governance PCZT for hardware-wallet flows.
         *
         * This explicit form trusts [fvkBytes] as caller-derived Keystone input; it does not
         * validate a wallet seed against it. Software-wallet callers that have the wallet seed
         * should use [buildGovernancePcztFromSeed] to retain that invariant.
         *
         * [hotkeyStoredSecret] is the persisted secret from [generateHotkey]. The raw hotkey
         * address alone is no longer sufficient: `zcash_voting` reconstructs the whole hotkey
         * from the secret and validates it against the stored round.
         */
        @Throws(RuntimeException::class)
        suspend fun buildGovernancePczt(
            roundId: String,
            bundleIndex: Int,
            fvkBytes: ByteArray,
            hotkeyStoredSecret: ByteArray,
            networkId: Int,
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
                    hotkeyStoredSecret,
                    networkId,
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
         * match [ufvk]. [hotkeyStoredSecret] is the persisted secret from [generateHotkey]; the
         * hotkey is app-owned random material and is not derived from [walletSeed].
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
            hotkeyStoredSecret: ByteArray,
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
                    hotkeyStoredSecret,
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
            networkId: Int,
            notes: List<JniNoteInfo>
        ): JniDelegationPirPrecomputeResult =
            withHandle { handle ->
                precomputeDelegationPirNative(
                    handle,
                    roundId,
                    bundleIndex,
                    pirServerUrl,
                    networkId,
                    notes.toTypedArray()
                ) ?: error("precomputeDelegationPir returned null")
            }

        /**
         * Proves the delegation for a bundle.
         *
         * The delegation keys are now assembled natively from [fvkBytes], [hotkeyStoredSecret],
         * [seedFingerprint], [accountIndex] and [roundName], and validated against the stored
         * round. A raw hotkey address is not accepted: `zcash_voting` exposes no public
         * constructor that takes one.
         *
         * [hotkeyStoredSecret] is the persisted secret from [generateHotkey]. See
         * [JniVotingHotkey] for why the application, not the SDK, owns that secret.
         */
        @Throws(RuntimeException::class)
        suspend fun buildAndProveDelegation(
            roundId: String,
            bundleIndex: Int,
            pirServerUrl: String,
            networkId: Int,
            notes: List<JniNoteInfo>,
            fvkBytes: ByteArray,
            hotkeyStoredSecret: ByteArray,
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
                    networkId,
                    notes.toTypedArray(),
                    fvkBytes,
                    hotkeyStoredSecret,
                    seedFingerprint,
                    accountIndex,
                    roundName,
                    proofProgress?.withVotingDbReentryGuard()
                ) ?: error("buildAndProveDelegation returned null")
            }

        /**
         * Assembles the delegation submission from a caller-supplied SpendAuth signature.
         *
         * This is the single path for both software and hardware signers: `zcash_voting` no
         * longer derives account keys or signs on the caller's behalf, so every signer hands
         * back a 64-byte [spendAuthSig] over the 32-byte ZIP-244 [sighash].
         */
        @Throws(RuntimeException::class)
        suspend fun getDelegationSubmission(
            roundId: String,
            bundleIndex: Int,
            spendAuthSig: ByteArray,
            sighash: ByteArray
        ): JniDelegationSubmissionResult =
            withHandle { handle ->
                getDelegationSubmissionNative(
                    handle,
                    roundId,
                    bundleIndex,
                    spendAuthSig,
                    sighash
                ) ?: error("getDelegationSubmission returned null")
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

        /**
         * Builds, signs and stores the vote commitment for a proposal in one call.
         *
         * This replaces the former build/sign/build-payloads sequence: the commitment builder
         * and the cast-vote signer are no longer public in `zcash_voting`, and the helper-share
         * payloads now come back with the commitment rather than from a follow-up call.
         *
         * [hotkeyStoredSecret] is the persisted secret from [generateHotkey]. The network the
         * vote is signed for is taken from the hotkey, so [networkId] must match the network the
         * round was initialized with; a mismatch surfaces only as a native exception.
         */
        @Throws(RuntimeException::class)
        suspend fun commitVote(
            roundId: String,
            bundleIndex: Int,
            hotkeyStoredSecret: ByteArray,
            networkId: Int,
            proposalId: Int,
            choice: Int,
            numOptions: Int,
            vcTreePosition: Long,
            witness: JniVanWitness,
            singleShare: Boolean,
            proofProgress: VotingProofProgressCallback?
        ): JniVoteCommitResult =
            withHandle { handle ->
                commitVoteNative(
                    handle,
                    roundId,
                    bundleIndex,
                    hotkeyStoredSecret,
                    networkId,
                    proposalId,
                    choice,
                    numOptions,
                    vcTreePosition,
                    witness,
                    singleShare,
                    proofProgress?.withVotingDbReentryGuard()
                ) ?: error("commitVote returned null")
            }

        /**
         * Returns the chain-ready fields needed to resend a cast-vote transaction.
         *
         * Use this before the transaction confirms. Once the vote commitment tree position has
         * been recorded via [recordVcPosition], use [getCommitmentBundle] instead, which also
         * yields fresh helper-share payloads.
         */
        @Throws(RuntimeException::class)
        suspend fun voteSubmission(
            roundId: String,
            bundleIndex: Int,
            proposalId: Int
        ): JniVoteSubmission =
            withHandle { handle ->
                voteSubmissionNative(handle, roundId, bundleIndex, proposalId)
                    ?: error("voteSubmission returned null")
            }

        /**
         * Records the confirmed position of the vote commitment in the vote commitment tree.
         *
         * [getCommitmentBundle] reports null until this has been called.
         */
        @Throws(RuntimeException::class)
        suspend fun recordVcPosition(
            roundId: String,
            bundleIndex: Int,
            proposalId: Int,
            vcTreePosition: Long
        ) = withHandle { handle ->
            check(recordVcPositionNative(handle, roundId, bundleIndex, proposalId, vcTreePosition)) {
                "recordVcPosition failed for roundId=$roundId bundleIndex=$bundleIndex proposalId=$proposalId"
            }
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
         * Records [txHash] as the cast-vote transaction for this vote.
         *
         * A vote is "submitted" by having a recorded transaction hash; there is no separate
         * flag. Recording the same hash twice is idempotent, but recording a *different* hash
         * for a vote that already has one fails, so that a wallet keeps polling the transaction
         * it originally submitted.
         */
        @Throws(RuntimeException::class)
        suspend fun markVoteSubmitted(
            roundId: String,
            bundleIndex: Int,
            proposalId: Int,
            txHash: String
        ) = withHandle { handle ->
            check(markVoteSubmittedNative(handle, roundId, bundleIndex, proposalId, txHash)) {
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

        /**
         * Reconstructs the stored vote commitment, with fresh helper-share payloads.
         *
         * Reports null until the vote reaches the confirmed phase — that is, until its
         * transaction hash has been recorded via [markVoteSubmitted] *and* its tree position
         * via [recordVcPosition]. It also reports null for a vote that was never stored. For
         * the window before confirmation, use [voteSubmission].
         */
        @Throws(RuntimeException::class)
        suspend fun getCommitmentBundle(
            roundId: String,
            bundleIndex: Int,
            proposalId: Int
        ): JniCommitmentBundleRecord? =
            withHandle { handle ->
                getCommitmentBundleNative(handle, roundId, bundleIndex, proposalId)
            }

        @Throws(RuntimeException::class)
        suspend fun clearRecoveryState(roundId: String) =
            withHandle { handle ->
                check(clearRecoveryStateNative(handle, roundId)) {
                    "clearRecoveryState failed for roundId=$roundId"
                }
            }

        /**
         * Records that a helper share was delegated.
         *
         * The share nullifier is no longer supplied by the caller: it is derived natively from
         * the vote's own recovery state, which is the only copy that is guaranteed to agree
         * with the stored commitment.
         */
        @Throws(RuntimeException::class)
        suspend fun recordShareDelegation(
            roundId: String,
            bundleIndex: Int,
            proposalId: Int,
            shareIndex: Int,
            sentToUrls: List<String>,
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
        private external fun extractOrchardFvkFromUfvkNative(
            ufvk: String,
            networkId: Int
        ): ByteArray?

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun deriveHotkeyRawAddressNative(
            hotkeyStoredSecret: ByteArray,
            networkId: Int
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
        private external fun openVotingDbNative(dbPath: String, walletId: String): Long

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
            networkId: Int,
            sessionJson: String?
        )

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun getRoundStateNative(dbHandle: Long, roundId: String): JniRoundState?

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
            roundId: String,
            networkId: Int
        ): JniVotingHotkey?

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun buildGovernancePcztNative(
            dbHandle: Long,
            roundId: String,
            bundleIndex: Int,
            fvkBytes: ByteArray,
            hotkeyStoredSecret: ByteArray,
            networkId: Int,
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
            hotkeyStoredSecret: ByteArray,
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
            networkId: Int,
            notes: Array<JniNoteInfo>
        ): JniDelegationPirPrecomputeResult?

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun buildAndProveDelegationNative(
            dbHandle: Long,
            roundId: String,
            bundleIndex: Int,
            pirServerUrl: String,
            networkId: Int,
            notes: Array<JniNoteInfo>,
            fvkBytes: ByteArray,
            hotkeyStoredSecret: ByteArray,
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
            spendAuthSig: ByteArray,
            sighash: ByteArray
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
        private external fun commitVoteNative(
            dbHandle: Long,
            roundId: String,
            bundleIndex: Int,
            hotkeyStoredSecret: ByteArray,
            networkId: Int,
            proposalId: Int,
            choice: Int,
            numOptions: Int,
            vcTreePosition: Long,
            witness: JniVanWitness,
            singleShare: Boolean,
            proofProgress: VotingProofProgressCallback?
        ): JniVoteCommitResult?

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun voteSubmissionNative(
            dbHandle: Long,
            roundId: String,
            bundleIndex: Int,
            proposalId: Int
        ): JniVoteSubmission?

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
        private external fun markVoteSubmittedNative(
            dbHandle: Long,
            roundId: String,
            bundleIndex: Int,
            proposalId: Int,
            txHash: String
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
