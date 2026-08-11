package cash.z.ecc.android.sdk.internal.model.voting

import androidx.annotation.Keep
import cash.z.ecc.android.sdk.internal.jni.JNI_HOTKEY_STORED_SECRET_BYTES_SIZE
import cash.z.ecc.android.sdk.internal.jni.JNI_ORCHARD_RAW_ADDRESS_BYTES_SIZE

/**
 * Typed JNI carrier for a spendable note the voting backend may draw voting weight from.
 *
 * [toString] is redacted: [rseed] and [rho] reconstruct the note's spending randomness,
 * [nullifier] links the note to its spend, and [ufvk] is a full viewing key that discloses
 * the entire account's transaction history. The generated `data class` rendering would print
 * all four into any log line that interpolates a note.
 */
@Keep
data class JniNoteInfo(
    val commitment: ByteArray,
    val nullifier: ByteArray,
    val value: Long,
    val position: Long,
    val diversifier: ByteArray,
    val rho: ByteArray,
    val rseed: ByteArray,
    val scope: Int,
    val ufvk: String
) {
    override fun toString(): String = "JniNoteInfo(redacted)"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is JniNoteInfo) return false
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
        result = 31 * result + scope
        result = 31 * result + ufvk.hashCode()
        return result
    }
}

@Keep
data class JniWitnessData(
    val noteCommitment: ByteArray,
    val position: Long,
    val root: ByteArray,
    val authPath: List<ByteArray>
) {
    internal constructor(
        noteCommitment: ByteArray,
        position: Long,
        root: ByteArray,
        authPath: Array<ByteArray>
    ) : this(
        noteCommitment = noteCommitment,
        position = position,
        root = root,
        authPath = authPath.toList()
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is JniWitnessData) return false
        return noteCommitment.contentEquals(other.noteCommitment) &&
            position == other.position &&
            root.contentEquals(other.root) &&
            authPath.contentDeepEquals(other.authPath)
    }

    override fun hashCode(): Int {
        var result = noteCommitment.contentHashCode()
        result = 31 * result + position.hashCode()
        result = 31 * result + root.contentHashCode()
        result = 31 * result + authPath.contentDeepHashCode()
        return result
    }
}

@Keep
data class JniVanWitness(
    val authPath: List<ByteArray>,
    val position: Long,
    val anchorHeight: Long
) {
    internal constructor(
        authPath: Array<ByteArray>,
        position: Long,
        anchorHeight: Long
    ) : this(
        authPath = authPath.toList(),
        position = position,
        anchorHeight = anchorHeight
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is JniVanWitness) return false
        return authPath.contentDeepEquals(other.authPath) &&
            position == other.position &&
            anchorHeight == other.anchorHeight
    }

    override fun hashCode(): Int {
        var result = authPath.contentDeepHashCode()
        result = 31 * result + position.hashCode()
        result = 31 * result + anchorHeight.hashCode()
        return result
    }
}

@Keep
data class JniWireEncryptedShare(
    val c1: ByteArray,
    val c2: ByteArray,
    val shareIndex: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is JniWireEncryptedShare) return false
        return c1.contentEquals(other.c1) &&
            c2.contentEquals(other.c2) &&
            shareIndex == other.shareIndex
    }

    override fun hashCode(): Int {
        var result = c1.contentHashCode()
        result = 31 * result + c2.contentHashCode()
        result = 31 * result + shareIndex
        return result
    }
}

/**
 * Typed JNI carrier for the outputs of a completed vote commitment.
 *
 * `rVpk` and `voteAuthSig` are signing material for the cast-vote transaction and
 * must not be logged; [toString] is redacted for that reason. The blinds, share
 * commitments and per-vote randomness that earlier releases carried here are now
 * owned by `zcash_voting` and are never exposed across JNI — recovery reads them
 * back through `getCommitmentBundle`, not from a caller-held copy.
 *
 * [sharePayloads] arrives with the commitment rather than from a separate call:
 * the payloads are derived from the same commitment the native side just built.
 */
