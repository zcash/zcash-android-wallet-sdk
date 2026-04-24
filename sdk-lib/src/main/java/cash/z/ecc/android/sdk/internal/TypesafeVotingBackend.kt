package cash.z.ecc.android.sdk.internal

import cash.z.ecc.android.sdk.internal.model.voting.FfiBundleSetupResult
import cash.z.ecc.android.sdk.internal.model.voting.FfiRoundState
import cash.z.ecc.android.sdk.internal.model.voting.FfiVotingHotkey

/**
 * Type-safe orchestration layer for shielded voting operations.
 *
 * Sits between [SdkSynchronizer] and [VotingRustBackend], providing suspend functions
 * with proper Kotlin types and encapsulating the multi-step voting protocol.
 *
 * Caller responsibilities (managed by the app/VM layer, not this interface):
 *   - Fetching NoteInfo JSON from the wallet at the snapshot height.
 *   - Securely storing [FfiVotingHotkey.secretKey] between protocol steps.
 *   - Providing the 32-byte wallet seed fingerprint (ZIP-32 SeedFingerprint).
 *   - Confirming the delegation TX on chain and providing the VAN tree position.
 */
@Suppress("TooManyFunctions")
interface TypesafeVotingBackend {

    // ─── Database lifecycle ────────────────────────────────────────────────────

    suspend fun openVotingDb(dbPath: String): Long
    suspend fun closeVotingDb(dbHandle: Long)
    suspend fun setWalletId(dbHandle: Long, walletId: String)

    // ─── Round management ─────────────────────────────────────────────────────

    suspend fun initRound(
        dbHandle: Long,
        roundId: String,
        snapshotHeight: Long,
        eaPK: ByteArray,
        ncRoot: ByteArray,
        nullifierIMTRoot: ByteArray,
        sessionJson: String?
    )

    suspend fun getRoundState(dbHandle: Long, roundId: String): FfiRoundState?

    /** Returns JSON array of RoundSummary objects from the voting database. */
    suspend fun listRoundsJson(dbHandle: Long): String

    suspend fun clearRound(dbHandle: Long, roundId: String)

    // ─── Note setup ───────────────────────────────────────────────────────────

    /**
     * Splits [notesJson] (JSON array of NoteInfo at snapshotHeight) into voting bundles.
     *
     * NoteInfo fields (all hex-encoded bytes):
     * ```json
     * [{ "commitment":"hex", "nullifier":"hex", "value":12500000,
     *    "position":42, "diversifier":"hex", "rho":"hex", "rseed":"hex",
     *    "scope":0, "ufvk_str":"uviewtest1..." }, ...]
     * ```
     */
    suspend fun setupBundles(
        dbHandle: Long,
        roundId: String,
        notesJson: String
    ): FfiBundleSetupResult

    // ─── Hotkey generation ────────────────────────────────────────────────────

    /**
     * Generates a per-round Pallas voting hotkey from [seed] (≥ 32 random bytes).
     * Caller must store [FfiVotingHotkey.secretKey] securely (e.g. encrypted storage).
     */
    suspend fun generateHotkey(
        dbHandle: Long,
        roundId: String,
        seed: ByteArray
    ): FfiVotingHotkey

    // ─── Witnesses (required before buildAndProveDelegation) ─────────────────

    /**
     * Caches Merkle witnesses for all notes in a bundle.
     * Must be called before [buildAndProveDelegation].
     *
     * [witnessesJson] JSON array of WitnessData (one per note):
     * ```json
     * [{ "note_commitment":"hex", "position":42, "root":"hex",
     *    "auth_path":["hex32",...] }, ...]
     * ```
     */
    suspend fun storeWitnesses(
        dbHandle: Long,
        roundId: String,
        bundleIndex: Int,
        witnessesJson: String
    )

    // ─── Governance PCZT (Keystone signing flow) ──────────────────────────────

    /**
     * Builds the governance PCZT for [bundleIndex].
     *
     * @param notesJson       JSON array of NoteInfo for this bundle (same as [setupBundles])
     * @param hotkeyRawSeed   32-byte hotkey seed — used to derive the Orchard hotkey address
     * @param seedFingerprint 32-byte ZIP-32 seed fingerprint of the wallet (SeedFingerprint.to_bytes())
     * @param roundName       Human-readable round name from the session data
     * @param addressIndex    ZIP-32 address index for the governance PCZT output (normally 0)
     */
    suspend fun buildGovernancePczt(
        dbHandle: Long,
        roundId: String,
        bundleIndex: Int,
        ufvk: String,
        networkId: Int,
        accountIndex: Int,
        notesJson: String,
        hotkeyRawSeed: ByteArray,
        seedFingerprint: ByteArray,
        roundName: String,
        addressIndex: Int = 0
    ): GovernancePcztResult

