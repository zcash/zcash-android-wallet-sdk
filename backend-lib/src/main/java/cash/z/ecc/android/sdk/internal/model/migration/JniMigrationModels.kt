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
 *
 * [proposalHandle] is the opaque identifier of the Rust-side cached migration plan this proposal
 * was rendered from. The plan's details never cross the JNI boundary inward: commit calls pass
 * this handle back, and the Rust side refuses to sign any plan other than the one it identifies
 * (erroring if a later propose/prepare call superseded it).
 */
@Keep
class JniNoteSplitProposal(
    val outputValuesZatoshi: LongArray,
    val feeZatoshi: Long,
    val proposalHandle: Long
)

/**
 * Serves as cross layer (Kotlin, Rust) communication class.
 */
@Keep
class JniPreparedTransfer(
    val id: Long,
    val txid: ByteArray,
    val pcztBytes: ByteArray
) {
    init {
        require(id.isInUIntRange()) {
            "Transfer id $id is outside of allowed UInt range"
        }
    }
}

/**
 * Serves as cross layer (Kotlin, Rust) communication class.
 *
 * Tri-state result of the next-due-transfer query.
 *   status 0 = NOTHING_DUE   — migration terminal, no state, or nothing due yet
 *   status 1 = READY          — a proven transfer is ready to broadcast ([prepared] is non-null)
 *   status 2 = AWAITING_PROOF — a transfer is due but still needs proving ([awaitingProofTransferId] non-null)
 */
@Keep
class JniDueTransferResult(
    /** 0 = NOTHING_DUE, 1 = READY, 2 = AWAITING_PROOF */
    val status: Int,
    /** Non-null when status == 2 (the due-but-unproven transfer). */
    val awaitingProofTransferId: Long?,
    /** Non-null when status == 1. */
    val prepared: JniPreparedTransfer?,
) {
    init {
        awaitingProofTransferId?.let {
            require(it.isInUIntRange()) {
                "Transfer id $it is outside of allowed UInt range"
            }
        }
    }
}

/**
 * Serves as cross layer (Kotlin, Rust) communication class.
 */
@Keep
class JniTransferProposal(
    val id: Long,
    val amountZatoshi: Long,
    val anchorHeight: Long,
    val nextExecutableAfterHeight: Long,
    val expiryHeight: Long
) {
    init {
        require(id.isInUIntRange()) {
            "Transfer id $id is outside of allowed UInt range"
        }
    }
}

/**
 * Serves as cross layer (Kotlin, Rust) communication class. One note-split (preparation)
 * transaction in the migration schedule: its stable [id], which [layer] and [index] within that
 * layer it occupies, the [broadcastHeight] at which to broadcast it, and the ids of earlier
 * preparation transactions whose outputs it spends ([dependsOn], empty for layer-0 transactions).
 */
@Keep
class JniPreparationStep(
    val id: Long,
    val layer: Int,
    val index: Int,
    val broadcastHeight: Long,
    val dependsOn: LongArray,
)

/**
 * Serves as cross layer (Kotlin, Rust) communication class.
 *
 * [proposalHandle] identifies the Rust-side cached migration plan this schedule was rendered
 * from — see [JniNoteSplitProposal.proposalHandle] for the contract.
 */
@Keep
class JniMigrationSchedule(
    val transfers: Array<JniTransferProposal>,
    val preparations: Array<JniPreparationStep>,
    val estimatedDurationHours: Int,
    val proposalHandle: Long,
)

/**
 * Serves as cross layer (Kotlin, Rust) communication class. The live, persisted status of one
 * committed migration transaction (transfer or preparation) — [id] is its real, stable
 * `MigrationTransferId` (same format/value as `JniTransferProposal.id`), NOT its pool-crossing/
 * funding-note index. ZIP 318 deliberately shuffles crossing order away from the broadcast-height
 * order the app displays transfers in, so [id] is the only key that reliably correlates back to a
 * specific `MigrationPlan.transfers` entry (via that entry's own `id` field).
 *
 * [isTransfer] is false for preparation (note-split layer) transactions — display-facing
 * consumers filter on it or correlate by id (prep ids match no display row). [isProved] is true
 * once the engine holds a proof (`Proved`/`Broadcast`/`Mined`). [anchorBoundaryHeight] is the
 * committed ZIP 318 bucket boundary the transaction proves against, or `-1` when the engine
 * committed none (preparations prove at their natural anchor).
 */
@Keep
class JniMigrationTransferState(
    val id: Long,
    val isTransfer: Boolean,
    val isSent: Boolean,
    val isProved: Boolean,
    val scheduledHeight: Long,
    val anchorBoundaryHeight: Long,
    /** Actionable RIGHT NOW ([action] says how) per the engine's `transaction_statuses`. */
    val ready: Boolean,
    /** 0 = none, 1 = prove, 2 = broadcast. */
    val action: Int,
    /**
     * Why it is waiting: 0 none, 1 dependencies, 2 schedule, 3 anchor boundary, 4 signature,
     * 5 expired, 6 unprovable anchor (synthetic — the backend guard veto; see the driver-surface
     * note in migration.rs, TODO(remove) once the engine surfaces it natively).
     */
    val blocker: Int,
    /** The engine-persisted crossing value (`transfer_crossing_value`); -1 for preparations. */
    val amountZatoshi: Long,
    /** Preparation layer/index within the note-split tree; -1/-1 for transfers. */
    val prepLayer: Int,
    val prepIndex: Int,
    /** Ids of the transactions that must mine before this one can build/broadcast. */
    val dependsOn: LongArray,
    /** ZIP 203 expiry height; 0 = never expires. */
    val expiryHeight: Long,
    /** Height this transaction mined at; -1 while unmined. */
    val minedHeight: Long,
) {
    init {
        require(id.isInUIntRange()) {
            "Transfer id $id is outside of allowed UInt range"
        }
    }
}

/**
 * Serves as cross layer (Kotlin, Rust) communication class. The live schedule/status of every
 * committed migration transaction (transfers AND preparations), read directly from the persisted
 * migration store — unlike [JniMigrationSchedule] (a one-time proposal snapshot), this reflects
 * whatever the engine's store holds right now. The engine is the single source of truth for the
 * plan; this type only surfaces it.
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
    val id: Long,
    val pcztBytes: ByteArray
) {
    init {
        require(id.isInUIntRange()) {
            "Transfer id $id is outside of allowed UInt range"
        }
    }
}

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
        val transferId: Long
    ) : JniAttentionReason() {
        init {
            require(transferId.isInUIntRange()) {
                "Transfer id $transferId is outside of allowed UInt range"
            }
        }
    }

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
