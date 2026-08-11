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

    // Split into per-field-group helpers (rather than one long `&&`-chained expression) to keep
    // `equals`'s own cyclomatic complexity under detekt's threshold; each field group is a
    // small, independently readable comparison.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VotingCommitmentResult) return false
        return scalarFieldsMatch(other) && byteArrayFieldsMatch(other) && collectionFieldsMatch(other)
    }

    private fun scalarFieldsMatch(other: VotingCommitmentResult): Boolean =
        proposalId == other.proposalId &&
            bundleIndex == other.bundleIndex &&
            anchorHeight == other.anchorHeight &&
            voteRoundId == other.voteRoundId

    private fun byteArrayFieldsMatch(other: VotingCommitmentResult): Boolean =
        listOf(
            vanNullifier to other.vanNullifier,
            voteAuthorityNoteNew to other.voteAuthorityNoteNew,
            voteCommitment to other.voteCommitment,
            proof to other.proof,
            sharesHash to other.sharesHash,
            rVpk to other.rVpk,
            alphaV to other.alphaV
        ).all { (a, b) -> a.contentEquals(b) }

    private fun collectionFieldsMatch(other: VotingCommitmentResult): Boolean =
        encShares == other.encShares &&
            shareBlinds.size == other.shareBlinds.size &&
            shareBlinds.zip(other.shareBlinds).all { (a, b) -> a.contentEquals(b) } &&
            shareComms.size == other.shareComms.size &&
            shareComms.zip(other.shareComms).all { (a, b) -> a.contentEquals(b) }

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

    data class Found(
        val txHash: String
    ) : VotingTxHashLookup
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
