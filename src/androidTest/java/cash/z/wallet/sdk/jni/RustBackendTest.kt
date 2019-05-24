package cash.z.wallet.sdk.jni

import android.text.format.DateUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.BeforeClass
import org.junit.Test

class RustBackendTest {

    private val dbDataFile = "/data/user/0/cash.z.wallet.sdk.test/databases/data2.db"

    @Test
    fun testGetAddress_exists() {
        assertNotNull(rustBackend.getAddress(dbDataFile, 0))
    }

    @Test
    fun testGetAddress_valid() {
        val address = rustBackend.getAddress(dbDataFile, 0)
        val expectedAddress = "ztestsapling1snmqdnfqnc407pvqw7sld8w5zxx6nd0523kvlj4jf39uvxvh7vn0hs3q38n07806dwwecqwke3t"
        assertEquals("Invalid address", expectedAddress, address)
    }

    @Test
    fun testScanBlocks() {
        rustBackend.initDataDb(dbDataFile)
        rustBackend.initAccountsTable(dbDataFile, "dummyseed".toByteArray(), 1)

        // note: for this to work, the db file below must be uploaded to the device. Eventually, this test will be self-contained and remove that requirement.
        val result = rustBackend.scanBlocks(
            "/data/user/0/cash.z.wallet.sdk.test/databases/dummy-cache.db",
            dbDataFile
        )
//        Thread.sleep(15 * DateUtils.MINUTE_IN_MILLIS)
        assertEquals("Invalid number of results", 3, 3)
    }

    @Test
    fun testSend() {
        rustBackend.sendToAddress(
            "/data/user/0/cash.z.wallet.sdk.test/databases/data2.db",
            0,
            "dummykey",
            "ztestsapling1fg82ar8y8whjfd52l0xcq0w3n7nn7cask2scp9rp27njeurr72ychvud57s9tu90fdqgwdt07lg",
            210_000,
            "",
            "/data/user/0/cash.z.wallet.sdk.test/databases/sapling-spend.params",
            "/data/user/0/cash.z.wallet.sdk.test/databases/sapling-output.params"
        )
    }

    companion object {
        val rustBackend: RustBackendWelding = RustBackend()

        @BeforeClass
        @JvmStatic
        fun setup() {
            rustBackend.initLogs()
        }
    }

}