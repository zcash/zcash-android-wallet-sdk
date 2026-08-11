package com.zodl.slipstream.internal

import cash.z.ecc.android.sdk.ext.ZcashSdk

/**
 * Ports the upstream SDK's private `validateAlias` rule (`Synchronizer.kt:1123-1131`) as a pure
 * predicate rather than calling the private symbol: length in `[ALIAS_MIN_LENGTH,
 * ALIAS_MAX_LENGTH]` and only letters, digits, `_`, or `-`.
 */
internal fun isValidAlias(alias: String): Boolean =
    alias.length in ZcashSdk.ALIAS_MIN_LENGTH..ZcashSdk.ALIAS_MAX_LENGTH &&
        alias.all { it.isLetterOrDigit() || it == '_' || it == '-' }

/** @throws IllegalArgumentException when [alias] fails [isValidAlias] - the upstream `require(...)` posture. */
internal fun validateAlias(alias: String) {
    require(isValidAlias(alias)) {
        "ERROR: Invalid alias ($alias). For security, the alias must be shorter than 100 " +
            "characters and only contain letters, digits, hyphens, and underscores."
    }
}
