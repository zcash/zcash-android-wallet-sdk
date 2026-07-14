package cash.z.ecc.android.sdk.internal

import android.content.Context
import cash.z.ecc.android.sdk.exception.TransactionEncoderException
import cash.z.ecc.android.sdk.internal.ext.deleteRecursivelySuspend
import cash.z.ecc.android.sdk.internal.ext.deleteSuspend
import cash.z.ecc.android.sdk.internal.ext.existsSuspend
import cash.z.ecc.android.sdk.internal.ext.getCacheDirSuspend
import cash.z.ecc.android.sdk.internal.ext.getSha1Hash
import cash.z.ecc.android.sdk.internal.ext.mkdirsSuspend
import cash.z.ecc.android.sdk.internal.ext.renameToSuspend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.URL
import java.nio.channels.Channels
import kotlin.time.Duration.Companion.milliseconds

internal class SaplingParamTool(
    val properties: SaplingParamToolProperties
) {
    val spendParamsFile: File
        get() = File(properties.paramsDirectory, SPEND_PARAM_FILE_NAME)

    val outputParamsFile: File
        get() = File(properties.paramsDirectory, OUTPUT_PARAM_FILE_NAME)

    companion object {
        /**
         * Maximum file size for the sapling spend params - 50MB
         */
        internal const val SPEND_PARAM_FILE_MAX_BYTES_SIZE = 50L * 1024L * 1024L

        /**
         * Maximum file size for the sapling spend params - 5MB
         */
        internal const val OUTPUT_PARAM_FILE_MAX_BYTES_SIZE = 5L * 1024L * 1024L

        /**
         * Subdirectory name, in which are the sapling params files stored.
         */
        internal const val SAPLING_PARAMS_LEGACY_SUBDIRECTORY = "params"

        /**
         * File name for the sapling spend params
         */
        internal const val SPEND_PARAM_FILE_NAME = "sapling-spend.params"

        /**
         * File name for the sapling output params
         */
        internal const val OUTPUT_PARAM_FILE_NAME = "sapling-output.params"

        /**
         *  Temporary file prefix to fulfill atomicity requirement of file handling
         */
        private const val TEMPORARY_FILE_NAME_PREFIX = "_"

        /**
         * File SHA1 hash for the sapling spend params
         */
        internal const val SPEND_PARAM_FILE_SHA1_HASH = "a15ab54c2888880e53c823a3063820c728444126"

        /**
         * File SHA1 hash for the sapling output params
         */
        internal const val OUTPUT_PARAM_FILE_SHA1_HASH = "0ebc5a1ef3653948e1c46cf7a16071eac4b7e352"

        /**
         * The Url that is used by default in zcashd
         */
        private const val CLOUD_PARAM_DIR_URL = "https://download.z.cash/downloads/"

        private val checkFilesMutex = Mutex()

        // Guards fetchParams() so concurrent callers can't race on the shared temporary file path
        private val fetchParamsMutex = Mutex()

        /**
         * Initialization of needed properties. This is necessary entry point for other operations from {@code
         * SaplingParamTool}. This type of implementation also simplifies its testing.
         *
         * @param context
         */
        internal suspend fun new(context: Context): SaplingParamTool {
            val paramsDirectory = Files.getZcashNoBackupSubdirectory(context)
            val toolProperties =
                SaplingParamToolProperties(
                    paramsDirectory = paramsDirectory,
                    paramsLegacyDirectory = File(context.getCacheDirSuspend(), SAPLING_PARAMS_LEGACY_SUBDIRECTORY),
                    saplingParams =
                        listOf(
                            SaplingParameters(
                                paramsDirectory,
                                SPEND_PARAM_FILE_NAME,
                                SPEND_PARAM_FILE_MAX_BYTES_SIZE,
                                SPEND_PARAM_FILE_SHA1_HASH
                            ),
                            SaplingParameters(
                                paramsDirectory,
                                OUTPUT_PARAM_FILE_NAME,
                                OUTPUT_PARAM_FILE_MAX_BYTES_SIZE,
                                OUTPUT_PARAM_FILE_SHA1_HASH
                            )
                        )
                )
            return SaplingParamTool(toolProperties)
        }

        /**
         * Returns file object pointing to the parameters files parent directory. We need to check if the parameters
         * files don't sit in the legacy folder first. If they do, then we move the files to the currently used
         * directory and validate files hashes.
         *
         * @return params destination directory file
         */
        internal suspend fun initAndGetParamsDestinationDir(toolProperties: SaplingParamToolProperties): File {
            checkFilesMutex.withLock {
                toolProperties.saplingParams.forEach {
                    val legacyFile = File(toolProperties.paramsLegacyDirectory, it.fileName)
                    val currentFile = File(toolProperties.paramsDirectory, it.fileName)

                    if (legacyFile.existsSuspend() && isFileHashValid(legacyFile, it.fileHash)) {
                        Twig.debug {
                            "Moving params file: ${it.fileName} from legacy folder to the currently used " +
                                "folder."
                        }
                        currentFile.parentFile?.mkdirsSuspend()
                        if (!renameParametersFile(legacyFile, currentFile)) {
                            Twig.debug {
                                "Failed while moving the params file: ${it.fileName} to the preferred " +
                                    "location."
                            }
                        }
                    } else {
                        Twig.debug {
                            "Legacy file either does not exist or is not valid. Will be fetched to the preferred " +
                                "location."
                        }
                    }
                }
                // remove the params folder and its files - a new sapling files will be fetched to the preferred
                // location
                toolProperties.paramsLegacyDirectory.deleteRecursivelySuspend()
            }

            return toolProperties.paramsDirectory
        }

        /**
         * Compares the input file parameter SHA1 hash with the given input hash.
         *
         * @param parametersFile file of which SHA1 hash will be checked
         * @param fileHash hash to compare with
         *
         * @return true in case of hashes are the same, false otherwise
         */
        private suspend fun isFileHashValid(
            parametersFile: File,
            fileHash: String
        ): Boolean =
            try {
                fileHash == parametersFile.getSha1Hash()
            } catch (e: IOException) {
                Twig.debug { "Failed in comparing file's hashes with: ${e.message}, caused by: ${e.cause}." }
                false
            }

        /**
         * The purpose of this function is to rename parameters file from the old name (given by the {@code
         * fromParamFile} parameter) to the new name (given by {@code toParamFile}). This operation covers also the file
         * move, if it's in a different location.
         *
         * @param fromParamFile the previously used file name/location
         * @param toParamFile the newly used file name/location
         */
        private suspend fun renameParametersFile(
            fromParamFile: File,
            toParamFile: File
        ): Boolean {
            return runCatching {
                return@runCatching fromParamFile.renameToSuspend(toParamFile)
            }.onFailure {
                Twig.debug(it) { "Failed while renaming parameters file" }
            }.getOrDefault(false)
        }
    }

    /**
     * Checks the given directory for the output and spending params and calls [fetchParams] for those, which are
     * missing.
     *
     * Note: Don't forget to call the entry point function {@code initSaplingParamTool} first. Make sure you also
     * called {@code initAndGetParamsDestinationDir} previously, as it's always better to check the
     * legacy destination folder first.
     *
     * @param destinationDir the directory where the params should be stored.
     *
     * @throws TransactionEncoderException.MissingParamsException in case of failure while checking sapling params
     * @throws TransactionEncoderException.FetchParamsException
     * @throws TransactionEncoderException.ValidateParamsException
     * files
     */
    @Throws(
        TransactionEncoderException.ValidateParamsException::class,
        TransactionEncoderException.FetchParamsException::class,
        TransactionEncoderException.MissingParamsException::class
    )
    internal suspend fun ensureParams(destinationDir: File) {
        properties.saplingParams
            .filter {
                !File(it.destinationDirectory, it.fileName).existsSuspend()
            }.forEach {
                try {
                    Twig.debug { "Attempting to download missing params: ${it.fileName}." }
                    fetchParams(it)
                } catch (e: TransactionEncoderException.FetchParamsException) {
                    Twig.debug {
                        "Failed to fetch param file ${it.fileName} due to: $e. The second attempt is starting with a " +
                            "little delay."
                    }
                    // Re-run the fetch with a little delay, if it failed previously (as it can be caused by network
                    // conditions). We do it only once, the next failure is delivered to the caller of this method.
                    delay(200.milliseconds)
                    fetchParams(it)
                } catch (e: TransactionEncoderException.ValidateParamsException) {
                    Twig.debug {
                        "Failed to validate fetched param file ${it.fileName} due to: $e. The second attempt is " +
                            "starting now."
                    }
                    // Re-run the fetch for invalid param file immediately, if it failed previously. We do it again only
                    // once, the next failure is delivered to the caller of this method.
                    fetchParams(it)
                }
            }

        if (!validate(destinationDir)) {
            Twig.debug { "Fetching sapling params files failed." }
            throw TransactionEncoderException.MissingParamsException
        }
    }

    /**
     * Download and store the params file into the given directory. It also checks the file size to eliminate the
     * risk of downloading potentially large file from a malicious server.
     *
     * @param paramsToFetch parameters wrapper class, which holds information about it
     *
     * @throws TransactionEncoderException.FetchParamsException if any error while downloading the params file occurs
     * @throws TransactionEncoderException.ValidateParamsException if a failure in validation of fetched file occurs
     */
    @Throws(
        TransactionEncoderException.ValidateParamsException::class,
        TransactionEncoderException.FetchParamsException::class
    )
    internal suspend fun fetchParams(paramsToFetch: SaplingParameters) {
        fetchParamsMutex.withLock {
            val url = URL("$CLOUD_PARAM_DIR_URL${paramsToFetch.fileName}")
            Twig.debug { "Sapling params fetch URL: $url" }
            val temporaryFile =
                File(
                    paramsToFetch.destinationDirectory,
                    "$TEMPORARY_FILE_NAME_PREFIX${paramsToFetch.fileName}"
                )

            withContext(Dispatchers.IO) {
                runCatching {
                    Channels.newChannel(url.openStream()).use { readableByteChannel ->
                        temporaryFile.outputStream().use { fileOutputStream ->
                            fileOutputStream.channel.use { fileChannel ->
                                // Transfers bytes from stream to file from position 0 to end position or to max
                                // file size limit. This eliminates the risk of downloading potentially large files
                                // from a malicious server. We need to make a check of the file hash then.
                                fileChannel.transferFrom(readableByteChannel, 0, paramsToFetch.fileMaxSizeBytes)
                            }
                        }
                    }
                }.onFailure { exception ->
                    // IllegalArgumentException - If the preconditions on the parameters do not hold
                    // NonReadableChannelException - If the source channel was not opened for reading
                    // NonWritableChannelException - If this channel was not opened for writing
                    // ClosedChannelException - If either this channel or the source channel is closed
                    // AsynchronousCloseException - If another thread closes either channel while the transfer is
                    // in progress
                    // ClosedByInterruptException - If another thread interrupts the current thread while the
                    // transfer is in progress, thereby closing both channels and setting the current thread's
                    // interrupt status
                    // IOException - If some other I/O error occurs
                    finalizeAndReportError(
                        temporaryFile,
                        exception =
                            TransactionEncoderException.FetchParamsException(
                                paramsToFetch,
                                "Error while fetching ${paramsToFetch.fileName}, caused by $exception."
                            )
                    )
                }.onSuccess {
                    Twig.debug {
                        "Fetch and write of the temporary ${temporaryFile.name} succeeded. Validating and moving " +
                            "it to the final destination."
                    }
                    if (!isFileHashValid(temporaryFile, paramsToFetch.fileHash)) {
                        finalizeAndReportError(
                            temporaryFile,
                            exception =
                                TransactionEncoderException.ValidateParamsException(
                                    paramsToFetch,
                                    "Failed while validating fetched param file: ${paramsToFetch.fileName}."
                                )
                        )
                    }
                    val resultFile = File(paramsToFetch.destinationDirectory, paramsToFetch.fileName)
                    if (!renameParametersFile(temporaryFile, resultFile)) {
                        finalizeAndReportError(
                            temporaryFile,
                            resultFile,
                            exception =
                                TransactionEncoderException.ValidateParamsException(
                                    paramsToFetch,
                                    "Failed while renaming result param file: ${paramsToFetch.fileName}."
                                )
                        )
                    }
                }
            }
        }
    }

    @Throws(TransactionEncoderException.FetchParamsException::class)
    private suspend fun finalizeAndReportError(
        vararg files: File,
        exception: TransactionEncoderException
    ) {
        files.forEach {
            it.deleteSuspend()
        }
        exception.also {
            Twig.debug(it) { "Error while fetching sapling params files." }
            throw it
        }
    }

    internal suspend fun validate(destinationDir: File): Boolean =
        arrayOf(
            SPEND_PARAM_FILE_NAME,
            OUTPUT_PARAM_FILE_NAME
        ).all { paramFileName ->
            File(destinationDir, paramFileName).existsSuspend()
        }.also {
            Twig.debug { "Param files ${if (!it) "did not" else ""} both exist!" }
        }
}

/**
 * Sapling file parameter class to hold each sapling file attributes.
 */
internal data class SaplingParameters(
    val destinationDirectory: File,
    val fileName: String,
    val fileMaxSizeBytes: Long,
    val fileHash: String
)

/**
 * Sapling param tool helper properties. The goal of this implementation is to ease its testing.
 */
internal data class SaplingParamToolProperties(
    val saplingParams: List<SaplingParameters>,
    val paramsDirectory: File,
    val paramsLegacyDirectory: File
)
