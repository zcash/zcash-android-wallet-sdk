package com.zodl.slipstream.model

import androidx.annotation.Keep

/**
 * One event from the engine's 64-slot ring. Drain every tick; treat events as DOORBELLS, not
 * payloads - state and progress come from the snapshot, data comes from SQL. A minimal host may
 * ignore every tag (state == 2 covers errors, txSetVersion covers tag 5) but must still drain the
 * ring. Unknown tags MUST be ignored, never treated as errors.
 *
 * Constructed by the `slipstream-jni` crate (`EVENT_CTOR = "(IJ)V"`) - field order is the binding
 * contract.
 */
@Keep
data class SlipstreamEvent(
    val tag: Int,
    val value: Long
) {
    companion object {
        /** Pass started. value = 0. Droppable on ring overflow. */
        const val TAG_SYNC_STARTED: Int = 1

        /** Pacing progress. value = scanned blocks. Droppable on ring overflow. */
        const val TAG_SYNC_PROGRESS: Int = 2

        /** One pass finished. value = transactions stored. Survives overflow. */
        const val TAG_SYNC_DONE: Int = 3

        /**
         * Pass failed. value = 1: pass failed after the engine's internal transient retries;
         * value = 2: task panicked (supervisor-converted). Survives overflow. Pairs with
         * snapshot.state == 2; host policy = offer retry (call start again).
         */
        const val TAG_SYNC_ERROR: Int = 4

        /** Stored transaction set changed. value = 0. Survives overflow. */
        const val TAG_FOUND_TRANSACTIONS: Int = 5
    }
}
