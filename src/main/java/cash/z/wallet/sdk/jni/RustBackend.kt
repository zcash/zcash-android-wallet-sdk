package cash.z.wallet.sdk.jni

import cash.z.wallet.sdk.data.twig

/**
 * Serves as the JNI boundary between the Kotlin and Rust layers. Functions in this class should not be called directly
 * by code outside of the SDK. Instead, one of the higher-level components should be used such as Wallet.kt or
 * CompactBlockProcessor.kt.
 */
class RustBackend : RustBackendWelding {

    external override fun initDataDb(dbData: String): Boolean

    external override fun initAccountsTable(
        dbData: String,
        seed: ByteArray,
        accounts: Int): Array<String>

    external override fun initBlocksTable(
        dbData: String,
        height: Int,
        hash: String,
        time: Long,
        saplingTree: String): Boolean

    external override fun getAddress(dbData: String, account: Int): String

    external override fun getBalance(dbData: String, account: Int): Long

    external override fun getVerifiedBalance(dbData: String, account: Int): Long

    external override fun getReceivedMemoAsUtf8(dbData: String, idNote: Long): String

    external override fun getSentMemoAsUtf8(dbData: String, idNote: Long): String

    external override fun validateCombinedChain(db_cache: String, db_data: String): Int

    external override fun rewindToHeight(db_data: String, height: Int): Boolean

    external override fun scanBlocks(db_cache: String, db_data: String): Boolean

    external override fun sendToAddress(
        dbData: String,
        account: Int,
        extsk: String,
        to: String,
        value: Long,
        memo: String,
        spendParams: String,
        outputParams: String
    ): Long

    external override fun initLogs()

    companion object {
        init {
            try {
                System.loadLibrary("zcashwalletsdk")
            } catch (e: Throwable) {
                twig("Error while loading native library: ${e.message}")
            }
        }
    }

}