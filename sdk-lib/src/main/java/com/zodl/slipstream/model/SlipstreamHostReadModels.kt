package com.zodl.slipstream.model

import androidx.annotation.Keep

/**
 * One `v_transactions` row - the production visible-transactions read (the `listTransactions`
 * native; `FFI_JNI_CONTRACT.md` section 7.1/9.3), replacing the JSON `readQuery` lane for this
 * read. `isExpiredUnmined` stays `Long?` (not `Boolean?`) to preserve the SQL NULL-ness through
 * the boundary - map it with `?.let { it != 0L }`. (`txId`/`raw` are `ByteArray`, so the
 * generated `equals`/`hashCode` are identity-based for those fields; this is a transport object,
 * not a map key.)
 *
 * Constructed by the `slipstream-jni` crate (`TX_ROW_CTOR =
 * "([BLjava/lang/Long;Ljava/lang/Long;Ljava/lang/Long;[BJJJLjava/lang/Long;ZIIILjava/lang/Long;ZLjava/lang/Long;)V"`)
 * - field order is the binding contract.
 */
@Keep
data class SlipstreamTransactionRow(
    val txId: ByteArray,
    val minedHeight: Long?,
    val expiryHeight: Long?,
    val txIndex: Long?,
    val raw: ByteArray?,
    val accountBalanceDelta: Long,
    val totalSpent: Long,
    val totalReceived: Long,
    val feePaid: Long?,
    val hasChange: Boolean,
    val sentNoteCount: Int,
    val receivedNoteCount: Int,
    val memoCount: Int,
    val blockTime: Long?,
    val isShielding: Boolean,
    val isExpiredUnmined: Long?
)

/**
 * Raw transaction bytes + expiry height for one txid (the `getTransactionRaw` native) - a Kotlin
 * `null` return (not this class) means the transaction is not stored.
 *
 * Constructed by the `slipstream-jni` crate (`RAW_TX_CTOR = "([BJ)V"`) - field order is the
 * binding contract.
 */
@Keep
data class SlipstreamRawTransaction(
    val raw: ByteArray,
    val expiryHeight: Long
)

/**
 * One non-change `v_tx_outputs` row (the `listTransactionOutputs` native) - either scoped to one
 * txid or, when the native's `txid` argument is `null`, every account's rows.
 *
 * Constructed by the `slipstream-jni` crate (`TX_OUTPUT_ROW_CTOR =
 * "([BIILjava/lang/String;[B)V"`) - field order is the binding contract.
 */
@Keep
data class SlipstreamTxOutputRow(
    val txId: ByteArray,
    val outputIndex: Int,
    val outputPool: Int,
    val toAddress: String?,
    val toAccountUuid: ByteArray?
)

/**
 * One resubmission-candidate row (the `listResubmissionCandidates` native): unmined, unexpired,
 * outgoing transactions.
 *
 * Constructed by the `slipstream-jni` crate (`RESUBMISSION_ROW_CTOR = "([B[B)V"`) - field order
 * is the binding contract.
 */
@Keep
data class SlipstreamResubmissionRow(
    val txId: ByteArray,
    val raw: ByteArray
)
