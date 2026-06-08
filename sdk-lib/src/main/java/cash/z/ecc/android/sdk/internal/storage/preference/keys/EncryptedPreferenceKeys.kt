package cash.z.ecc.android.sdk.internal.storage.preference.keys

import cash.z.ecc.android.sdk.internal.storage.preference.model.entry.PreferenceKey
import cash.z.ecc.android.sdk.internal.storage.preference.model.entry.StringPreferenceDefault

internal object EncryptedPreferenceKeys {
    val PENDING_SUBMIT_PLANS =
        StringPreferenceDefault(
            key = PreferenceKey("pending_submit_plans"),
            defaultValue = ""
        )
}
