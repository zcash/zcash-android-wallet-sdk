package cash.z.ecc.android.sdk.internal.model

import androidx.annotation.Keep
import cash.z.ecc.android.sdk.internal.jni.JNI_ACCOUNT_SEED_FP_BYTES_SIZE
import cash.z.ecc.android.sdk.internal.jni.JNI_ACCOUNT_UUID_BYTES_SIZE

/**
 * Serves as cross layer (Kotlin, Rust) communication class.
 *
 * @param accountUuid the "one-way stable" identifier for the account.
 * @param ufvk The account's Unified Full Viewing Key, if any.
 * @param uivk The account's Unified Incoming Viewing Key, if any.
 * @param accountName A human-readable name for the account
 * @param keySource A string identifier or other metadata describing the source of the seed
 * @param seedFingerprint The seed fingerprint
 * @param hdAccountIndex ZIP 32 account index
 *
 * @throws IllegalArgumentException if the values are inconsistent.
 */
@Keep
@Suppress("LongParameterList")
class JniAccount(
    val accountName: String?,
    val accountUuid: ByteArray,
    // We use -1L to represent NULL across JNI
    val hdAccountIndex: Long,
    val keySource: String?,
    val seedFingerprint: ByteArray?,
    val ufvk: String?,
    val uivk: String?,
) {
    init {
        require(accountUuid.size == JNI_ACCOUNT_UUID_BYTES_SIZE) {
            "Account UUID must be 16 bytes"
        }

        seedFingerprint?.let {
            require(seedFingerprint.size == JNI_ACCOUNT_SEED_FP_BYTES_SIZE) {
                "Seed fingerprint must be 32 bytes"
            }
        }
    }
}
