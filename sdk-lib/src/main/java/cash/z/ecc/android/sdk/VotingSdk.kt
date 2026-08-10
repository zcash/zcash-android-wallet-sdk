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