@Keep
data class JniVoteCommitResult(
    val vanNullifier: ByteArray,
    val voteAuthorityNoteNew: ByteArray,
    val voteCommitment: ByteArray,
    val proposalId: Int,
    val bundleIndex: Int,
    val proof: ByteArray,
    val anchorHeight: Long,
    val rVpk: ByteArray,
    val voteAuthSig: ByteArray,
    val encShares: List<JniWireEncryptedShare>,
    val sharePayloads: List<JniSharePayload>
) {
    internal constructor(
        vanNullifier: ByteArray,
        voteAuthorityNoteNew: ByteArray,
        voteCommitment: ByteArray,
        proposalId: Int,
        bundleIndex: Int,
        proof: ByteArray,
        anchorHeight: Long,
        rVpk: ByteArray,
        voteAuthSig: ByteArray,
        encShares: Array<JniWireEncryptedShare>,
        sharePayloads: Array<JniSharePayload>
    ) : this(
        vanNullifier = vanNullifier,
        voteAuthorityNoteNew = voteAuthorityNoteNew,
        voteCommitment = voteCommitment,
        proposalId = proposalId,
        bundleIndex = bundleIndex,
        proof = proof,
        anchorHeight = anchorHeight,
        rVpk = rVpk,
        voteAuthSig = voteAuthSig,
        encShares = encShares.toList(),
        sharePayloads = sharePayloads.toList()
    )

    override fun toString(): String = "JniVoteCommitResult(redacted)"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is JniVoteCommitResult) return false
        return scalarFieldsEqual(other) &&
            byteFieldsEqual(other) &&
            listFieldsEqual(other)
    }

    private fun scalarFieldsEqual(other: JniVoteCommitResult) =
        proposalId == other.proposalId &&
            bundleIndex == other.bundleIndex &&
            anchorHeight == other.anchorHeight

    private fun byteFieldsEqual(other: JniVoteCommitResult) =
        vanNullifier.contentEquals(other.vanNullifier) &&
            voteAuthorityNoteNew.contentEquals(other.voteAuthorityNoteNew) &&
            voteCommitment.contentEquals(other.voteCommitment) &&
            proof.contentEquals(other.proof) &&
            rVpk.contentEquals(other.rVpk) &&
            voteAuthSig.contentEquals(other.voteAuthSig)

    private fun listFieldsEqual(other: JniVoteCommitResult) =
        encShares == other.encShares &&
            sharePayloads == other.sharePayloads

    override fun hashCode(): Int {
        var result = vanNullifier.contentHashCode()
        result = 31 * result + voteAuthorityNoteNew.contentHashCode()
        result = 31 * result + voteCommitment.contentHashCode()
        result = 31 * result + proposalId
        result = 31 * result + bundleIndex
        result = 31 * result + proof.contentHashCode()
        result = 31 * result + anchorHeight.hashCode()
        result = 31 * result + rVpk.contentHashCode()
        result = 31 * result + voteAuthSig.contentHashCode()
        result = 31 * result + encShares.hashCode()
        result = 31 * result + sharePayloads.hashCode()
        return result
    }
}

/**
 * Typed JNI carrier for the chain-ready fields of a cast-vote transaction.
 *
 * This is the pre-confirmation resend view: it carries everything needed to
 * rebuild and resubmit the cast-vote transaction, and deliberately omits the
 * helper-share payloads, which go stale once the vote commitment tree position
 * has been recorded. After confirmation, use `getCommitmentBundle` instead to
 * obtain fresh payloads.
 */
