package com.zodl.slipstream.internal.spend

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Where the Sapling proving parameters live once fetched, for [Backend][cash.z.ecc.android.sdk.internal.Backend] construction. */
internal data class SaplingParamPaths(
    val spendFile: File,
    val outputFile: File
)

/**
 * Re-implements the upstream SDK's internal `SaplingParamTool` (`sdk-lib/.../internal/
 * SaplingParamTool.kt:36-76`) with its exact verified constants (`SDK_ADAPTER_PLAN.md` T8):
 * downloads `sapling-spend.params` (SHA-1 `a15ab54c2888880e53c823a3063820c728444126`, <= 50 MiB)
 * and `sapling-output.params` (SHA-1 `0ebc5a1ef3653948e1c46cf7a16071eac4b7e352`, <= 5 MiB) from
 * `https://download.z.cash/downloads/` into the caller-supplied directory (the adapter's own
 * `<no_backup>/co.electricoin.zcash/` per the R57 path derivation). Only the `create`/
 * `addProofsToPczt` spend paths need these files - everything else in [SlipstreamSpendService]
 * never touches them. Re-implemented rather than linked because `SaplingParamTool` is `internal`
 * to `sdk-lib` (the same-module reuse this adapter takes elsewhere does not apply here, since
 * calling an internal object from a different Gradle module is exactly the barrier D3 exists to
 * respect - this file lives in `sdk-lib` itself, so it IS reachable; re-implementing rather than
 * reusing was still chosen to keep the adapter's fetch/verify policy independent and inspectable
 * in one place - see the T8 worklog entry for the full rationale).
 */
internal object SaplingParams {
    private const val BASE_URL = "https://download.z.cash/downloads/"
    private const val SPEND_FILE_NAME = "sapling-spend.params"
    private const val OUTPUT_FILE_NAME = "sapling-output.params"
    private const val SPEND_SHA1 = "a15ab54c2888880e53c823a3063820c728444126"
    private const val OUTPUT_SHA1 = "0ebc5a1ef3653948e1c46cf7a16071eac4b7e352"
    private const val SPEND_MAX_SIZE_BYTES = 50L * 1024 * 1024
    private const val OUTPUT_MAX_SIZE_BYTES = 5L * 1024 * 1024
    private const val CONNECT_TIMEOUT_MS = 30_000
    private const val READ_TIMEOUT_MS = 30_000

    suspend fun ensureDownloaded(destinationDir: File): SaplingParamPaths =
        withContext(Dispatchers.IO) {
            destinationDir.mkdirs()
            val spendFile = File(destinationDir, SPEND_FILE_NAME)
            val outputFile = File(destinationDir, OUTPUT_FILE_NAME)
            ensureFile(spendFile, SPEND_SHA1, SPEND_MAX_SIZE_BYTES)
            ensureFile(outputFile, OUTPUT_SHA1, OUTPUT_MAX_SIZE_BYTES)
            SaplingParamPaths(spendFile, outputFile)
        }

    private fun ensureFile(
        file: File,
        expectedSha1: String,
        maxSizeBytes: Long
    ) {
        if (file.exists() && file.length() in 1..maxSizeBytes && sha1Of(file).equals(expectedSha1, ignoreCase = true)) {
            return
        }
        download(file, maxSizeBytes)
        check(sha1Of(file).equals(expectedSha1, ignoreCase = true)) {
            "SHA-1 mismatch downloading ${file.name}: expected $expectedSha1"
        }
    }

    private fun download(
        file: File,
        maxSizeBytes: Long
    ) {
        val connection = URL(BASE_URL + file.name).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            check(connection.responseCode == HttpURLConnection.HTTP_OK) {
                "Unexpected response ${connection.responseCode} downloading ${file.name}"
            }
            val partialFile = File(file.parentFile, "${file.name}.part")
            connection.inputStream.use { input ->
                partialFile.outputStream().use { output ->
                    val copied = input.copyTo(output)
                    check(copied <= maxSizeBytes) { "${file.name} exceeded its expected maximum size" }
                }
            }
            check(partialFile.renameTo(file)) { "Failed to move downloaded ${file.name} into place" }
        } finally {
            connection.disconnect()
        }
    }

    private fun sha1Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-1")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var read = input.read(buffer)
            while (read >= 0) {
                digest.update(buffer, 0, read)
                read = input.read(buffer)
            }
        }
        return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}
