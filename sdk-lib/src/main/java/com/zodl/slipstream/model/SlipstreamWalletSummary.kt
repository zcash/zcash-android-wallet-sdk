package com.zodl.slipstream.model

import androidx.annotation.Keep

/**
 * Balance of one value pool within one account. All values are zatoshi.
 *
 * Constructed by the `slipstream-jni` crate's `POOL_BALANCE_CTOR`; field order is the JNI
 * binding contract (`FFI_JNI_CONTRACT.md` §4.2) - do not reorder.
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
 * Constructed by the `slipstream-jni` crate's `ACCOUNT_BALANCE_CTOR`; field order is the JNI
 * binding contract (`FFI_JNI_CONTRACT.md` §4.2) - do not reorder. `accountUuid`/`Array` fields
 * make the generated `equals` identity-based for those fields; these are transport objects, not
 * map keys.
 */
@Keep
data class SlipstreamAccountBalance(
    /** 16-byte account UUID, matching accounts.uuid in data.db. */
    val accountUuid: ByteArray,
    val sapling: SlipstreamPoolBalance,
    val orchard: SlipstreamPoolBalance,
    /** Unspent Ironwood (Orchard note-version V3) outputs; zero when the account holds none. */
    val ironwood: SlipstreamPoolBalance,
    /** All unspent transparent outputs regardless of confirmations (zero-conf shieldable). */
    val unshielded: Long
)

/**
 * Scan/recovery progress ratio as reported by the upstream wallet backend. When denominator == 0
 * the numerator is a non-progress indicator: 0 = progress unknown, 1 = an error occurred. FOR
 * DISPLAY DIAGNOSTICS ONLY - the blessed UI progress value is snapshot.progressPermille.
 *
 * Constructed by the `slipstream-jni` crate's `SCAN_PROGRESS_CTOR`; field order is the JNI
 * binding contract (`FFI_JNI_CONTRACT.md` §4.2) - do not reorder.
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
 * Constructed by the `slipstream-jni` crate's `WALLET_SUMMARY_CTOR`; field order is the JNI
 * binding contract (`FFI_JNI_CONTRACT.md` §4.2) - do not reorder.
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
    val nextIronwoodSubtreeIndex: Long
)
