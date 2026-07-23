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
 * Serves as cross layer (Kotlin, Rust) communication class. The live, persisted status of one
 * committed transfer transaction — [id] is its real, stable `MigrationTxId` (same format/value as
 * `JniTransferProposal.id`), NOT its pool-crossing/funding-note index. ZIP 318 deliberately shuffles
 * crossing order away from the broadcast-height order the app displays transfers in, so [id] is the
 * only key that reliably correlates back to a specific `MigrationPlan.transfers` entry (via that
 * entry's own `id` field).
 */
@Keep
class JniMigrationTransferState(
    val id: String,
    val isSent: Boolean,
    val scheduledHeight: Long
)

/**
 * Serves as cross layer (Kotlin, Rust) communication class. The live schedule/status of every
 * committed transfer transaction, read directly from the persisted migration store — unlike
 * [JniMigrationSchedule] (a one-time proposal snapshot), this reflects whatever the store's
 * `scheduled_height`/state columns hold right now, including any later reschedule.
 */
@Keep
class JniMigrationTransferStates(
    val transfers: Array<JniMigrationTransferState>,
    val tipHeight: Long
)

/**
 * Serves as cross layer (Kotlin, Rust) communication class. One transfer's unsigned, proven (self-
 * funding transfers are the exception: not yet proven, per the sign-now/prove-later scheme) PCZT,
 * staged in the engine and awaiting an external signer (e.g. Keystone).
 */
@Keep
class JniUnsignedTransferPczt(
    val id: String,
    val pcztBytes: ByteArray
)

/**
 * Serves as cross layer (Kotlin, Rust) communication class. The result of feeding one scanned QR
 * frame to the Keystone batch-signing UR decoder — see `migration_keystone::decode_sign_batch_part`.
 *
 * [firmwareVersion] is the signing device's raw `[major, minor, build]` firmware version, non-null
 * once [complete] — read directly from the `zcash-batch-sig-result` envelope's own field, not
 * scanned out of any signed PCZT (the batch response is signatures-only and never echoes PCZT
 * bytes back, so a PCZT-proprietary-field scan always comes back empty for this flow).
 */
@Keep
class JniKeystoneBatchDecodeResult(
    val complete: Boolean,
    val progress: Int,
    val data: ByteArray?,
    val firmwareVersion: ByteArray?
)

/**
 * Serves as cross layer (Kotlin, Rust) communication class. The signed-but-unproven PCZT bytes
 * produced by applying a Keystone batch-signing response back to the retained unsigned PCZTs —
 * see `migration_keystone::apply_batch_signatures`. `transferSignedPczts` is index-aligned with
 * whatever order the caller passed the unsigned transfer PCZTs in.
 */
@Keep
class JniKeystoneBatchSignedPczts(
    val splitSignedPczt: ByteArray?,
    val transferSignedPczts: Array<ByteArray>
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