    // ─── Delegation proof (ZKP1) ──────────────────────────────────────────────

    /**
     * Generates the Halo2 delegation proof. Long-running (10s–several minutes).
     *
     * @param notesJson     JSON array of NoteInfo for this bundle (same as [storeWitnesses])
     * @param hotkeyRawSeed 32-byte hotkey seed (Orchard hotkey address is derived from this)
     */
    suspend fun buildAndProveDelegation(
        dbHandle: Long,
        roundId: String,
        bundleIndex: Int,
        pirServerUrl: String,
        networkId: Int,
        notesJson: String,
        hotkeyRawSeed: ByteArray
    ): DelegationProofResult

    // ─── Delegation submission ─────────────────────────────────────────────────

    /**
     * Reconstructs the chain-ready delegation TX payload using the wallet spending key.
     * Call after [buildAndProveDelegation] completes.
     *
     * @param senderSeed   Wallet seed (used to sign the ZIP-244 sighash)
     * @param accountIndex ZIP-32 account index
     */
    suspend fun getDelegationSubmission(
        dbHandle: Long,
        roundId: String,
        bundleIndex: Int,
        senderSeed: ByteArray,
        networkId: Int,
        accountIndex: Int
    ): DelegationSubmissionResult

    /**
     * Reconstructs the delegation TX payload using a Keystone-provided signature.
     * Call instead of [getDelegationSubmission] for Keystone hardware wallet flow.
     */
    suspend fun getDelegationSubmissionWithKeystoneSig(
        dbHandle: Long,
        roundId: String,
        bundleIndex: Int,
        keystoneSig: ByteArray,
        keystoneSighash: ByteArray
    ): DelegationSubmissionResult

    // ─── Vote commitment tree ─────────────────────────────────────────────────

    /**
     * Syncs the vote commitment tree from the chain node.
     * Returns the latest synced block height, or -1 on error.
     */
    suspend fun syncVoteTree(
        dbHandle: Long,
        roundId: String,
        nodeUrl: String
    ): Long

    /**
     * Stores the VAN leaf position after the delegation TX is confirmed on chain.
     * Must be called before [generateVanWitnessJson].
     *
     * @param position VAN leaf position reported in the delegation TX response events
     */
    suspend fun storeVanPosition(
        dbHandle: Long,
        roundId: String,
        bundleIndex: Int,
        position: Int
    )

    /**
     * Generates the Merkle witness for the VAN note (input to ZKP2).
     * Requires [syncVoteTree] + [storeVanPosition] to have been called first.
     *
     * Returns JSON-encoded VanWitness:
     * ```json
     * { "auth_path":["hex32",...], "position":42, "anchor_height":1234 }
     * ```
     */
    suspend fun generateVanWitnessJson(
        dbHandle: Long,
        roundId: String,
        bundleIndex: Int,
        anchorHeight: Int
    ): String

    // ─── Tree state + note witnesses ─────────────────────────────────────────

    /** Caches the lightwalletd TreeState for the snapshot height (required before [generateNoteWitnesses]). */
    suspend fun storeTreeState(dbHandle: Long, roundId: String, treeStateBytes: ByteArray)

    /**
     * Returns NoteInfo JSON array for unspent Orchard notes at [snapshotHeight].
     * [accountUuidBytes] must be 16 bytes (UUID).
     */
    suspend fun getWalletNotes(
        walletDbPath: String,
        snapshotHeight: Long,
        networkId: Int,
        accountUuidBytes: ByteArray
    ): String

    /**
     * Generates Orchard witnesses for a bundle and caches them in the voting DB.
     * Requires [storeTreeState] first. Returns JSON array of WitnessData.
     */
    suspend fun generateNoteWitnesses(
        dbHandle: Long,
        roundId: String,
        bundleIndex: Int,
        walletDbPath: String,
        notesJson: String
    ): String

    // ─── Vote commitment (ZKP2) ────────────────────────────────────────────────

