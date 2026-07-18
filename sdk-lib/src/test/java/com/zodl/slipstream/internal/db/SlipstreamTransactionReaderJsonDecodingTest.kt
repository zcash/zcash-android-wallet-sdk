package com.zodl.slipstream.internal.db

import org.junit.Test
import kotlin.test.assertTrue

/**
 * Pins [hexToBytes] - the BLOB-column half of
 * [com.zodl.slipstream.SlipstreamNative.readQuery]'s JSON row decoding this reader's methods
 * rely on (worklog `08-engine-sigbus-android.md`). Pure JVM, no native library.
 *
 * The [org.json.JSONArray] column accessors alongside [hexToBytes] (`longOrNull`/`intOrNull`/
 * `stringOrNull`/`blobOrNull`/`blob`) are NOT unit-tested here: this module's default Android
 * Gradle Plugin unit-test jar stubs `org.json` (every method returns a zero-value default
 * regardless of input, confirmed empirically - a real `JSONArray("[1]").getLong(0)` came back
 * `0`, not `1`, under `testDebugUnitTest`), and this project has no Robolectric dependency to
 * back it with a real implementation. Those accessors are exercised on-device instead, by
 * [com.zodl.slipstream.SlipstreamNativeSmokeTest.readQuery_smoke] in `androidTest`.
 */
class SlipstreamTransactionReaderJsonDecodingTest {
    @Test
    fun hexToBytes_decodes_lowercase_hex_pairs_in_order() {
        assertTrue(hexToBytes("0a1b2c").contentEquals(byteArrayOf(0x0a, 0x1b, 0x2c)))
    }

    @Test
    fun hexToBytes_decodes_empty_string_to_empty_array() {
        assertTrue(hexToBytes("").contentEquals(ByteArray(0)))
    }

    @Test
    fun hexToBytes_round_trips_all_byte_values() {
        val original = ByteArray(256) { it.toByte() }
        val hex = original.joinToString("") { byte -> "%02x".format(byte) }
        assertTrue(hexToBytes(hex).contentEquals(original))
    }

    @Test
    fun hexToBytes_decodes_a_single_byte_pair() {
        assertTrue(hexToBytes("ff").contentEquals(byteArrayOf(-1)))
    }
}
