@file:Suppress("ReturnCount")

package com.zodl.slipstream.internal.db

import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.TransactionState

/**
 * Verbatim port of the upstream SDK's internal `TransactionState.new` derivation
 * (`TransactionOverview.kt:91-121`, not callable from this module since the factory itself is
 * `internal fun`). `MIN_CONFIRMATIONS` matches the upstream private constant exactly.
 */
internal const val MIN_CONFIRMATIONS = 10L

internal fun computeTransactionState(
    latestHeight: BlockHeight?,
    minedHeight: BlockHeight?,
    expiryHeight: BlockHeight?,
    isExpiredUnmined: Boolean?
): TransactionState {
    if (isExpiredUnmined == true) return TransactionState.Expired
    val chainTip = latestHeight ?: return TransactionState.Pending
    minedHeight?.let { mined ->
        return if ((chainTip.value + 1 - mined.value) >= MIN_CONFIRMATIONS) {
            TransactionState.Confirmed
        } else {
            TransactionState.Pending
        }
    }
    expiryHeight?.let { expiry ->
        return if (expiry.value == 0L || expiry > chainTip) {
            TransactionState.Pending
        } else {
            TransactionState.Expired
        }
    }
    return TransactionState.Pending // unmined + unknown expiry: will change as we sync
}
