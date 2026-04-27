package cash.z.ecc.android.sdk.internal.jni

import cash.z.ecc.android.sdk.internal.model.voting.FfiBundleSetupResult
import cash.z.ecc.android.sdk.internal.model.voting.FfiRoundState
import cash.z.ecc.android.sdk.internal.model.voting.FfiVotingHotkey

/**
 * JNI interface for the shielded voting backend (zcash_voting Rust crate).
 *
 * Rust implementation: backend-lib/src/main/rust/voting.rs
 * Crate: https://github.com/valargroup/zcash_voting (branch greg/orchard-0.12)
 * iOS reference: valargroup/zcash-swift-wallet-sdk rust/src/voting.rs
 *
 * Complex return types (GovernancePczt, DelegationProofResult, VoteCommitmentBundle,
 * SharePayload, VanWitness, WitnessData) are serialised as JSON strings by the Rust layer.
 * Byte arrays in JSON are hex-encoded lowercase strings.
 */
@Suppress("TooManyFunctions")
internal object VotingRustBackend {

    // ─── Database lifecycle ────────────────────────────────────────────────────

    /** Opens (or creates) the voting SQLite database. Returns opaque handle or 0 on error. */
    @JvmStatic
    external fun openVotingDb(dbPath: String): Long

    /** Frees the handle returned by [openVotingDb]. */
    @JvmStatic
    external fun closeVotingDb(dbHandle: Long)

    /** Associates a wallet identity string with this database (multi-wallet support). */
    @JvmStatic
    external fun setWalletId(dbHandle: Long, walletId: String): Boolean

    // ─── Round management ─────────────────────────────────────────────────────

    @JvmStatic
    external fun initRound(
        dbHandle: Long,
        roundId: String,
        snapshotHeight: Long,
        eaPK: ByteArray,
        ncRoot: ByteArray,
        nullifierIMTRoot: ByteArray,
        sessionJson: String?
    ): Boolean

    /** Returns current phase and metadata for the round, or null on error. */
    @JvmStatic
    external fun getRoundState(dbHandle: Long, roundId: String): FfiRoundState?

    /** Returns JSON array of round summaries. */
    @JvmStatic
    external fun listRoundsJson(dbHandle: Long): String

    @JvmStatic
    external fun clearRound(dbHandle: Long, roundId: String): Boolean

    // ─── Note setup ───────────────────────────────────────────────────────────

    /**
     * Splits [notesJson] (JSON array of NoteInfo) into voting bundles.
     * Returns bundle count + eligible weight, or null on error.
     */
    @JvmStatic
    external fun setupBundles(
        dbHandle: Long,
        roundId: String,
        notesJson: String
    ): FfiBundleSetupResult?

    // ─── Hotkey generation ────────────────────────────────────────────────────

    /**
     * Generates a per-round Pallas voting hotkey from [seed] (≥ 32 bytes).
     * Returns [FfiVotingHotkey] with secret/public key and "sv1…" address, or null on error.
     * Caller must securely erase [seed] after use.
     */
    @JvmStatic
    external fun generateHotkey(
        dbHandle: Long,
        roundId: String,
        seed: ByteArray
    ): FfiVotingHotkey?

    // ─── Governance PCZT ─────────────────────────────────────────────────────

    /**
     * Builds a governance PCZT for Keystone signing.
     *
     * @param notesJson       JSON array of NoteInfo for this bundle (from wallet at snapshotHeight)
     * @param hotkeyRawSeed   32-byte hotkey seed — used to derive the 43-byte Orchard hotkey address
     * @param seedFingerprint 32-byte ZIP-32 seed fingerprint of the wallet
     * @param roundName       Human-readable round name from the session data
     * @param addressIndex    ZIP-32 address index (normally 0)
     *
     * Returns JSON-encoded GovernancePczt, or null on error.
     */
    @JvmStatic
    external fun buildGovernancePcztJson(
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
        addressIndex: Int
    ): String?

    /** Extracts the ZIP-244 sighash from a PCZT blob. Returns 32 bytes or null. */
    @JvmStatic
    external fun extractPcztSighash(pcztBytes: ByteArray): ByteArray?

    /** Extracts the Orchard SpendAuth signature for the action at [actionIndex]. */
    @JvmStatic
    external fun extractSpendAuthSig(
        signedPcztBytes: ByteArray,
        actionIndex: Int
    ): ByteArray?

