package cash.z.ecc.android.sdk.internal.model

import androidx.annotation.Keep
import cash.z.ecc.android.sdk.internal.jni.JNI_ACCOUNT_UUID_BYTES_SIZE

/**
 * Serves as cross layer (Kotlin, Rust) communication class.
 *
 * @param accountUuid the account UUID in ByteArray
 * @param saplingVerifiedBalance The verified account balance in the Sapling pool.
 * @param saplingChangePending The value in the account of Sapling change notes that do
 *        not yet have sufficient confirmations to be spendable.
 * @param saplingValuePending The value in the account of all remaining received Sapling
 *        notes that either do not have sufficient confirmations to be spendable, or for
 *        which witnesses cannot yet be constructed without additional scanning.
 * @param orchardVerifiedBalance The verified account balance in the Orchard pool.
 * @param orchardChangePending The value in the account of Orchard change notes that do
 *        not yet have sufficient confirmations to be spendable.
 * @param orchardValuePending The value in the account of all remaining received Orchard
 *        notes that either do not have sufficient confirmations to be spendable, or for
 *        which witnesses cannot yet be constructed without additional scanning.
 * @param ironwoodVerifiedBalance The verified account balance in the Ironwood pool.
 * @param ironwoodChangePending The value in the account of Ironwood change notes that do
 *        not yet have sufficient confirmations to be spendable.
 * @param ironwoodValuePending The value in the account of all remaining received Ironwood
 *        notes that either do not have sufficient confirmations to be spendable, or for
 *        which witnesses cannot yet be constructed without additional scanning.
 * @param unshieldedBalance The total account balance in the transparent pool,
 *        including unconfirmed funds, that must be shielded before use.
 * @throws IllegalArgumentException if the values are inconsistent.
 */
@Keep
@Suppress("LongParameterList")
class JniAccountBalance(
    val accountUuid: ByteArray,
    val saplingVerifiedBalance: Long,
    val saplingChangePending: Long,
    val saplingValuePending: Long,
    val orchardVerifiedBalance: Long,
    val orchardChangePending: Long,
    val orchardValuePending: Long,
    val ironwoodVerifiedBalance: Long,
    val ironwoodChangePending: Long,
    val ironwoodValuePending: Long,
    val unshieldedBalance: Long,
) {
    init {
        require(accountUuid.size == JNI_ACCOUNT_UUID_BYTES_SIZE) {
            "Account UUID must be 16 bytes"
        }
        require(saplingVerifiedBalance >= MIN_INCLUSIVE) {
            "Sapling verified balance $saplingVerifiedBalance must by equal or above $MIN_INCLUSIVE"
        }
        require(saplingChangePending >= MIN_INCLUSIVE) {
            "Sapling change pending $saplingChangePending must by equal or above $MIN_INCLUSIVE"
        }
        require(saplingValuePending >= MIN_INCLUSIVE) {
            "Sapling value pending $saplingValuePending must by equal or above $MIN_INCLUSIVE"
        }
        require(orchardVerifiedBalance >= MIN_INCLUSIVE) {
            "Orchard verified balance $orchardVerifiedBalance must by equal or above $MIN_INCLUSIVE"
        }
        require(orchardChangePending >= MIN_INCLUSIVE) {
            "Orchard change pending $orchardChangePending must by equal or above $MIN_INCLUSIVE"
        }
        require(orchardValuePending >= MIN_INCLUSIVE) {
            "Orchard value pending $orchardValuePending must by equal or above $MIN_INCLUSIVE"
        }
        require(ironwoodVerifiedBalance >= MIN_INCLUSIVE) {
            "Ironwood verified balance $ironwoodVerifiedBalance must by equal or above $MIN_INCLUSIVE"
        }
        require(ironwoodChangePending >= MIN_INCLUSIVE) {
            "Ironwood change pending $ironwoodChangePending must by equal or above $MIN_INCLUSIVE"
        }
        require(ironwoodValuePending >= MIN_INCLUSIVE) {
            "Ironwood value pending $ironwoodValuePending must by equal or above $MIN_INCLUSIVE"
        }
        require(unshieldedBalance >= MIN_INCLUSIVE) {
            "Unshielded balance $unshieldedBalance must by equal or above $MIN_INCLUSIVE"
        }
    }

    companion object {
        const val MIN_INCLUSIVE = 0
    }
}
