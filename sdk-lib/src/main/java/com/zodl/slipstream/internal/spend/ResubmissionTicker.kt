package com.zodl.slipstream.internal.spend

/** The raw SQL for the T8 resubmission scan - ports the upstream `SELECTION_TRX_RESUBMISSION` selection verbatim. */
internal object ResubmissionQuery {
    const val SQL =
        "SELECT tx.txid, tx.raw FROM v_transactions AS tx WHERE tx.mined_height IS NULL " +
            "AND tx.expiry_height > ? AND tx.account_balance_delta < 0"
}

/** One row the resubmission scan considers. */
internal data class ResubmissionCandidate(
    val txId: ByteArray,
    val raw: ByteArray
)

/**
 * Pure twin of [ResubmissionQuery.SQL]'s `WHERE` clause - unmined, unexpired (relative to
 * [chainTip]), and a send (negative account balance delta). Kept as its own function so the
 * eligibility rule is unit-testable without a SQLite connection (`SDK_ADAPTER_PLAN.md` T8: "the
 * eligibility predicate is pure -> JUnit-5 it"), independently of the actual query, which applies
 * the identical condition in SQL.
 */
internal fun isEligibleForResubmission(
    minedHeight: Long?,
    expiryHeight: Long,
    chainTip: Long,
    accountBalanceDelta: Long
): Boolean = minedHeight == null && expiryHeight > chainTip && accountBalanceDelta < 0

/**
 * `KOTLIN_ROSETTA.md` section 3.7: upstream resubmits unmined-within-expiry sends from inside the
 * processor's sync loop, which this adapter never constructs. Re-homes the *tick*: every
 * [RESUBMIT_EVERY] poll ticks while `SYNCED`, resubmit every eligible candidate and poke once.
 * iOS slipstream ships no background resubmission today - Android intentionally exceeds it because
 * the product depends on it (DECISIONS.md D6).
 */
internal class ResubmissionTicker(
    private val findCandidates: suspend (chainTip: Long) -> List<ResubmissionCandidate>,
    private val resubmit: suspend (ResubmissionCandidate) -> Unit,
    private val notifyTxChange: suspend () -> Unit
) {
    private var ticksSinceLastRun = 0

    suspend fun onTick(
        isSynced: Boolean,
        chainTip: Long
    ) {
        if (!isSynced) {
            ticksSinceLastRun = 0
            return
        }
        ticksSinceLastRun++
        if (ticksSinceLastRun < RESUBMIT_EVERY) return
        ticksSinceLastRun = 0

        val candidates = findCandidates(chainTip)
        if (candidates.isEmpty()) return
        candidates.forEach { resubmit(it) }
        notifyTxChange()
    }

    companion object {
        /** ~5 minutes at the 2 s tick cadence (`SlipstreamEngine.POLL_INTERVAL_MS`). */
        const val RESUBMIT_EVERY = 150
    }
}
