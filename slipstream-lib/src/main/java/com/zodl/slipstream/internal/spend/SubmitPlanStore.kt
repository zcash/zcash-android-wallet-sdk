@file:Suppress("ReturnCount")

package com.zodl.slipstream.internal.spend

import android.content.SharedPreferences
import androidx.core.content.edit
import cash.z.ecc.android.sdk.ext.toHex
import cash.z.ecc.android.sdk.model.FirstClassByteArray
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint

/** `host|port|isSecure` <-> [LightWalletEndpoint] - a plain, dependency-free codec for [SubmitPlanStore]'s values. */
private fun LightWalletEndpoint.encode(): String = "$host|$port|$isSecure"

private fun decodeEndpoint(entry: String): LightWalletEndpoint? {
    val parts = entry.split("|")
    if (parts.size != ENDPOINT_ENTRY_FIELD_COUNT) return null
    val port = parts[1].toIntOrNull() ?: return null
    return LightWalletEndpoint(host = parts[0], port = port, isSecure = parts[2].toBoolean())
}

private const val ENDPOINT_ENTRY_FIELD_COUNT = 3
private const val ENDPOINT_DELIMITER = ";"

/**
 * The adapter's own submit-plan store for R29 (`broadcaster`) - `SdkBroadcaster`'s own
 * `PendingSubmitPlanStore` is `internal` to `sdk-lib`'s own broadcaster package and paired tightly
 * to `OutboundTransactionManager`/`TransactionSubmitter`, so this is a from-scratch, minimal
 * re-implementation of exactly the two facts `KOTLIN_ROSETTA.md` section 3.5 asks for: "create
 * marks awaiting-submission; submit records endpoints for retry" - namespaced `<networkId>_<alias>`
 * like theirs, backed by the caller's own `SharedPreferences` file (never the upstream SDK's own
 * preference files).
 */
internal class SubmitPlanStore(
    private val preferences: SharedPreferences
) {
    fun markAwaitingSubmission(txId: FirstClassByteArray) {
        val key = txId.byteArray.toHex()
        if (!preferences.contains(key)) {
            preferences.edit { putString(key, "") }
        }
    }

    fun recordSubmitEndpoint(
        txId: FirstClassByteArray,
        endpoint: LightWalletEndpoint
    ) {
        val key = txId.byteArray.toHex()
        val updated = (endpointsFor(txId) + endpoint).distinct()
        preferences.edit { putString(key, updated.joinToString(ENDPOINT_DELIMITER) { it.encode() }) }
    }

    fun endpointsFor(txId: FirstClassByteArray): List<LightWalletEndpoint> {
        val stored = preferences.getString(txId.byteArray.toHex(), null).orEmpty()
        if (stored.isBlank()) return emptyList()
        return stored.split(ENDPOINT_DELIMITER).mapNotNull(::decodeEndpoint)
    }
}
