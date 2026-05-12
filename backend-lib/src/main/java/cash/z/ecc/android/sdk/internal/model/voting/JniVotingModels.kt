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
    val bundleWeights: List<Long> = emptyList()
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
