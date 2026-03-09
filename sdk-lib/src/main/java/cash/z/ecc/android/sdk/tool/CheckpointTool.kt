@file:Suppress("TooManyFunctions")

package cash.z.ecc.android.sdk.tool

import android.content.Context
import androidx.annotation.VisibleForTesting
import cash.z.ecc.android.sdk.exception.BirthdayException
import cash.z.ecc.android.sdk.internal.Twig
import cash.z.ecc.android.sdk.internal.model.Checkpoint
import cash.z.ecc.android.sdk.internal.model.ext.from
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.ZcashNetwork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import java.io.BufferedReader
import java.io.IOException
import java.util.Locale
import kotlin.math.abs

/**
 * Tool for loading checkpoints for the wallet, based on the height at which the wallet was born.
 */
internal object CheckpointTool {
    // Behavior change implemented as a fix for issue #270.  Temporarily adding a boolean
    // that allows the change to be rolled back quickly if needed, although long-term
    // this flag should be removed.
    @VisibleForTesting
    internal val IS_FALLBACK_ON_FAILURE = true

    /**
     * Load the nearest checkpoint to the given birthday height. If null is given, then this
     * will load the most recent checkpoint available.
     */
    suspend fun loadNearest(
        context: Context,
        network: ZcashNetwork,
        birthdayHeight: BlockHeight?
    ): Checkpoint {
        // TODO [#684]: potentially pull from shared preferences first
        // TODO [#684]: https://github.com/zcash/zcash-android-wallet-sdk/issues/684
        return loadCheckpointFromAssets(context, network, birthdayHeight)
    }

    /**
     * Useful for when an exact checkpoint is needed, like for SAPLING_ACTIVATION_HEIGHT. In
     * most cases, loading the nearest checkpoint is preferred for privacy reasons.
     */
    suspend fun loadExact(
        context: Context,
        network: ZcashNetwork,
        birthday: BlockHeight
    ) = loadNearest(context, network, birthday).also {
        if (it.height != birthday) {
            throw BirthdayException.ExactBirthdayNotFoundException(
                birthday,
                it
            )
        }
    }

    /**
     * Load tha last know checkpoint for the given network.
     */
    suspend fun loadLast(
        context: Context,
        network: ZcashNetwork,
    ) = loadNearest(context, network, null)

    // Converting this to suspending will then propagate
    @Throws(IOException::class)
    internal suspend fun listCheckpointDirectoryContents(
        context: Context,
        directory: String
    ) = withContext(Dispatchers.IO) {
        context.assets.list(directory)
    }

    /**
     * Returns the directory within the assets folder where birthday data
     * (i.e. sapling trees for a given height) can be found.
     */
    @VisibleForTesting
    internal fun checkpointDirectory(network: ZcashNetwork) =
        "co.electriccoin.zcash/checkpoint/${network.networkName.lowercase(Locale.ROOT)}"

    internal fun checkpointHeightFromFilename(fileName: String) = BlockHeight.new(fileName.split('.').first().toLong())

    private fun Array<String>.sortDescending() =
        apply {
            sortByDescending { checkpointHeightFromFilename(it).value }
        }

    /**
     * Load the given birthday file from the assets of the given context. When no height is
     * specified, we default to the file with the greatest name.
     *
     * @param context the context from which to load assets.
     * @param birthday the height file to look for among the file names.
     *
     * @return a WalletBirthday that reflects the contents of the file or an exception when
     * parsing fails.
     */
    private suspend fun loadCheckpointFromAssets(
        context: Context,
        network: ZcashNetwork,
        birthday: BlockHeight?
    ): Checkpoint {
        Twig.debug { "loading checkpoint from assets: $birthday" }
        val directory = checkpointDirectory(network)
        val treeFiles = getFilteredFileNames(context, directory, birthday)

        Twig.debug { "found ${treeFiles.size} sapling tree checkpoints: $treeFiles" }

        return getFirstValidWalletBirthday(context, network, directory, treeFiles)
    }

