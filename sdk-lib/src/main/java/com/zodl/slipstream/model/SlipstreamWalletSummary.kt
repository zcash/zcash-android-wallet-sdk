package com.zodl.slipstream.model

import androidx.annotation.Keep

/**
 * Balance of one value pool within one account. All values are zatoshi.
 *
 * Constructed by the `slipstream-jni` crate (`POOL_BALANCE_CTOR = "(JJJ)V"`) - field order is the
 * binding contract.
 */
@Keep
data class SlipstreamPoolBalance(
    /** May be spent now: witnesses computable, confirmed to the required depth. */
    val spendableValue: Long,
    /** Shielded change awaiting sufficient confirmations. */
    val changePendingConfirmation: Long,
    /** Remaining received notes: unconfirmed, or witnesses need more scanning. */
    val valuePendingSpendability: Long
) {
    val total: Long get() = spendableValue + changePendingConfirmation + valuePendingSpendability
}

/**
 * Per-account balances. The sum of all fields is the account's total balance. DURING RECOVERY
 * (snapshot.isRecovering) the engine deliberately collapses the per-pool breakdown: the whole
 * recovery-safe net is surfaced as orchard.spendableValue and every other component is zero -
 * render the total, not the pools, in that phase.
 *
 * Constructed by the `slipstream-jni` crate (`ACCOUNT_BALANCE_CTOR =
 * "([BLcom/zodl/slipstream/model/SlipstreamPoolBalance;Lcom/zodl/slipstream/model/SlipstreamPoolBalance;Lcom/zodl/slipstream/model/SlipstreamPoolBalance;J)V"`)
 * - field order is the binding contract. `accountUuid`/`Array` fields make the generated `equals`
 * identity-based for those fields; these are transport objects, not map keys.
 */
@Keep
data class SlipstreamAccountBalance(
    /** 16-byte account UUID, matching accounts.uuid in data.db. */
    val accountUuid: ByteArray,
    val sapling: SlipstreamPoolBalance,
    val orchard: SlipstreamPoolBalance,
    /**
     * FORWARD FIELD: unspent Ironwood (Orchard note-version V3) outputs. Null when the AAR's
     * engine tag predates ironwood support. Presence follows the engine tag the AAR was built
     * from (`FFI_JNI_CONTRACT.md` section 9.2).
     */
    val ironwood: SlipstreamPoolBalance?,
    /** All unspent transparent outputs regardless of confirmations (zero-conf shieldable). */
    val unshielded: Long
)

/**
 * Scan/recovery progress ratio as reported by the upstream wallet backend. When denominator == 0
 * the numerator is a non-progress indicator: 0 = progress unknown, 1 = an error occurred. FOR
 * DISPLAY DIAGNOSTICS ONLY - the blessed UI progress value is snapshot.progressPermille.
 *
 * Constructed by the `slipstream-jni` crate (`SCAN_PROGRESS_CTOR = "(JJ)V"`) - field order is the
 * binding contract.
 */
@Keep
data class SlipstreamScanProgress(
    val numerator: Long,
    val denominator: Long
)

/**
 * Phase-resolving wallet summary (the `walletSummary` native): recovery-safe values while
 * restoring, the upstream summary otherwise. A Kotlin null return from the native (not this
 * class) means "no balance data yet". Never re-derive balances from notes or transactions; never
 * serve a cached balance across the restore phase.
 *
 * Constructed by the `slipstream-jni` crate (`WALLET_SUMMARY_CTOR =
 * "([Lcom/zodl/slipstream/model/SlipstreamAccountBalance;JJLcom/zodl/slipstream/model/SlipstreamScanProgress;Lcom/zodl/slipstream/model/SlipstreamScanProgress;JJLjava/lang/Long;)V"`)
 * - field order is the binding contract.
 */
@Keep
data class SlipstreamWalletSummary(
    val accountBalances: Array<SlipstreamAccountBalance>,
    val chainTipHeight: Long,
    val fullyScannedHeight: Long,
    val scanProgress: SlipstreamScanProgress?,
    val recoveryProgress: SlipstreamScanProgress?,
    val nextSaplingSubtreeIndex: Long,
    val nextOrchardSubtreeIndex: Long,
    /** FORWARD FIELD: null when the AAR's engine tag predates ironwood support. */
    val nextIronwoodSubtreeIndex: Long?
)
