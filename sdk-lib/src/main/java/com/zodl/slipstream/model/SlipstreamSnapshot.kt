package com.zodl.slipstream.model

import androidx.annotation.Keep

/**
 * Point-in-time snapshot of the Slipstream engine. Poll it on a steady 1-2 s tick.
 * TRUTHFUL FROM OPEN: before the first network byte the recovery flag, progress,
 * chain tip, and spendable hint are seeded from the persisted wallet - a relaunch
 * mid-restore reports "restoring, at 34%" on the very first poll. Hosts MUST NOT
 * compensate (no warm-up caches, no hold-last-value layers, no first-N-seconds
 * special cases). THE ENGINE'S NUMBERS ARE ALREADY CORRECT AT EVERY PHASE - render
 * them; never re-derive them.
 *
 * Constructed field-by-field by the `slipstream-jni` crate's `SNAPSHOT_CTOR`. Field order is
 * the JNI binding contract (`FFI_JNI_CONTRACT.md` §4.2) - do not reorder.
 */
@Keep
data class SlipstreamSnapshot(
    /** Chain tip as known to the wallet: persisted tip from open, live once syncing. 0 = never fetched. */
    val chainTip: Long,
    /** Compact blocks fetched in the current/last sync pass. */
    val fetchedBlocks: Long,
    /** Compact blocks scanned in the current/last sync pass. */
    val scannedBlocks: Long,
    /** Transactions enhanced (full data + memos fetched); monotonic per handle. */
    val enhancedTxs: Long,
    /** End height of the block range currently being processed. */
    val currentRangeEnd: Long,
    /** Sync state: 0 = idle, 1 = syncing, 2 = error, 3 = done (following the tip). */
    val state: Int,
    /** Total blocks in the current pass - the denominator the engine uses; not for host math. */
    val passTotalBlocks: Long,
    /**
     * True once the recent (chain-tip) range has scanned - "spend before sync": spendable
     * notes are discovered early, before historic ranges finish. Latches true within a pass.
     */
    val spendableHint: Boolean,
    /** Suggested ranges fully scanned+enhanced this pass; monotonic per handle. */
    val rangesCompleted: Long,
    /** True while queued scan work remains below the account's recover_until height (the restore/backfill window); keys the visibility rule (`FFI_JNI_CONTRACT.md` section 7.1). */
    val isRecovering: Boolean,
    /** THE progress value, 0..1000; never regresses while the handle lives. state == 3 forces 1000. */
    val progressPermille: Int,
    /**
     * Seconds since any counter moved while state == 1; 0 otherwise. The engine supplies the
     * fact; the host owns the policy (log loudly at >= 120 s and deliberately never auto-restart).
     */
    val stalledSeconds: Long,
    /** True once the current run has refreshed the wallet's chain tip (or completed a pass); while false, display spendable funds as pending spendability (except when isRecovering is true). */
    val tipFresh: Boolean,
    /** Monotonic version of the stored transaction set; bumps whenever it changes. Re-query and publish transactions when this moves since the last poll, or when the recovery filter flips scope. */
    val txSetVersion: Long
)