@Keep
data class JniVoteSubmission(
    val voteRoundId: String,
    val proposalId: Int,
    val bundleIndex: Int,
    val vanNullifier: ByteArray,
    val voteAuthorityNoteNew: ByteArray,
    val voteCommitment: ByteArray,
    val proof: ByteArray,
    val rVpk: ByteArray,
    val voteAuthSig: ByteArray,
    val anchorHeight: Long
) {
    override fun toString(): String = "JniVoteSubmission(redacted)"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is JniVoteSubmission) return false
        return voteRoundId == other.voteRoundId &&
            proposalId == other.proposalId &&
            bundleIndex == other.bundleIndex &&
            anchorHeight == other.anchorHeight &&
            vanNullifier.contentEquals(other.vanNullifier) &&
            voteAuthorityNoteNew.contentEquals(other.voteAuthorityNoteNew) &&
            voteCommitment.contentEquals(other.voteCommitment) &&
            proof.contentEquals(other.proof) &&
            rVpk.contentEquals(other.rVpk) &&
            voteAuthSig.contentEquals(other.voteAuthSig)
    }

    override fun hashCode(): Int {
        var result = voteRoundId.hashCode()
        result = 31 * result + proposalId
        result = 31 * result + bundleIndex
        result = 31 * result + anchorHeight.hashCode()
        result = 31 * result + vanNullifier.contentHashCode()
        result = 31 * result + voteAuthorityNoteNew.contentHashCode()
        result = 31 * result + voteCommitment.contentHashCode()
        result = 31 * result + proof.contentHashCode()
        result = 31 * result + rVpk.contentHashCode()
        result = 31 * result + voteAuthSig.contentHashCode()
        return result
    }
}

@Keep
data class JniCommitmentBundleRecord(
    val commitment: JniVoteCommitResult,
    val vcTreePosition: Long
)

/**
 * Typed JNI carrier for one helper-share payload of a vote commitment.
 *
 * [toString] is redacted: [primaryBlind] is the blinding factor that opens the vote
 * commitment, so printing it would let a log reader recover the plaintext vote.
 */
@Keep
data class JniSharePayload(
    val sharesHash: ByteArray,
    val proposalId: Int,
    val voteDecision: Int,
    val encShare: JniWireEncryptedShare,
    val treePosition: Long,
    val allEncShares: List<JniWireEncryptedShare>,
    val shareComms: List<ByteArray>,
    val primaryBlind: ByteArray
) {
    internal constructor(
        sharesHash: ByteArray,
        proposalId: Int,
        voteDecision: Int,
        encShare: JniWireEncryptedShare,
        treePosition: Long,
        allEncShares: Array<JniWireEncryptedShare>,
        shareComms: Array<ByteArray>,
        primaryBlind: ByteArray
    ) : this(
        sharesHash = sharesHash,
        proposalId = proposalId,
        voteDecision = voteDecision,
        encShare = encShare,
        treePosition = treePosition,
        allEncShares = allEncShares.toList(),
        shareComms = shareComms.toList(),
        primaryBlind = primaryBlind
    )

    override fun toString(): String = "JniSharePayload(redacted)"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is JniSharePayload) return false
        return sharesHash.contentEquals(other.sharesHash) &&
            proposalId == other.proposalId &&
            voteDecision == other.voteDecision &&
            encShare == other.encShare &&
            treePosition == other.treePosition &&
            allEncShares == other.allEncShares &&
            shareComms.contentDeepEquals(other.shareComms) &&
            primaryBlind.contentEquals(other.primaryBlind)
    }

    override fun hashCode(): Int {
        var result = sharesHash.contentHashCode()
        result = 31 * result + proposalId
        result = 31 * result + voteDecision
        result = 31 * result + encShare.hashCode()
        result = 31 * result + treePosition.hashCode()
        result = 31 * result + allEncShares.hashCode()
        result = 31 * result + shareComms.contentDeepHashCode()
        result = 31 * result + primaryBlind.contentHashCode()
        return result
    }
}

/**
 * Typed JNI carrier for the delegation bookkeeping of a single helper share.
 *
 * [toString] is redacted: [nullifier] is the share nullifier, and publishing it alongside the
 * round and proposal it belongs to is exactly the linkage the share protocol exists to avoid.
 */