    private suspend fun getFilteredFileNames(
        context: Context,
        directory: String,
        birthday: BlockHeight?
    ): List<String> {
        val unfilteredTreeFiles = listCheckpointDirectoryContents(context, directory)
        if (unfilteredTreeFiles.isNullOrEmpty()) {
            throw BirthdayException.MissingBirthdayFilesException(directory)
        }

        val filteredTreeFiles =
            unfilteredTreeFiles
                .sortDescending()
                .filter { filename ->
                    birthday?.let { checkpointHeightFromFilename(filename) <= it } ?: true
                }

        if (filteredTreeFiles.isEmpty()) {
            throw BirthdayException.BirthdayFileNotFoundException(
                directory,
                birthday
            )
        }

        return filteredTreeFiles
    }

    /**
     * @param treeFiles A list of files, sorted in descending order based on `int` value of the first part of
     * the filename.
     */
    @VisibleForTesting
    internal suspend fun getFirstValidWalletBirthday(
        context: Context,
        network: ZcashNetwork,
        directory: String,
        treeFiles: List<String>
    ): Checkpoint {
        var lastException: Exception? = null
        treeFiles.forEach { treefile ->
            @Suppress("TooGenericExceptionCaught")
            try {
                val jsonString =
                    withContext(Dispatchers.IO) {
                        context.assets.open("$directory/$treefile").use { inputStream ->
                            inputStream.reader().use { inputStreamReader ->
                                BufferedReader(inputStreamReader).use { bufferedReader ->
                                    bufferedReader.readText()
                                }
                            }
                        }
                    }

                return Checkpoint.from(network, jsonString)
            } catch (t: Throwable) {
                val exception =
                    BirthdayException.MalformattedBirthdayFilesException(
                        directory,
                        treefile,
                        t
                    )
                lastException = exception

                if (IS_FALLBACK_ON_FAILURE) {
                    // TODO [#684]: If we ever add crash analytics hooks, this would be something to report
                    // TODO [#684]: https://github.com/zcash/zcash-android-wallet-sdk/issues/684
                    Twig.debug(t) { "Malformed birthday file $t" }
                } else {
                    throw exception
                }
            }
        }

        throw lastException!!
    }

    // These two come values from the Zcash Swift SDK. The average time between 2500 blocks during last 10
    // checkpoints (estimated March 31, 2025) is 52.33 hours for mainnet. And the average time between 10,000 blocks
    // during last 10 checkpoints (estimated March 31, 2025) is 134.93 hours for testnet.
    private const val AVG_INTERVAL_TIME_MAINNET = 52.33f
    private const val AVG_INTERVAL_TIME_TESTNET = 134.93f

    private const val CHECKPOINT_BLOCK_INTERVAL_MAINNET = 2_500
    private const val CHECKPOINT_BLOCK_INTERVAL_TESTNET = 10_000

    private const val MILLIS_IN_HOUR = 3_600_000

    /**
     * This API takes a given date and finds out the closes checkpoint's height for it. Each block checkpoint has a
     * timestamp stored that is used for this calculation.
     *
     * @param context
     * @param date The given date to find the closest checkpoint for.
     * @param network The network to use for the calculation.
     * @return The height of the closest checkpoint for the given date, or the latest checkpoint height if the given
     * date is over it. It returns the sapling activation height if the given date is before it.
     */
    @Suppress("ReturnCount")
    internal suspend fun estimateBirthdayHeight(
        context: Context,
        date: Instant,
        network: ZcashNetwork,
    ): BlockHeight {
        val avgIntervalTime =
            if (network == ZcashNetwork.Mainnet) {
                AVG_INTERVAL_TIME_MAINNET
            } else {
                AVG_INTERVAL_TIME_TESTNET
            }
        val blockInterval =
            if (network == ZcashNetwork.Mainnet) {
                CHECKPOINT_BLOCK_INTERVAL_MAINNET
            } else {
                CHECKPOINT_BLOCK_INTERVAL_TESTNET
            }
        val saplingActivationHeight =
            if (network == ZcashNetwork.Mainnet) {
                ZcashNetwork.Mainnet.saplingActivationHeight
            } else {
                ZcashNetwork.Testnet.saplingActivationHeight
            }

        val latestCheckpoint = loadLast(context, network)
        val latestCheckpointTime = latestCheckpoint.epochTimeMillis

        // If above the latest checkpoint, return the checkpoint height
        if (date.toEpochMilliseconds() >= latestCheckpointTime) {
            return latestCheckpoint.height
        }

        // Phase 1, estimate possible height
        val nowTimeIntervalSince1970 = Clock.System.now().toEpochMilliseconds()
        val timeDiff =
            (nowTimeIntervalSince1970 - date.toEpochMilliseconds()) -
                (nowTimeIntervalSince1970 - latestCheckpointTime)
        val blockDiffHours = ((timeDiff / MILLIS_IN_HOUR) / avgIntervalTime) * blockInterval

        val heightToLookAround = (
            (latestCheckpoint.height.value - blockDiffHours.toInt()) /
                blockInterval * blockInterval
        )

        // If bellow the sapling activation height, return the sapling activation height
        if (heightToLookAround <= saplingActivationHeight.value) {
            return saplingActivationHeight
        }

        return loadCheckpointAndEstimate(
            avgIntervalTime = avgIntervalTime,
            blockInterval = blockInterval,
            context = context,
            date = date,
            lookAround = heightToLookAround,
            network = network,
            saplingActivationHeight = saplingActivationHeight
        )
    }

