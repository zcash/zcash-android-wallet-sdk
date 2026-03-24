@file:Suppress("TooManyFunctions")

package cash.z.ecc.android.sdk.model

import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

@ConsistentCopyVisibility
data class PendingTransaction internal constructor(
    val id: Long,
    val value: Zatoshi,
    val fee: Zatoshi?,
    val memo: FirstClassByteArray?,
    val raw: FirstClassByteArray,
    val recipient: TransactionRecipient,
    val sentFromAccount: Account,
    val minedHeight: BlockHeight?,
    val expiryHeight: BlockHeight?,
    val cancelled: Int,
    val encodeAttempts: Int,
    val submitAttempts: Int,
    val errorMessage: String?,
    val errorCode: Int?,
    val createTime: Long,
    val rawTransactionId: TransactionId?
) {
    override fun toString() = "PendingTransaction"
}

data class TransactionRecipient(
    val addressValue: String?,
    val accountUuid: AccountUuid?
)

// Note there are some commented out methods which aren't being removed yet, as they might be needed before the
// Roomoval draft PR is completed

// fun PendingTransaction.isSameTxId(other: MinedTransaction) =
//     rawTransactionId == other.rawTransactionId
//
// fun PendingTransaction.isSameTxId(other: PendingTransaction) =
//     rawTransactionId == other.rawTransactionId

internal fun PendingTransaction.hasRawTransactionId() = rawTransactionId?.value?.byteArray?.isEmpty() == false

@Suppress("MaxLineLength")
fun PendingTransaction.isCreating() = raw.byteArray.isNotEmpty() && submitAttempts <= 0 && !isFailedSubmit() && !isFailedEncoding()

@Suppress("MaxLineLength")
fun PendingTransaction.isCreated() = raw.byteArray.isNotEmpty() && submitAttempts <= 0 && !isFailedSubmit() && !isFailedEncoding()

fun PendingTransaction.isFailedEncoding() = raw.byteArray.isEmpty() && encodeAttempts > 0

fun PendingTransaction.isFailedSubmit(): Boolean = errorMessage != null || (errorCode != null && errorCode < 0)

fun PendingTransaction.isFailure(): Boolean = isFailedEncoding() || isFailedSubmit()

// fun PendingTransaction.isCancelled(): Boolean {
//     return cancelled > 0
// }

fun PendingTransaction.isMined(): Boolean = minedHeight != null

internal fun PendingTransaction.isSubmitted(): Boolean = submitAttempts > 0

@Suppress("ReturnCount")
internal fun PendingTransaction.isExpired(
    latestHeight: BlockHeight?,
    saplingActivationHeight: BlockHeight
): Boolean {
    val expiryHeightLocal = expiryHeight

    if (latestHeight == null || expiryHeightLocal == null) {
        return false
    }
    // TODO [#687]: test for off-by-one error here. Should we use <= or <
    // TODO [#687]: https://github.com/zcash/zcash-android-wallet-sdk/issues/687
    if (latestHeight.value < saplingActivationHeight.value || expiryHeightLocal < saplingActivationHeight) {
        return false
    }

    return expiryHeightLocal < latestHeight
}

private const val EXPIRY_BLOCK_COUNT = 100

// if we don't have info on a pendingtx after 100 blocks then it's probably safe to stop polling!
@Suppress("ReturnCount")
internal fun PendingTransaction.isLongExpired(
    latestHeight: BlockHeight?,
    saplingActivationHeight: BlockHeight
): Boolean {
    val expiryHeightLocal = expiryHeight

    if (latestHeight == null || expiryHeightLocal == null) {
        return false
    }

    if (latestHeight.value < saplingActivationHeight.value || expiryHeightLocal < saplingActivationHeight) {
        return false
    }
    return (latestHeight.value - expiryHeightLocal.value) > EXPIRY_BLOCK_COUNT
}

private const val ERROR_CODE_MARKED_FOR_DELETION = -9090

internal fun PendingTransaction.isMarkedForDeletion(): Boolean =
    rawTransactionId == null && (errorCode ?: 0) == ERROR_CODE_MARKED_FOR_DELETION

private val smallThreshold = 30.minutes
private val hugeThreshold = 30.days

internal fun PendingTransaction.isSafeToDiscard(): Boolean {
    // invalid dates shouldn't happen or should be temporary
    if (createTime < 0) return false

    val ageInMilliseconds = System.currentTimeMillis() - createTime
    return when {
        // if it is mined, then it is not pending so it can be deleted fairly quickly from this db
        isMined() && ageInMilliseconds > smallThreshold.inWholeMilliseconds -> true

        // if a tx fails to encode, then there's not much we can do with it
        isFailedEncoding() && ageInMilliseconds > smallThreshold.inWholeMilliseconds -> true

        // don't delete failed submissions until they've been cleaned up, properly, or else we lose
        // the ability to remove them in librustzcash prior to expiration
        isFailedSubmit() && isMarkedForDeletion() -> true

        !isMined() && ageInMilliseconds > hugeThreshold.inWholeMilliseconds -> true

        else -> false
    }
}

fun PendingTransaction.isPending(currentHeight: BlockHeight?): Boolean {
    // not mined and not expired and successfully created
    return !isSubmitSuccess() &&
        minedHeight == null &&
        (expiryHeight == null || expiryHeight.value > (currentHeight?.value ?: 0L))
}

fun PendingTransaction.isSubmitSuccess(): Boolean =
    submitAttempts > 0 && (errorCode != null && errorCode >= 0) && errorMessage == null
