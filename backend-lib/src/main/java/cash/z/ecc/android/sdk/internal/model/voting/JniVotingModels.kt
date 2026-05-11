package cash.z.ecc.android.sdk.internal.model.voting

import androidx.annotation.Keep
import cash.z.ecc.android.sdk.internal.jni.JNI_HOTKEY_PUBLIC_KEY_BYTES_SIZE

@ConsistentCopyVisibility
data class HotkeyPublicKey internal constructor(
    val value: ByteArray
) {
    init {
        require(value.size == JNI_HOTKEY_PUBLIC_KEY_BYTES_SIZE) {
            "HotkeyPublicKey must be $JNI_HOTKEY_PUBLIC_KEY_BYTES_SIZE bytes, got ${value.size}"
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HotkeyPublicKey) return false
        return value.contentEquals(other.value)
    }

    override fun hashCode(): Int = value.contentHashCode()

    override fun toString(): String = "HotkeyPublicKey(${value.toHexString()})"

    companion object {
        internal fun new(bytes: ByteArray) = HotkeyPublicKey(bytes)
    }
}

private fun ByteArray.toHexString() = joinToString("") { "%02x".format(it) }

@Keep
@ConsistentCopyVisibility
data class JniVotingHotkey internal constructor(
    val publicKey: HotkeyPublicKey,
    val address: String
) {
    internal constructor(pk: ByteArray, addr: String) :
        this(HotkeyPublicKey.new(pk), addr)
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
    val nfSigned: ByteArray,
    val cmxNew: ByteArray,
    val govComm: ByteArray,
    val govNullifiers: List<ByteArray>,
    val alpha: ByteArray,
    val voteRoundId: String
) {
    internal constructor(
        proof: ByteArray,
        rk: ByteArray,
        spendAuthSig: ByteArray,
        sighash: ByteArray,
        nfSigned: ByteArray,
        cmxNew: ByteArray,
        govComm: ByteArray,
        govNullifiers: Array<ByteArray>,
        alpha: ByteArray,
        voteRoundId: String
    ) : this(
        proof = proof,
        rk = rk,
        spendAuthSig = spendAuthSig,
        sighash = sighash,
        nfSigned = nfSigned,
        cmxNew = cmxNew,
        govComm = govComm,
        govNullifiers = govNullifiers.toList(),
        alpha = alpha,
        voteRoundId = voteRoundId
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is JniDelegationSubmissionResult) return false
        return proof.contentEquals(other.proof) &&
            rk.contentEquals(other.rk) &&
            spendAuthSig.contentEquals(other.spendAuthSig) &&
            sighash.contentEquals(other.sighash) &&
            nfSigned.contentEquals(other.nfSigned) &&
            cmxNew.contentEquals(other.cmxNew) &&
            govComm.contentEquals(other.govComm) &&
            govNullifiers.contentDeepEquals(other.govNullifiers) &&
            alpha.contentEquals(other.alpha) &&
            voteRoundId == other.voteRoundId
    }

    override fun hashCode(): Int {
        var result = proof.contentHashCode()
        result = 31 * result + rk.contentHashCode()
        result = 31 * result + spendAuthSig.contentHashCode()
        result = 31 * result + sighash.contentHashCode()
        result = 31 * result + nfSigned.contentHashCode()
        result = 31 * result + cmxNew.contentHashCode()
        result = 31 * result + govComm.contentHashCode()
        result = 31 * result + govNullifiers.contentDeepHashCode()
        result = 31 * result + alpha.contentHashCode()
        result = 31 * result + voteRoundId.hashCode()
        return result
    }
}

private fun List<ByteArray>.contentDeepEquals(other: List<ByteArray>): Boolean =
    size == other.size && zip(other).all { (left, right) -> left.contentEquals(right) }

private fun List<ByteArray>.contentDeepHashCode(): Int =
    toTypedArray().contentDeepHashCode()