    suspend fun estimateBirthdayDate(context: Context, blockHeight: BlockHeight, network: ZcashNetwork): Instant? {
        val blockInterval =
            if (network == ZcashNetwork.Mainnet) {
                CHECKPOINT_BLOCK_INTERVAL_MAINNET
            } else {
                CHECKPOINT_BLOCK_INTERVAL_TESTNET
            }

        var checkpointHeight = (blockHeight.value / blockInterval) * blockInterval

        var checkpoint: Checkpoint? = null

        while (checkpoint == null || checkpointHeight > blockInterval) {
            checkpoint =
                try {
                    loadNearest(
                        birthdayHeight = BlockHeight(checkpointHeight),
                        network = network,
                        context = context,
                    )
                } catch (_: Exception) {
                    null
                }

            if (checkpoint != null) {
                return Instant.fromEpochMilliseconds(checkpoint.epochTimeMillis)
            }

            checkpointHeight -= blockInterval
        }

        return null
    }

    @Suppress("LongParameterList", "ReturnCount")
    private suspend fun loadCheckpointAndEstimate(
        avgIntervalTime: Float,
        blockInterval: Int,
        context: Context,
        date: Instant,
        lookAround: Long,
        network: ZcashNetwork,
        saplingActivationHeight: BlockHeight
    ): BlockHeight {
        var heightToLookAround = lookAround

        // Phase 2, load checkpoint and evaluate against the given date
        val loadedCheckpoint =
            loadNearest(
                context,
                network,
                BlockHeight(heightToLookAround),
            )

        // The loaded checkpoint is exactly the one
        var hoursApart = (loadedCheckpoint.epochTimeMillis - date.toEpochMilliseconds()) / MILLIS_IN_HOUR
        if (hoursApart < 0 && abs(hoursApart) < avgIntervalTime) {
            return loadedCheckpoint.height
        }

        if (hoursApart < 0) {
            // The loaded checkpoint is lower, increase until reached the one
            var closestHeight = loadedCheckpoint.height
            while (abs(hoursApart) > avgIntervalTime) {
                heightToLookAround += blockInterval
                val newCheckpoint = loadNearest(context, network, BlockHeight.new(heightToLookAround))
                hoursApart = (newCheckpoint.epochTimeMillis - date.toEpochMilliseconds()) / MILLIS_IN_HOUR
                if (hoursApart < 0 && abs(hoursApart) < avgIntervalTime) {
                    return newCheckpoint.height
                } else if (hoursApart >= 0) {
                    return closestHeight
                }
                closestHeight = newCheckpoint.height
            }
        } else {
            // The loaded checkpoint is higher, decrease until reached the one
            while (hoursApart > 0) {
                heightToLookAround -= blockInterval
                val newCheckpoint = loadNearest(context, network, BlockHeight.new(heightToLookAround))
                hoursApart = (newCheckpoint.epochTimeMillis - date.toEpochMilliseconds()) / MILLIS_IN_HOUR
                if (hoursApart < 0) {
                    return newCheckpoint.height
                }
            }
        }

        return saplingActivationHeight
    }
}
