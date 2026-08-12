package cash.z.ecc.android.sdk.internal.storage.preference.keys

import cash.z.ecc.android.sdk.internal.storage.preference.model.entry.PreferenceKey
import cash.z.ecc.android.sdk.internal.storage.preference.model.entry.StringPreferenceDefault

internal object EncryptedPreferenceKeys {
    val PENDING_SUBMIT_PLANS =
        StringPreferenceDefault(
            key = PreferenceKey("pending_submit_plans"),
            defaultValue = ""
        )

    /**
     * Epoch-millis string: when sync may resume after a migration transfer was broadcast via the
     * immediate ("send now") path. Empty means no buffer is active. See
     * [cash.z.ecc.android.sdk.OrchardMigrationSdk.isSyncBlocked]'s doc for why this lives here
     * rather than in the migration engine itself (a network/timing-privacy technique layered on
     * top of it, not part of it).
     */
    val MIGRATION_SYNC_RESUME_AT =
        StringPreferenceDefault(
            key = PreferenceKey("migration_sync_resume_at"),
            defaultValue = ""
        )

    /**
     * Epoch-seconds expiry: the point past which a migration broadcast is no longer considered
     * in-flight. Written to `now + 120s` immediately before calling [broadcast], cleared (written
     * as "0") right after [OrchardMigrationSdkImpl.executeNextPendingTransfer] records the
     * transfer result. A stale mark (e.g. from a crash between write and clear) self-expires in
     * at most 120 seconds — [isBroadcastInFlight] treats any value ≤ now as expired. Used by
     * [isSyncBlockedNow] to gate the sync engine during the critical broadcast window.
     */
    val MIGRATION_BROADCAST_IN_FLIGHT_UNTIL =
        StringPreferenceDefault(
            key = PreferenceKey("migration_broadcast_in_flight_until"),
            defaultValue = ""
        )

    /**
     * Epoch-seconds stamp: when [isSyncBlockedNow] first saw a transfer ready to broadcast without
     * interruption. Unlike the other two migration keys, the readiness this bounds cannot expire on
     * its own — a transfer stays `STEP_BROADCAST`-ready until a driver actually broadcasts it, and
     * the plan's own staleness is measured against the SCANNED tip, which cannot advance while the
     * sync this blocks is paused. The stamp turns that unbounded state into a capped one: cleared
     * whenever readiness goes away, re-armed by every live
     * [OrchardMigrationSdkImpl.executeNextPendingTransfer] attempt, and expired after
     * [OrchardMigrationSdkImpl.READY_TO_BROADCAST_BLOCK_CAP_MULTIPLIER] privacy buffers, so only a
     * dead migration driver ever reaches the cap.
     */
    val MIGRATION_SYNC_BLOCK_READY_SINCE =
        StringPreferenceDefault(
            key = PreferenceKey("migration_sync_block_ready_since"),
            defaultValue = ""
        )
}
