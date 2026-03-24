package cash.z.ecc.android.sdk.model

import cash.z.ecc.android.sdk.internal.jni.JNI_ACCOUNT_UUID_BYTES_SIZE

/**
 * Typesafe wrapper class for the account UUID identifier.
 *
 * @param value The account identifier. Must be length 16.
 */
@ConsistentCopyVisibility
data class AccountUuid internal constructor(
    val value: ByteArray,
) {
    init {
        require(value.size == JNI_ACCOUNT_UUID_BYTES_SIZE) {
            "Account UUID must be 16 bytes"
        }
    }

    companion object {
        fun new(uuid: ByteArray) = AccountUuid(uuid)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as AccountUuid

        return value.contentEquals(other.value)
    }

    override fun hashCode(): Int = value.contentHashCode()
}
