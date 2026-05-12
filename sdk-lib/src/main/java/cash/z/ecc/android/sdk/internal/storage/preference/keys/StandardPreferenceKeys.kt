package cash.z.ecc.android.sdk.internal.storage.preference.keys

import cash.z.ecc.android.sdk.internal.storage.preference.model.entry.PreferenceKey
import cash.z.ecc.android.sdk.internal.storage.preference.model.entry.StringPreferenceDefault

internal object StandardPreferenceKeys {
    val SDK_VERSION_OF_LAST_FIX_WITNESSES_CALL =
        StringPreferenceDefault(
            key = PreferenceKey("sdk_version_of_last_fix_witnesses_call"),
            defaultValue = ""
        )
}