    // ─── Witnesses (required before buildAndProveDelegationJson) ─────────────

    /**
     * Caches Merkle witnesses for all notes in a bundle.
     * Must be called before [buildAndProveDelegationJson].
     *
     * @param witnessesJson JSON array of WitnessData (one per note in the bundle)
     */
    @JvmStatic
    external fun storeWitnesses(
        dbHandle: Long,
        roundId: String,
        bundleIndex: Int,
        witnessesJson: String
    ): Boolean

    // ─── Delegation proof (ZKP1) ───────────────────────────────────────────────

    /**
     * Generates the Halo2 delegation proof. Long-running — call on a background coroutine.
     *
     * @param notesJson     JSON array of NoteInfo (same notes used in [storeWitnesses])
     * @param hotkeyRawSeed 32-byte hotkey seed (re-derives Orchard hotkey address internally)
     *
     * Returns JSON-encoded DelegationProofResult, or null on error.
     */
    @JvmStatic
    external fun buildAndProveDelegationJson(
        dbHandle: Long,
        roundId: String,
        bundleIndex: Int,
        pirServerUrl: String,
        networkId: Int,
        notesJson: String,
        hotkeyRawSeed: ByteArray
    ): String?

    // ─── Vote commitment tree ─────────────────────────────────────────────────

    /**
     * Syncs the vote commitment tree from the chain node.
     * Returns the latest synced block height, or -1 on error.
     */
    @JvmStatic
    external fun syncVoteTree(
        dbHandle: Long,
        roundId: String,
        nodeUrl: String
    ): Long

    /**
     * Stores the VAN leaf position after the delegation TX is confirmed on chain.
     * Must be called before [generateVanWitnessJson].
     */
    @JvmStatic
    external fun storeVanPosition(
        dbHandle: Long,
        roundId: String,
        bundleIndex: Int,
        position: Int
    ): Boolean

    /**
     * Generates a VAN Merkle witness. Must be called after [syncVoteTree] and [storeVanPosition].
     * Returns JSON-encoded VanWitness (auth_path, position, anchor_height), or null on error.
     */
    @JvmStatic
    external fun generateVanWitnessJson(
        dbHandle: Long,
        roundId: String,
        bundleIndex: Int,
        anchorHeight: Int
    ): String?

    // ─── Tree state + note witnesses ─────────────────────────────────────────

    /**
     * Caches the lightwalletd TreeState protobuf for the snapshot height.
     * Must be called before [generateNoteWitnessesJson].
     */
    @JvmStatic
    external fun storeTreeState(
        dbHandle: Long,
        roundId: String,
        treeStateBytes: ByteArray
    ): Boolean

    /**
     * Fetches unspent Orchard notes at [snapshotHeight] from the wallet database.
     * [accountUuidBytes] must be exactly 16 bytes (UUID representation).
     * Returns JSON array of NoteInfo (hex-encoded fields), or null on error.
     */
    @JvmStatic
    external fun getWalletNotesJson(
        walletDbPath: String,
        snapshotHeight: Long,
        networkId: Int,
        accountUuidBytes: ByteArray
    ): String?

    /**
     * Generates Orchard Merkle witnesses for notes in a bundle.
     * Requires [storeTreeState] to have been called first.
     * Internally calls [storeWitnesses] to cache results in the voting DB.
     * Returns JSON array of WitnessData, or null on error.
     */
    @JvmStatic
    external fun generateNoteWitnessesJson(
        dbHandle: Long,
        roundId: String,
        bundleIndex: Int,
        walletDbPath: String,
        notesJson: String
    ): String?

    // ─── Vote commitment (ZKP2) ────────────────────────────────────────────────

    /**
     * Builds the vote commitment proof for one proposal choice.
     *
     * @param witnessJson  JSON-encoded VanWitness returned by [generateVanWitnessJson]
     * @param singleShare  true for single-share mode (test/dev only)
     *
     * Returns JSON-encoded VoteCommitmentBundle (wire-safe — no secret share fields).
     */
    @JvmStatic
    external fun buildVoteCommitmentJson(
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
        singleShare: Boolean
    ): String?

    // ─── Share payloads ────────────────────────────────────────────────────────