@Keep
data class JniShareDelegationRecord(
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
    internal constructor(
        roundId: String,
        bundleIndex: Int,
        proposalId: Int,
        shareIndex: Int,
        sentToUrls: Array<String>,
        nullifier: ByteArray,
        confirmed: Boolean,
        submitAt: Long,
        createdAt: Long
    ) : this(
        roundId = roundId,
        bundleIndex = bundleIndex,
        proposalId = proposalId,
        shareIndex = shareIndex,
        sentToUrls = sentToUrls.toList(),
        nullifier = nullifier,
        confirmed = confirmed,
        submitAt = submitAt,
        createdAt = createdAt
    )

    override fun toString(): String = "JniShareDelegationRecord(redacted)"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is JniShareDelegationRecord) return false
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
        result = 31 * result + sentToUrls.hashCode()
        result = 31 * result + nullifier.contentHashCode()
        result = 31 * result + confirmed.hashCode()
        result = 31 * result + submitAt.hashCode()
        result = 31 * result + createdAt.hashCode()
        return result
    }
}

/**
 * A voting hotkey: the identity a round's voting power is delegated to.
 *
 * **The application must persist [storedSecret], and must persist it before the
 * delegation transaction is broadcast.** A voting hotkey is app-owned random
 * material generated by `zcash_voting`; it is *not* derived from the wallet seed.
 * That has three consequences a wallet has to design around:
 *
 * - [storedSecret] cannot be recovered or re-derived from anything else. Not from
 *   the wallet seed phrase, not from the wallet database, not from the chain.
 * - Restoring a wallet from its seed phrase does **not** restore the ability to
 *   vote in a round whose hotkey secret was not separately backed up.
 * - Losing [storedSecret] forfeits the voting power already delegated to this
 *   hotkey for the round. The delegation cannot be reissued to a new hotkey.
 *
 * Store it in platform secure storage (Android Keystore-wrapped, or equivalent),
 * keyed by round, alongside the wallet's other secrets. Hand it back to
 * `buildGovernancePczt`, `buildAndProveDelegation` and `commitVote` for the rest
 * of the round's lifecycle, and to `deriveHotkeyRawAddress` to recover
 * [rawOrchardAddress] if it was not retained.
 *
 * [toString] is redacted: this is a secret-carrying type and the generated
 * `data class` rendering would print the secret's bytes into any log that
 * interpolates it.
 *
 * Both byte arrays are length-checked on construction, so a malformed hotkey
 * cannot exist regardless of where it came from. That includes the native layer:
 * JNI's `NewObject` runs the constructor, and therefore this `init` block, so a
 * bug in `make_jni_voting_hotkey` surfaces here rather than travelling onward as
 * a short secret. The constructor stays public because the invariant is enforced
 * by the type rather than by visibility, and constructing one grants no
 * capability in any case: every native entry point takes the raw [storedSecret]
 * bytes, not this carrier.
 */
@Keep
data class JniVotingHotkey(
    val storedSecret: ByteArray,
    val rawOrchardAddress: ByteArray,
    val addressIndex: Int
) {
    init {
        require(storedSecret.size == JNI_HOTKEY_STORED_SECRET_BYTES_SIZE) {
            "storedSecret must be $JNI_HOTKEY_STORED_SECRET_BYTES_SIZE bytes"
        }

        require(rawOrchardAddress.size == JNI_ORCHARD_RAW_ADDRESS_BYTES_SIZE) {
            "rawOrchardAddress must be $JNI_ORCHARD_RAW_ADDRESS_BYTES_SIZE bytes"
        }
    }

    override fun toString(): String = "JniVotingHotkey(redacted)"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is JniVotingHotkey) return false
        return storedSecret.contentEquals(other.storedSecret) &&
            rawOrchardAddress.contentEquals(other.rawOrchardAddress) &&
            addressIndex == other.addressIndex
    }

    override fun hashCode(): Int {
        var result = storedSecret.contentHashCode()
        result = 31 * result + rawOrchardAddress.contentHashCode()
        result = 31 * result + addressIndex
        return result
    }
}

