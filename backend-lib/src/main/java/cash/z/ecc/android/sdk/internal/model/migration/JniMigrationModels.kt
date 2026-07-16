package cash.z.ecc.android.sdk.internal.model.migration

import androidx.annotation.Keep
import cash.z.ecc.android.sdk.internal.ext.isInUIntRange

/**
 * Serves as cross layer (Kotlin, Rust) communication class.
 */
@Keep
class JniMigrationProgress(
    val completedTransfers: Int,
    val totalTransfers: Int,
    val remainingOrchardValueZatoshi: Long,
    val nextTransferReadyAtHeight: Long
) {
    init {
        if (nextTransferReadyAtHeight != -1L) {
            require(nextTransferReadyAtHeight.isInUIntRange()) {
                "Height $nextTransferReadyAtHeight is outside of allowed UInt range and is not -1"
            }
        }
    }
}

/**
 * Serves as cross layer (Kotlin, Rust) communication class.
 */
@Keep
class JniNoteSplitProposal(
    val outputValuesZatoshi: LongArray,
    val feeZatoshi: Long
)

/**
 * Serves as cross layer (Kotlin, Rust) communication class.
 */
@Keep
class JniPreparedTransfer(
    val id: String,
    val txid: ByteArray,
    val pcztBytes: ByteArray
)

/**
 * Serves as cross layer (Kotlin, Rust) communication class.
 */
@Keep
class JniTransferProposal(
    val id: String,
    val amountZatoshi: Long,
    val anchorHeight: Long,
    val nextExecutableAfterHeight: Long,
    val expiryHeight: Long
)

/**
 * Serves as cross layer (Kotlin, Rust) communication class.
 */
@Keep
class JniMigrationSchedule(
    val transfers: Array<JniTransferProposal>,
    val estimatedDurationHours: Int
)

/**
 * Serves as cross layer (Kotlin, Rust) communication class.
 */
@Keep
sealed class JniAttentionReason {
    @Keep
    class InvalidTransfer(
        val transferId: String
    ) : JniAttentionReason()

    @Keep
    class TransferExpired : JniAttentionReason()

    @Keep
    class SyncRequiredBeforeNext : JniAttentionReason()
}

/**
 * Serves as cross layer (Kotlin, Rust) communication class.
 */
@Keep
sealed class JniMigrationState {
    @Keep
    class NotStarted : JniMigrationState()

    @Keep
    class SplitPendingConfirmation : JniMigrationState()

    @Keep
    class ReadyToPropose : JniMigrationState()

    @Keep
    class InProgress(
        val progress: JniMigrationProgress
    ) : JniMigrationState()

    @Keep
    class RequiresAttention(
        val reason: JniAttentionReason
    ) : JniMigrationState()

    @Keep
    class Complete : JniMigrationState()
}
