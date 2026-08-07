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
 * Constructed by the `slipstream-jni` crate's `TX_ROW_CTOR`; field order is the JNI binding
 * contract (`FFI_JNI_CONTRACT.md` §4.2) - do not reorder.
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
    val isExpiredUnmined: Long?,
    /** How the row classifies against ZIP 318 — `Zip318Kind.new(this)` decodes the raw code. */
    val zip318Kind: Int
)

/**
 * Raw transaction bytes + expiry height for one txid (the `getTransactionRaw` native) - a Kotlin
 * `null` return (not this class) means the transaction is not stored.
 *
 * Constructed by the `slipstream-jni` crate's `RAW_TX_CTOR`; field order is the JNI binding
 * contract (`FFI_JNI_CONTRACT.md` §4.2) - do not reorder.
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
 * Constructed by the `slipstream-jni` crate's `TX_OUTPUT_ROW_CTOR`; field order is the JNI
 * binding contract (`FFI_JNI_CONTRACT.md` §4.2) - do not reorder.
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
 * Constructed by the `slipstream-jni` crate's `RESUBMISSION_ROW_CTOR`; field order is the JNI
 * binding contract (`FFI_JNI_CONTRACT.md` §4.2) - do not reorder.
 */
@Keep
data class SlipstreamResubmissionRow(
    val txId: ByteArray,
    val raw: ByteArray
)