    /**
     * Builds the vote commitment proof for one proposal choice.
     *
     * @param witnessJson  JSON VanWitness from [generateVanWitnessJson]
     * @param singleShare  true for single-share mode (test / dev only)
     *
     * The returned [VoteCommitmentResult] includes the raw JSON needed for
     * [buildSharePayloadsJson] so the caller doesn't need to re-serialise.
     */
    suspend fun buildVoteCommitment(
        dbHandle: Long,
        roundId: String,
        bundleIndex: Int,
        hotkeySeed: ByteArray,
        proposalId: Int,
        choice: Int,
        numOptions: Int,
        witnessJson: String,
        vanPosition: Int,
        anchorHeight: Int,
        networkId: Int,
        singleShare: Boolean = false
    ): VoteCommitmentResult

    // ─── Share payloads ───────────────────────────────────────────────────────

    /**
     * Builds share payloads for distribution to vote-server helpers.
     *
     * Typical call pattern using [VoteCommitmentResult]:
     * ```kotlin
     * val payloadsJson = buildSharePayloadsJson(
     *     encSharesJson    = commitment.encSharesJson,
     *     commitmentJson   = commitment.rawBundleJson,
     *     voteDecision     = choice,
     *     numOptions       = numOptions,
     *     vcTreePosition   = confirmedVcPosition,
     *     singleShareMode  = false
     * )
     * ```
     *
     * Returns JSON array of SharePayload ready for [VotingApiProvider.submitVoteCommitment].
     */
    suspend fun buildSharePayloadsJson(
        encSharesJson: String,
        commitmentJson: String,
        voteDecision: Int,
        numOptions: Int,
        vcTreePosition: Long,
        singleShareMode: Boolean = false
    ): String

    // ─── Cast vote signature ───────────────────────────────────────────────────

    suspend fun signCastVote(
        hotkeySeed: ByteArray,
        networkId: Int,
        roundId: String,
        rVpk: ByteArray,
        vanNullifier: ByteArray,
        vanNew: ByteArray,
        voteCommitment: ByteArray,
        proposalId: Int,
        anchorHeight: Int,
        alphaV: ByteArray
    ): ByteArray

    // ─── Stateless utilities ──────────────────────────────────────────────────

    suspend fun decomposeWeight(weight: Long): List<Long>
    suspend fun extractOrchardFvkFromUfvk(ufvk: String, networkId: Int): ByteArray
}

// =============================================================================
// Result types
// =============================================================================

data class GovernancePcztResult(
    /** Raw PCZT bytes for display as QR code (Keystone) or direct signing. */
    val pcztBytes: ByteArray,
    /** ZIP-244 sighash — used to verify the Keystone signature. */
    val sighash: ByteArray,
    /** Index of the governance action within the PCZT. */
    val actionIndex: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GovernancePcztResult) return false
        return sighash.contentEquals(other.sighash)
    }

    override fun hashCode(): Int = sighash.contentHashCode()
}

data class DelegationProofResult(
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
        if (other !is DelegationProofResult) return false
        return proof.contentEquals(other.proof)
    }

    override fun hashCode(): Int = proof.contentHashCode()
}

data class VoteCommitmentResult(
    /** Parsed fields needed for [TypesafeVotingBackend.signCastVote]. */
    val vanNullifier: ByteArray,
    val voteAuthorityNoteNew: ByteArray,
    val voteCommitment: ByteArray,
    val rVpk: ByteArray,
    val alphaV: ByteArray,
    val anchorHeight: Int,
    /** Wire-safe encrypted shares JSON — pass to [TypesafeVotingBackend.buildSharePayloadsJson]. */
    val encSharesJson: String,
    /** Full bundle JSON — pass as [commitmentJson] to [TypesafeVotingBackend.buildSharePayloadsJson]. */
    val rawBundleJson: String
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VoteCommitmentResult) return false
        return voteCommitment.contentEquals(other.voteCommitment)
    }

    override fun hashCode(): Int = voteCommitment.contentHashCode()
}

data class DelegationSubmissionResult(
    val proof: ByteArray,
    val rk: ByteArray,
    val spendAuthSig: ByteArray,
    val sighash: ByteArray,
    val nfSigned: ByteArray,
    val cmxNew: ByteArray,
    val govComm: ByteArray,
    val govNullifiers: List<ByteArray>,
    val alpha: ByteArray,
    val voteRoundId: String
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DelegationSubmissionResult) return false
        return sighash.contentEquals(other.sighash)
    }

    override fun hashCode(): Int = sighash.contentHashCode()
}
