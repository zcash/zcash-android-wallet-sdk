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
 * Constructor field order is the JNI binding contract (`FFI_JNI_CONTRACT.md` section 4.2/4.3):
 * changing it is a binding break. Constructed field-by-field by the `slipstream-jni` crate
 * (`jni/src/lib.rs`, `SNAPSHOT_CTOR = "(JJJJJIJZJZIJZJ)V"`) - never edit this order in isolation.
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
    /**
     * True while queued scan work remains below the account's recover_until height - the
     * restore/backfill window in which naive balance and history reads mislead. Engine-derived
     * from the database (survives kills, correct from open) and FORCE-RELEASED on terminal
     * states - a dead pass can never wedge a "Restoring" UI. Keys the visibility rule
     * (`FFI_JNI_CONTRACT.md` section 7.1). NEVER persist a host-side restoring flag.
     */
    val isRecovering: Boolean,
    /**
     * THE progress value, 0..1000. Never regresses while the handle lives; a synced wallet's
     * catch-up starts near 1000 (no 0% flash); an interrupted restore resumes at its true
     * position; scope expansion re-baselines to a genuine climb. state == 3 forces 1000.
     * Render it; NEVER compute height ratios.
     */
    val progressPermille: Int,
    /**
     * Seconds since any counter moved while state == 1; 0 otherwise. The engine supplies the
     * fact; the host owns the policy (log loudly at >= 120 s and deliberately never auto-restart).
     */
    val stalledSeconds: Long,
    /**
     * True once the CURRENT run has refreshed the wallet's chain tip (or completed a pass);
     * survives stop-start hops shorter than 120 s. While false, display spendable funds as
     * pending spendability (shift spendable -> pending in the balance rendering); never
     * applied while isRecovering is true (recovery values are safe by construction).
     */
    val tipFresh: Boolean,
    /**
     * Monotonic version of the stored transaction set. Bumps exactly when the set changes
     * (scan/enhance, mined/expired, 0-conf mempool hit, restore linkage resolving, or the
     * host's own notifyTxChange poke). HOST RULE: version moved since last poll OR the
     * recovery filter flipped scope -> re-query transactions and publish. Snapshot-carried and
     * cumulative - it cannot be lost the way queue events can; build NO event-sniffing
     * heuristics on top.
     */
    val txSetVersion: Long
)
