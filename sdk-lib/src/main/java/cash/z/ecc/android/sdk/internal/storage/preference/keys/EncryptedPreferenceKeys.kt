package cash.z.ecc.android.sdk.internal.storage.preference.keys

import cash.z.ecc.android.sdk.internal.storage.preference.model.entry.PreferenceKey
import cash.z.ecc.android.sdk.internal.storage.preference.model.entry.StringPreferenceDefault

internal object EncryptedPreferenceKeys {
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
}
