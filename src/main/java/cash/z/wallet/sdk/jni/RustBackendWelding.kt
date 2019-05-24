package cash.z.wallet.sdk.jni

/**
 * Contract defining the exposed capabilitiies of the Rust backend.
 * This is what welds the SDK to the Rust layer.
 */
interface RustBackendWelding {

    fun initDataDb(dbData: String): Boolean

    fun initAccountsTable(
        dbData: String,
        seed: ByteArray,
        accounts: Int): Array<String>

    fun initBlocksTable(
        dbData: String,
        height: Int,
        hash: String,
        time: Long,
        saplingTree: String): Boolean

    fun getAddress(dbData: String, account: Int): String

    fun getBalance(dbData: String, account: Int): Long

    fun getVerifiedBalance(dbData: String, account: Int): Long

    fun getReceivedMemoAsUtf8(dbData: String, idNote: Long): String

    fun getSentMemoAsUtf8(dbData: String, idNote: Long): String

    fun validateCombinedChain(db_cache: String, db_data: String): Int

    fun rewindToHeight(db_data: String, height: Int): Boolean

    fun scanBlocks(db_cache: String, db_data: String): Boolean

    fun sendToAddress(
        dbData: String,
        account: Int,
        extsk: String,
        to: String,
        value: Long,
        memo: String,
        spendParams: String,
        outputParams: String
    ): Long

    fun initLogs()

}