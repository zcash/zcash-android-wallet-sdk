package com.zodl.slipstream.internal

import com.zodl.slipstream.model.SlipstreamSnapshot

/** Outcome of one [PollGate.reduce] call: whether to re-query transactions, plus the next gate. */
internal data class PollDecision(
    val requeryTransactions: Boolean,
    val next: PollGate
)

/**
 * `HOSTING.md` section 5.4 verbatim: re-query when `txSetVersion != lastSeen` OR the recovery
 * filter flipped scope; remember both. The version counter is snapshot-carried and loss-proof -
 * no event-tag or row-count heuristic may sit beside it (DECISIONS.md D11). This is the ONLY
 * place the transaction re-query rule lives.
 */
internal data class PollGate(
    val lastTxSetVersion: Long?,
    val lastRecovering: Boolean?
) {
    fun reduce(snapshot: SlipstreamSnapshot): PollDecision {
        val versionChanged = lastTxSetVersion == null || snapshot.txSetVersion != lastTxSetVersion
        val scopeFlipped = lastRecovering == null || snapshot.isRecovering != lastRecovering
        return PollDecision(
            requeryTransactions = versionChanged || scopeFlipped,
            next = PollGate(snapshot.txSetVersion, snapshot.isRecovering)
        )
    }

    companion object {
        val INITIAL = PollGate(null, null)
    }
}