// Must match PHASE_* constants in backend-lib/src/main/rust/voting/helpers.rs.
internal const val JNI_ROUND_PHASE_INITIALIZED = 0
internal const val JNI_ROUND_PHASE_HOTKEY_GENERATED = 1
internal const val JNI_ROUND_PHASE_DELEGATION_CONSTRUCTED = 2
internal const val JNI_ROUND_PHASE_DELEGATION_PROVED = 3
internal const val JNI_ROUND_PHASE_VOTE_READY = 4

@Keep
data class JniBundleSetupResult(
    val bundleCount: Int,
    val eligibleWeight: Long,
    val bundleWeights: List<Long>
) {
    internal constructor(bundleCount: Int, eligibleWeight: Long, bundleWeights: LongArray) :
        this(bundleCount, eligibleWeight, bundleWeights.toList())
}

@Keep
data class JniGovernancePczt(
    val pcztBytes: ByteArray,
    val rk: ByteArray,
    val sighash: ByteArray,
    val actionIndex: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is JniGovernancePczt) return false
        return pcztBytes.contentEquals(other.pcztBytes) &&
            rk.contentEquals(other.rk) &&
            sighash.contentEquals(other.sighash) &&
            actionIndex == other.actionIndex
    }

    override fun hashCode(): Int {
        var result = pcztBytes.contentHashCode()
        result = 31 * result + rk.contentHashCode()
        result = 31 * result + sighash.contentHashCode()
        result = 31 * result + actionIndex
        return result
    }
}

/**
 * Round lifecycle state as reported by the native voting database.
 *
 * [hotkeyAddress] and [delegatedWeight] are **always null**. `zcash_voting` does
 * not populate either field on the round state it returns, regardless of what
 * hotkey generation or delegation did, so neither can be read back from here.
 * Obtain the hotkey's address by calling `deriveHotkeyRawAddress` with the
 * secret the application persisted (see [JniVotingHotkey]); obtain the delegated
 * weight from the bundle weights `setupBundles` returned. Both fields are kept
 * because the native constructor signature still passes them.
 */
@Keep
data class JniRoundState(
    val roundId: String,
    val phase: Int,
    val snapshotHeight: Long,
    val hotkeyAddress: String?,
    val delegatedWeight: Long?,
    val proofGenerated: Boolean
) {
    val roundPhase = JniRoundPhase.fromInt(phase)
}

@Keep
enum class JniRoundPhase(
    val value: Int
) {
    INITIALIZED(JNI_ROUND_PHASE_INITIALIZED),
    HOTKEY_GENERATED(JNI_ROUND_PHASE_HOTKEY_GENERATED),
    DELEGATION_CONSTRUCTED(JNI_ROUND_PHASE_DELEGATION_CONSTRUCTED),
    DELEGATION_PROVED(JNI_ROUND_PHASE_DELEGATION_PROVED),
    VOTE_READY(JNI_ROUND_PHASE_VOTE_READY);

    companion object {
        fun fromInt(value: Int) =
            entries.firstOrNull { it.value == value }
                ?: error("Unknown round phase: $value")
    }
}

@Keep
data class JniRoundSummary(
    val roundId: String,
    val phase: Int,
    val snapshotHeight: Long,
    val createdAt: Long
) {
    val roundPhase = JniRoundPhase.fromInt(phase)
}

@Keep
data class JniVoteRecord(
    val proposalId: Int,
    val bundleIndex: Int,
    val choice: Int,
    val submitted: Boolean
)

@Keep
data class JniDelegationPirPrecomputeResult(
    val cachedCount: Long,
    val fetchedCount: Long
)

