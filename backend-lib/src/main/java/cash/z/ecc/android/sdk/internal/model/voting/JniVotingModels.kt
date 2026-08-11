package cash.z.ecc.android.sdk.internal.model.voting

import androidx.annotation.Keep

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
 * Typed JNI carrier for vote commitment outputs.
 *
 * `shareBlinds`, `rVpk`, and `alphaV` are sensitive reveal/signing inputs.
 * They must not be logged or exposed outside the voting recovery path. They are
 * carried here because follow-up JNI calls consume the typed commitment result,
 * and recovery persistence needs them to resume after restart. Encrypted-share
 * plaintext and encryption randomness remain Rust-only and are not included in
 * [encShares].
 */
@Keep
data class JniVoteCommitmentResult(
    val vanNullifier: ByteArray,
    val voteAuthorityNoteNew: ByteArray,
    val voteCommitment: ByteArray,
    val proposalId: Int,
    val bundleIndex: Int,
    val proof: ByteArray,
    val encShares: List<JniWireEncryptedShare>,
    val anchorHeight: Long,
    val voteRoundId: String,
    val sharesHash: ByteArray,
    val shareBlinds: List<ByteArray>,
    val shareComms: List<ByteArray>,
    val rVpk: ByteArray,
    val alphaV: ByteArray
) {
    internal constructor(
        vanNullifier: ByteArray,
        voteAuthorityNoteNew: ByteArray,
        voteCommitment: ByteArray,
        proposalId: Int,
        bundleIndex: Int,
        proof: ByteArray,
        encShares: Array<JniWireEncryptedShare>,
        anchorHeight: Long,
        voteRoundId: String,
        sharesHash: ByteArray,
        shareBlinds: Array<ByteArray>,
        shareComms: Array<ByteArray>,
        rVpk: ByteArray,
        alphaV: ByteArray
    ) : this(
        vanNullifier = vanNullifier,
        voteAuthorityNoteNew = voteAuthorityNoteNew,
        voteCommitment = voteCommitment,
        proposalId = proposalId,
        bundleIndex = bundleIndex,
        proof = proof,
        encShares = encShares.toList(),
        anchorHeight = anchorHeight,
        voteRoundId = voteRoundId,
        sharesHash = sharesHash,
        shareBlinds = shareBlinds.toList(),
        shareComms = shareComms.toList(),
        rVpk = rVpk,
        alphaV = alphaV
    )

    override fun toString(): String = "JniVoteCommitmentResult(redacted)"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is JniVoteCommitmentResult) return false
        return scalarFieldsEqual(other) &&
            byteFieldsEqual(other) &&
            listFieldsEqual(other)
    }

    private fun scalarFieldsEqual(other: JniVoteCommitmentResult) =
        proposalId == other.proposalId &&
            bundleIndex == other.bundleIndex &&
            anchorHeight == other.anchorHeight &&
            voteRoundId == other.voteRoundId

    private fun byteFieldsEqual(other: JniVoteCommitmentResult) =
        vanNullifier.contentEquals(other.vanNullifier) &&
            voteAuthorityNoteNew.contentEquals(other.voteAuthorityNoteNew) &&
            voteCommitment.contentEquals(other.voteCommitment) &&
            proof.contentEquals(other.proof) &&
            sharesHash.contentEquals(other.sharesHash) &&
            rVpk.contentEquals(other.rVpk) &&
            alphaV.contentEquals(other.alphaV)

    private fun listFieldsEqual(other: JniVoteCommitmentResult) =
        encShares == other.encShares &&
            shareBlinds.contentDeepEquals(other.shareBlinds) &&
            shareComms.contentDeepEquals(other.shareComms)

    override fun hashCode(): Int {
        var result = vanNullifier.contentHashCode()
        result = 31 * result + voteAuthorityNoteNew.contentHashCode()
        result = 31 * result + voteCommitment.contentHashCode()
        result = 31 * result + proposalId
        result = 31 * result + bundleIndex
        result = 31 * result + proof.contentHashCode()
        result = 31 * result + encShares.hashCode()
        result = 31 * result + anchorHeight.hashCode()
        result = 31 * result + voteRoundId.hashCode()
        result = 31 * result + sharesHash.contentHashCode()
        result = 31 * result + shareBlinds.contentDeepHashCode()
        result = 31 * result + shareComms.contentDeepHashCode()
        result = 31 * result + rVpk.contentHashCode()
        result = 31 * result + alphaV.contentHashCode()
        return result
    }
}

@Keep
data class JniCommitmentBundleRecord(
    val commitment: JniVoteCommitmentResult,
    val vcTreePosition: Long
)

/**
 * Typed JNI carrier for the one-shot `vote::commit` result: the signed commitment bundle
 * plus the vote authorization signature and share payloads it produces.
 *
 * `rVpk` and `voteAuthSig` are signing-path outputs. They are carried here because recovery
 * persistence needs them to resume after restart, not because callers should act on them
 * directly.
 */
