package cash.z.ecc.android.sdk.internal.ext

import cash.z.ecc.android.sdk.exception.TransactionEncoderException
import cash.z.ecc.android.sdk.internal.Twig

@Suppress("SwallowedException", "TooGenericExceptionCaught")
internal inline fun <R> tryNull(block: () -> R): R? =
    try {
        block()
    } catch (t: Throwable) {
        null
    }

/**
 * Execute the given block, converting exceptions into warnings. This can be further controlled by
 * filters so that only exceptions matching the given constraints are converted into warnings.
 *
 * @param ifContains only convert an exception into a warning if it contains the given text
 * @param unlessContains convert all exceptions into warnings unless they contain the given text
 */
@Suppress("TooGenericExceptionCaught")
internal inline fun <R> tryWarn(
    @Suppress("UNUSED_PARAMETER") message: String,
    ifContains: String? = null,
    unlessContains: String? = null,
    block: () -> R
): R? =
    try {
        block()
    } catch (t: Throwable) {
        val shouldThrowAnyway =
            (
                unlessContains != null &&
                    (t.message?.lowercase()?.contains(unlessContains.lowercase()) == true)
            ) ||
                (
                    ifContains != null &&
                        (t.message?.lowercase()?.contains(ifContains.lowercase()) == false)
                )
        if (shouldThrowAnyway) {
            throw t
        } else {
            Twig.debug(t) { "" }
            null
        }
    }

// Note: Do NOT change these texts as they match the ones from ScanError in
// librustzcash/zcash_client_backend/src/scanning.rs
internal const val PREV_HASH_MISMATCH =
    "The parent hash of proposed block does not correspond to the block hash at " +
        "height" // $NON-NLS
internal const val BLOCK_HEIGHT_DISCONTINUITY = "Block height discontinuity at height" // $NON-NLS
internal const val TREE_SIZE_MISMATCH =
    "note commitment tree size provided by a compact block did not match the " +
        "expected size at height" // $NON-NLS

/**
 * Check whether this error is the result of a failed continuity while scanning new blocks in the Rust layer.
 *
 * @return true in case of the check match, false otherwise
 */
internal fun Throwable.isScanContinuityError(): Boolean {
    val errorMessages =
        listOf(
            PREV_HASH_MISMATCH,
            BLOCK_HEIGHT_DISCONTINUITY,
            TREE_SIZE_MISMATCH
        )
    errorMessages.forEach { errMessage ->
        if (this.message?.lowercase()?.contains(errMessage.lowercase()) == true) {
            return true
        }
    }
    return false
}

// Note: Do NOT change these texts as they match the ones the proposal machinery of
// librustzcash/zcash_client_backend reports when an account cannot cover a spend. They are the only
// signal the FFI gives us for this failure (cf. issue #680).
internal const val INSUFFICIENT_BALANCE = "Insufficient balance" // $NON-NLS
internal const val ADDITIONAL_CHANGE_OUTPUT_REQUIRED =
    "The transaction requires an additional change output of" // $NON-NLS

private const val CAUSE_CHAIN_MAX_DEPTH = 10

/**
 * Check whether this error - or any error within the first [CAUSE_CHAIN_MAX_DEPTH] links of its cause
 * chain - is the Rust layer reporting that the account lacks the spendable funds a proposal needs.
 * The walk is bounded and cycle-safe, as a cause chain coming across the FFI is not guaranteed to be
 * either.
 *
 * @return true in case of the check match, false otherwise
 */
internal fun Throwable.indicatesInsufficientFunds(): Boolean {
    val markers = listOf(INSUFFICIENT_BALANCE, ADDITIONAL_CHANGE_OUTPUT_REQUIRED)
    val visited = mutableSetOf<Throwable>()
    var current: Throwable? = this
    var depth = 0
    while (current != null && depth < CAUSE_CHAIN_MAX_DEPTH && visited.add(current)) {
        val message = current.message
        if (message != null && markers.any { message.contains(it, ignoreCase = true) }) {
            return true
        }
        current = current.cause
        depth++
    }
    return false
}

/**
 * Maps a failed proposal attempt onto the typed exception that describes it: an insufficient-funds
 * failure - whatever the propose operation was - becomes
 * [TransactionEncoderException.InsufficientFundsException]; anything else is handed to the
 * per-operation [fallback] wrapper.
 */
internal fun Throwable.toProposalException(
    fallback: (Throwable) -> TransactionEncoderException
): TransactionEncoderException =
    if (indicatesInsufficientFunds()) {
        TransactionEncoderException.InsufficientFundsException(this)
    } else {
        fallback(this)
    }