@Keep
data class JniDelegationProofResult(
    val proof: ByteArray,
    val publicInputs: List<ByteArray>,
    val nfSigned: ByteArray,
    val cmxNew: ByteArray,
    val govNullifiers: List<ByteArray>,
    val vanComm: ByteArray,
    val rk: ByteArray
) {
    internal constructor(
        proof: ByteArray,
        publicInputs: Array<ByteArray>,
        nfSigned: ByteArray,
        cmxNew: ByteArray,
        govNullifiers: Array<ByteArray>,
        vanComm: ByteArray,
        rk: ByteArray
    ) : this(
        proof = proof,
        publicInputs = publicInputs.toList(),
        nfSigned = nfSigned,
        cmxNew = cmxNew,
        govNullifiers = govNullifiers.toList(),
        vanComm = vanComm,
        rk = rk
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is JniDelegationProofResult) return false
        return proof.contentEquals(other.proof) &&
            publicInputs.contentDeepEquals(other.publicInputs) &&
            nfSigned.contentEquals(other.nfSigned) &&
            cmxNew.contentEquals(other.cmxNew) &&
            govNullifiers.contentDeepEquals(other.govNullifiers) &&
            vanComm.contentEquals(other.vanComm) &&
            rk.contentEquals(other.rk)
    }

    override fun hashCode(): Int {
        var result = proof.contentHashCode()
        result = 31 * result + publicInputs.contentDeepHashCode()
        result = 31 * result + nfSigned.contentHashCode()
        result = 31 * result + cmxNew.contentHashCode()
        result = 31 * result + govNullifiers.contentDeepHashCode()
        result = 31 * result + vanComm.contentHashCode()
        result = 31 * result + rk.contentHashCode()
        return result
    }
}

@Keep
data class JniDelegationSubmissionResult(
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
    internal constructor(
        proof: ByteArray,
        rk: ByteArray,
        spendAuthSig: ByteArray,
        sighash: ByteArray,
        tx1Effects: ByteArray,
        nfSigned: ByteArray,
        cmxNew: ByteArray,
        govComm: ByteArray,
        govNullifiers: Array<ByteArray>,
        voteRoundId: String
    ) : this(
        proof = proof,
        rk = rk,
        spendAuthSig = spendAuthSig,
        sighash = sighash,
        tx1Effects = tx1Effects,
        nfSigned = nfSigned,
        cmxNew = cmxNew,
        govComm = govComm,
        govNullifiers = govNullifiers.toList(),
        voteRoundId = voteRoundId
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is JniDelegationSubmissionResult) return false
        return proof.contentEquals(other.proof) &&
            rk.contentEquals(other.rk) &&
            spendAuthSig.contentEquals(other.spendAuthSig) &&
            sighash.contentEquals(other.sighash) &&
            tx1Effects.contentEquals(other.tx1Effects) &&
            nfSigned.contentEquals(other.nfSigned) &&
            cmxNew.contentEquals(other.cmxNew) &&
            govComm.contentEquals(other.govComm) &&
            govNullifiers.contentDeepEquals(other.govNullifiers) &&
            voteRoundId == other.voteRoundId
    }

    override fun hashCode(): Int {
        var result = proof.contentHashCode()
        result = 31 * result + rk.contentHashCode()
        result = 31 * result + spendAuthSig.contentHashCode()
        result = 31 * result + sighash.contentHashCode()
        result = 31 * result + tx1Effects.contentHashCode()
        result = 31 * result + nfSigned.contentHashCode()
        result = 31 * result + cmxNew.contentHashCode()
        result = 31 * result + govComm.contentHashCode()
        result = 31 * result + govNullifiers.contentDeepHashCode()
        result = 31 * result + voteRoundId.hashCode()
        return result
    }
}

/**
 * The canonical, per-bundle delegation phase (`prepared`, `pczt_built`, `proved`, `submitted`,
 * `confirmed` — matches `zcash_voting::phases::DelegationPhase::as_str`), derived on read from
 * persisted artifacts rather than the coarse round-level phase on [JniRoundState].
 */
@Keep
data class JniDelegationPhase(
    val bundleIndex: Int,
    val phase: String
)

private fun List<ByteArray>.contentDeepEquals(other: List<ByteArray>): Boolean =
    size == other.size && zip(other).all { (left, right) -> left.contentEquals(right) }

private fun List<ByteArray>.contentDeepHashCode(): Int =
    toTypedArray().contentDeepHashCode()
