package cash.z.wallet.sdk.jni

import cash.z.wallet.sdk.annotation.OpenForTesting

/**
 * Serves as the JNI boundary between the Kotlin and Rust layers. Functions in this class should not be called directly
 * by code outside of the SDK. Instead, one of the higher-level components should be used such as Wallet.kt or
 * CompactBlockProcessor.kt.
 */
@OpenForTesting
class JniConverter {

    external fun initDataDb(dbData: String): Boolean

    external fun initAccountsTable(
        dbData: String,
        seed: ByteArray,
        accounts: Int): Array<String>

    external fun initBlocksTable(
        dbData: String,
        height: Int,
        hash: String,
        time: Long,
        saplingTree: String): Boolean

    external fun getAddress(dbData: String, account: Int): String

    external fun getBalance(dbData: String, account: Int): Long

    external fun getVerifiedBalance(dbData: String, account: Int): Long

    external fun getReceivedMemoAsUtf8(dbData: String, idNote: Long): String

    external fun getSentMemoAsUtf8(dbData: String, idNote: Long): String

    external fun validateCombinedChain(db_cache: String, db_data: String): Int

    external fun rewindToHeight(db_data: String, height: Int): Boolean

    external fun scanBlocks(db_cache: String, db_data: String): Boolean

    external fun sendToAddress(
        dbData: String,
        account: Int,
        extsk: String,
        to: String,
        value: Long,
        memo: String,
        spendParams: String,
        outputParams: String
    ): Long

    external fun initLogs()

    companion object {
        init {
            System.loadLibrary("zcashwalletsdk")
        }
    }

}