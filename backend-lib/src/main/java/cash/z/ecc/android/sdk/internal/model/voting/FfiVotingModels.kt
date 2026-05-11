package cash.z.ecc.android.sdk.internal.model.voting

import androidx.annotation.Keep

// Must match PHASE_* constants in backend-lib/src/main/rust/voting/json.rs.
internal const val FFI_ROUND_PHASE_INITIALIZED = 0
internal const val FFI_ROUND_PHASE_HOTKEY_GENERATED = 1
internal const val FFI_ROUND_PHASE_DELEGATION_CONSTRUCTED = 2
internal const val FFI_ROUND_PHASE_DELEGATION_PROVED = 3
internal const val FFI_ROUND_PHASE_VOTE_READY = 4

@Keep
data class FfiRoundState(
    val roundId: String,
    val phase: Int,
    val snapshotHeight: Long,
    val hotkeyAddress: String?,
    val delegatedWeight: Long?,
    val proofGenerated: Boolean
) {
    val roundPhase = FfiRoundPhase.fromInt(phase)
}

@Keep
enum class FfiRoundPhase(
    val value: Int
) {
    INITIALIZED(FFI_ROUND_PHASE_INITIALIZED),
    HOTKEY_GENERATED(FFI_ROUND_PHASE_HOTKEY_GENERATED),
    DELEGATION_CONSTRUCTED(FFI_ROUND_PHASE_DELEGATION_CONSTRUCTED),
    DELEGATION_PROVED(FFI_ROUND_PHASE_DELEGATION_PROVED),
    VOTE_READY(FFI_ROUND_PHASE_VOTE_READY);

    companion object {
        fun fromInt(value: Int) =
            entries.firstOrNull { it.value == value }
                ?: error("Unknown round phase: $value")
    }
}

data class FfiRoundSummary(
    val roundId: String,
    val phase: Int,
    val snapshotHeight: Long,
    val createdAt: Long
) {
    val roundPhase = FfiRoundPhase.fromInt(phase)
}
