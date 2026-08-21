package cash.z.ecc.android.sdk.internal.transaction

import cash.z.ecc.android.sdk.internal.model.EncodedTransaction
import cash.z.ecc.android.sdk.model.CreatedTransaction

internal fun EncodedTransaction.toCreatedTransaction() =
    CreatedTransaction(
        txId = txId,
        raw = raw,
        expiryHeight = expiryHeight
    )
