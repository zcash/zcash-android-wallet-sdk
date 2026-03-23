package cash.z.ecc.android.sdk.model

import cash.z.ecc.android.sdk.internal.jni.JNI_ACCOUNT_SEED_FP_BYTES_SIZE
import cash.z.ecc.android.sdk.internal.model.JniAccount

/**
 * Unique identifier for a specific account tracked by a `Synchronizer`.
 *
 * Account identifiers are "one-way stable": a given identifier always points to a
 * specific viewing key within a specific `Synchronizer` instance, but the same viewing
 * key may have multiple account identifiers over time. In particular, this SDK upholds
 * the following properties:
 *
 * - When an account starts being tracked within a `Synchronizer` instance, a new
 *   `Account` is generated.
 * - If an `Account` is present within a `Synchronizer`, it always points to the same
 *   account.
 *
 * What this means is that account identifiers are not stable across "wallet recreation
 * events". Examples of these include:
 * - Restoring a wallet from a backed-up seed.
 * - Importing the same viewing key into two different wallet instances.
 *
 * @param accountUuid The account identifier.
 * @param ufvk The account's Unified Full Viewing Key, if any.
 * @param uivk The account's Unified Incoming Viewing Key, if any.
 * @param accountName A human-readable name for the account. This is visible to the wallet user, and
 *        the wallet app may have obtained it from them.
 * @param keySource A string identifier or other metadata describing the location of the spending
 *        key corresponding to the account's UFVK. This is set internally by the wallet app based on
 *        its private enumeration of spending methods it supports.
 * @param seedFingerprint The seed fingerprint. Must be length 32.
 * @param hdAccountIndex ZIP 32 account index
 */
data class Account internal constructor(
    val accountUuid: AccountUuid,
    val ufvk: String?,
    val uivk: String?,
    val accountName: String?,
    val keySource: String?,
    val seedFingerprint: ByteArray?,
    val hdAccountIndex: Zip32AccountIndex?,
) {
    init {
        seedFingerprint?.let {
            require(seedFingerprint.size == JNI_ACCOUNT_SEED_FP_BYTES_SIZE) {
                "Seed fingerprint must be 32 bytes"
            }
        }
    }

    companion object {
        fun new(jniAccount: JniAccount): Account =
            Account(
                accountUuid = AccountUuid.new(jniAccount.accountUuid),
                ufvk = jniAccount.ufvk,
                uivk = jniAccount.uivk,
                accountName = jniAccount.accountName,
                keySource = jniAccount.keySource,
                seedFingerprint = jniAccount.seedFingerprint,
                // We use -1L to represent NULL across JNI
                hdAccountIndex =
                    if (jniAccount.hdAccountIndex == -1L) {
                        null
                    } else {
                        Zip32AccountIndex.new(jniAccount.hdAccountIndex)
                    }
            )

        fun new(accountUuid: AccountUuid): Account =
            Account(
                accountUuid = accountUuid,
                ufvk = null,
                uivk = null,
                accountName = null,
                keySource = null,
                seedFingerprint = null,
                hdAccountIndex = null
            )
    }

    override fun toString(): String =
        "Account(accountUuid=$accountUuid," +
            " ufvk length=${ufvk?.length}," +
            " uivk length=${uivk?.length}," +
            " accountName=$accountName," +
            " keySource=$keySource," +
            " seedFingerprint size=${seedFingerprint?.size}," +
            " hdAccountIndex=$hdAccountIndex)"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Account

        if (accountUuid != other.accountUuid) return false
        if (ufvk != other.ufvk) return false
        if (uivk != other.uivk) return false
        if (accountName != other.accountName) return false
        if (keySource != other.keySource) return false
        if (seedFingerprint != null) {
            if (other.seedFingerprint == null) return false
            if (!seedFingerprint.contentEquals(other.seedFingerprint)) return false
        } else if (other.seedFingerprint != null) {
            return false
        }
        if (hdAccountIndex != other.hdAccountIndex) return false

        return true
    }

    override fun hashCode(): Int {
        var result = accountUuid.hashCode()
        result = 31 * result + (ufvk?.hashCode() ?: 0)
        result = 31 * result + (uivk?.hashCode() ?: 0)
        result = 31 * result + (accountName?.hashCode() ?: 0)
        result = 31 * result + (keySource?.hashCode() ?: 0)
        result = 31 * result + (seedFingerprint?.contentHashCode() ?: 0)
        result = 31 * result + (hdAccountIndex?.hashCode() ?: 0)
        return result
    }
}
