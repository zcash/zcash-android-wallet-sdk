package cash.z.ecc.android.sdk.fixture

import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.FirstClassByteArray
import cash.z.ecc.android.sdk.model.TransactionId
import cash.z.ecc.android.sdk.model.TransactionOverview
import cash.z.ecc.android.sdk.model.TransactionState
import cash.z.ecc.android.sdk.model.Zatoshi

@Suppress("MagicNumber")
object TransactionOverviewFixture {
    const val ID: Long = 1
    val TX_ID: TransactionId get() = TransactionId(FirstClassByteArray("txId".toByteArray()))
    val MINED_HEIGHT: BlockHeight = BlockHeight(1)
    val EXPIRY_HEIGHT: BlockHeight? = null
    const val INDEX: Long = 2
    val RAW: FirstClassByteArray get() = FirstClassByteArray("raw".toByteArray())
    const val IS_SENT_TRANSACTION: Boolean = false

    val NET_VALUE: Zatoshi = Zatoshi(-10_000)
    val TOTAL_SPENT_VALUE: Zatoshi = Zatoshi(985_000)
    val TOTAL_RECEIVED_VALUE: Zatoshi = Zatoshi(975_000)
    val FEE_PAID: Zatoshi = Zatoshi(10_000)
    const val IS_CHANGE: Boolean = false
    const val RECEIVED_NOTE_COUNT: Int = 1
    const val SENT_NOTE_COUNT: Int = 0
    const val MEMO_COUNT: Int = 0
    const val BLOCK_TIME_EPOCH_SECONDS: Long = 1234
    val STATE = TransactionState.Confirmed
    const val IS_SHIELDING = false
    const val SPENT_NOTE_COUNT: Int = 0
    val POOL_CROSSING_VALUE: Zatoshi? = null
    const val IS_TRUSTED: Boolean = false

    @Suppress("LongParameterList")
    fun new(
        txId: TransactionId = TX_ID,
        minedHeight: BlockHeight? = MINED_HEIGHT,
        expiryHeight: BlockHeight? = EXPIRY_HEIGHT,
        index: Long = INDEX,
        raw: FirstClassByteArray? = RAW,
        isSentTransaction: Boolean = IS_SENT_TRANSACTION,
        netValue: Zatoshi = NET_VALUE,
        totalReceivedValue: Zatoshi = TOTAL_SPENT_VALUE,
        totalSpentValue: Zatoshi = TOTAL_RECEIVED_VALUE,
        feePaid: Zatoshi = FEE_PAID,
        isChange: Boolean = IS_CHANGE,
        receivedNoteCount: Int = RECEIVED_NOTE_COUNT,
        sentNoteCount: Int = SENT_NOTE_COUNT,
        memoCount: Int = MEMO_COUNT,
        blockTimeEpochSeconds: Long = BLOCK_TIME_EPOCH_SECONDS,
        transactionState: TransactionState = STATE,
        isShielding: Boolean = IS_SHIELDING,
        spentNoteCount: Int = SPENT_NOTE_COUNT,
        poolCrossingValue: Zatoshi? = POOL_CROSSING_VALUE,
        isTrusted: Boolean = IS_TRUSTED
    ) = TransactionOverview(
        txId = txId,
        minedHeight = minedHeight,
        expiryHeight = expiryHeight,
        index = index,
        raw = raw,
        isSentTransaction = isSentTransaction,
        netValue = netValue,
        totalSpent = totalSpentValue,
        totalReceived = totalReceivedValue,
        feePaid = feePaid,
        isChange = isChange,
        receivedNoteCount = receivedNoteCount,
        sentNoteCount = sentNoteCount,
        memoCount = memoCount,
        blockTimeEpochSeconds = blockTimeEpochSeconds,
        transactionState = transactionState,
        isShielding = isShielding,
        spentNoteCount = spentNoteCount,
        poolCrossingValue = poolCrossingValue,
        isTrusted = isTrusted,
    )
}