@Keep
data class JniVoteCommitResult(
    val bundleIndex: Int,
    val proposalId: Int,
    val choice: Int,
    val voteRoundId: String,
    val vanNullifier: ByteArray,
    val voteAuthorityNoteNew: ByteArray,
    val voteCommitment: ByteArray,
    val proof: ByteArray,
    val encShares: List<JniWireEncryptedShare>,
    val anchorHeight: Long,
    val sharesHash: ByteArray,
    val shareComms: List<ByteArray>,
    val rVpk: ByteArray,
    val voteAuthSig: ByteArray,
    val sharePayloads: List<JniSharePayload>
) {
    internal constructor(
        bundleIndex: Int,
        proposalId: Int,
        choice: Int,
        voteRoundId: String,
        vanNullifier: ByteArray,
        voteAuthorityNoteNew: ByteArray,
        voteCommitment: ByteArray,
        proof: ByteArray,
        encShares: Array<JniWireEncryptedShare>,
        anchorHeight: Long,
        sharesHash: ByteArray,
        shareComms: Array<ByteArray>,
        rVpk: ByteArray,
        voteAuthSig: ByteArray,
        sharePayloads: Array<JniSharePayload>
    ) : this(
        bundleIndex = bundleIndex,
        proposalId = proposalId,
        choice = choice,
        voteRoundId = voteRoundId,
        vanNullifier = vanNullifier,
        voteAuthorityNoteNew = voteAuthorityNoteNew,
        voteCommitment = voteCommitment,
        proof = proof,
        encShares = encShares.toList(),
        anchorHeight = anchorHeight,
        sharesHash = sharesHash,
        shareComms = shareComms.toList(),
        rVpk = rVpk,
        voteAuthSig = voteAuthSig,
        sharePayloads = sharePayloads.toList()
    )

    override fun toString(): String = "JniVoteCommitResult(redacted)"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is JniVoteCommitResult) return false
        return scalarFieldsEqual(other) && byteFieldsEqual(other) && listFieldsEqual(other)
    }

    private fun scalarFieldsEqual(other: JniVoteCommitResult) =
        bundleIndex == other.bundleIndex &&
            proposalId == other.proposalId &&
            choice == other.choice &&
            voteRoundId == other.voteRoundId &&
            anchorHeight == other.anchorHeight

    private fun byteFieldsEqual(other: JniVoteCommitResult) =
        vanNullifier.contentEquals(other.vanNullifier) &&
            voteAuthorityNoteNew.contentEquals(other.voteAuthorityNoteNew) &&
            voteCommitment.contentEquals(other.voteCommitment) &&
            proof.contentEquals(other.proof) &&
            sharesHash.contentEquals(other.sharesHash) &&
            rVpk.contentEquals(other.rVpk) &&
            voteAuthSig.contentEquals(other.voteAuthSig)

    private fun listFieldsEqual(other: JniVoteCommitResult) =
        encShares == other.encShares &&
            shareComms.contentDeepEquals(other.shareComms) &&
            sharePayloads == other.sharePayloads

    override fun hashCode(): Int {
        var result = bundleIndex
        result = 31 * result + proposalId
        result = 31 * result + choice
        result = 31 * result + voteRoundId.hashCode()
        result = 31 * result + vanNullifier.contentHashCode()
        result = 31 * result + voteAuthorityNoteNew.contentHashCode()
        result = 31 * result + voteCommitment.contentHashCode()
        result = 31 * result + proof.contentHashCode()
        result = 31 * result + encShares.hashCode()
        result = 31 * result + anchorHeight.hashCode()
        result = 31 * result + sharesHash.contentHashCode()
        result = 31 * result + shareComms.contentDeepHashCode()
        result = 31 * result + rVpk.contentHashCode()
        result = 31 * result + voteAuthSig.contentHashCode()
        result = 31 * result + sharePayloads.hashCode()
        return result
    }
}

/**
 * Wraps a recovered [JniVoteCommitResult] with its confirmed vote-commitment-tree position, as
 * returned by `recoverCommittedVoteNative`.
 */
@Keep
data class JniCommittedVoteRecord(
    val commit: JniVoteCommitResult,
    val vcTreePosition: Long
)

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
 * Typed JNI carrier for a voting hotkey.
 *
 * `generateHotkeyNative` can mint a fresh app-owned hotkey identity, so [storedSecret] must
 * cross JNI here for Android to persist in secure storage; the crate never re-derives it from
 * the wallet seed. [storedSecret] is sensitive and must not be logged.
 */
@Keep
data class JniVotingHotkey(
    val storedSecret: ByteArray,
    val rawAddress: ByteArray,
    val address: String
) {
    override fun toString(): String = "JniVotingHotkey(redacted)"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is JniVotingHotkey) return false
        return storedSecret.contentEquals(other.storedSecret) &&
            rawAddress.contentEquals(other.rawAddress) &&
            address == other.address
    }

    override fun hashCode(): Int {
        var result = storedSecret.contentHashCode()
        result = 31 * result + rawAddress.contentHashCode()
        result = 31 * result + address.hashCode()
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