    /**
     * Builds share payloads for distribution to tally-server helpers.
     *
     * @param encSharesJson    JSON array of WireEncryptedShare extracted from the bundle
     * @param commitmentJson   Full JSON-encoded VoteCommitmentBundle from [buildVoteCommitmentJson]
     * @param vcTreePosition   Position of the VC leaf after cast-vote TX is confirmed
     *
     * Returns JSON array of SharePayload, or null on error.
     */
    @JvmStatic
    external fun buildSharePayloadsJson(
        encSharesJson: String,
        commitmentJson: String,
        voteDecision: Int,
        numOptions: Int,
        vcTreePosition: Long,
        singleShareMode: Boolean
    ): String?

    // ─── Cast vote signature ───────────────────────────────────────────────────

    /** Signs the cast-vote message. Returns 64-byte RedPallas signature, or null. */
    @JvmStatic
    external fun signCastVote(
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
    ): ByteArray?

    // ─── Delegation submission ─────────────────────────────────────────────────

    /**
     * Reconstructs the chain-ready delegation TX payload from DB + wallet seed.
     * Signs the ZIP-244 sighash with the randomised spending key.
     * Returns JSON-encoded DelegationSubmissionData, or null on error.
     */
    @JvmStatic
    external fun getDelegationSubmissionJson(
        dbHandle: Long,
        roundId: String,
        bundleIndex: Int,
        senderSeed: ByteArray,
        networkId: Int,
        accountIndex: Int
    ): String?

    /**
     * Reconstructs the delegation TX payload using a Keystone-provided signature.
     * Returns JSON-encoded DelegationSubmissionData, or null on error.
     */
    @JvmStatic
    external fun getDelegationSubmissionWithKeystoneSigJson(
        dbHandle: Long,
        roundId: String,
        bundleIndex: Int,
        keystoneSig: ByteArray,
        keystoneSighash: ByteArray
    ): String?

    // ─── Recovery state ───────────────────────────────────────────────────────

    @JvmStatic
    external fun storeDelegationTxHash(
        dbHandle: Long,
        roundId: String,
        bundleIndex: Int,
        txHash: String
    ): Boolean

    @JvmStatic
    external fun getDelegationTxHash(
        dbHandle: Long,
        roundId: String,
        bundleIndex: Int
    ): String?

    @JvmStatic
    external fun storeVoteTxHash(
        dbHandle: Long,
        roundId: String,
        bundleIndex: Int,
        proposalId: Int,
        txHash: String
    ): Boolean

    @JvmStatic
    external fun getVoteTxHash(
        dbHandle: Long,
        roundId: String,
        bundleIndex: Int,
        proposalId: Int
    ): String?

    @JvmStatic
    external fun storeCommitmentBundle(
        dbHandle: Long,
        roundId: String,
        bundleIndex: Int,
        proposalId: Int,
        bundleJson: String,
        vcTreePosition: Long
    ): Boolean

    @JvmStatic
    external fun getCommitmentBundleJson(
        dbHandle: Long,
        roundId: String,
        bundleIndex: Int,
        proposalId: Int
    ): String?

    @JvmStatic
    external fun clearRecoveryState(
        dbHandle: Long,
        roundId: String
    ): Boolean

    @JvmStatic
    external fun recordShareDelegation(
        dbHandle: Long,
        roundId: String,
        bundleIndex: Int,
        proposalId: Int,
        shareIndex: Int,
        sentToUrlsJson: String,
        nullifier: ByteArray,
        submitAt: Long
    ): Boolean

    @JvmStatic
    external fun getShareDelegationsJson(
        dbHandle: Long,
        roundId: String
    ): String

    @JvmStatic
    external fun markShareConfirmed(
        dbHandle: Long,
        roundId: String,
        bundleIndex: Int,
        proposalId: Int,
        shareIndex: Int
    ): Boolean

    @JvmStatic
    external fun addSentServers(
        dbHandle: Long,
        roundId: String,
        bundleIndex: Int,
        proposalId: Int,
        shareIndex: Int,
        newUrlsJson: String
    ): Boolean

    @JvmStatic
    external fun computeShareNullifier(
        voteCommitment: ByteArray,
        shareIndex: Int,
        blind: ByteArray
    ): ByteArray?

    // ─── Stateless utilities ──────────────────────────────────────────────────

    /** Decomposes [weight] into power-of-2 share components. Returns JSON array of u64. */
    @JvmStatic
    external fun decomposeWeightJson(weight: Long): String

    /** Extracts 96-byte Orchard FVK from a UFVK string. Returns bytes or null. */
    @JvmStatic
    external fun extractOrchardFvkFromUfvk(ufvk: String, networkId: Int): ByteArray?
}
