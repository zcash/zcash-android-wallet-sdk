package cash.z.ecc.android.sdk.internal.storage.block

import androidx.annotation.VisibleForTesting
import cash.z.ecc.android.sdk.internal.Twig
import cash.z.ecc.android.sdk.internal.TypesafeBackend
import cash.z.ecc.android.sdk.internal.ext.createNewFileSuspend
import cash.z.ecc.android.sdk.internal.ext.deleteRecursivelySuspend
import cash.z.ecc.android.sdk.internal.ext.deleteSuspend
import cash.z.ecc.android.sdk.internal.ext.existsSuspend
import cash.z.ecc.android.sdk.internal.ext.isDirectorySuspend
import cash.z.ecc.android.sdk.internal.ext.listFilesSuspend
import cash.z.ecc.android.sdk.internal.ext.mkdirsSuspend
import cash.z.ecc.android.sdk.internal.ext.renameToSuspend
import cash.z.ecc.android.sdk.internal.ext.toHexReversed
import cash.z.ecc.android.sdk.internal.ext.writeBytesSuspend
import cash.z.ecc.android.sdk.internal.model.JniBlockMeta
import cash.z.ecc.android.sdk.internal.repository.CompactBlockRepository
import cash.z.ecc.android.sdk.model.BlockHeight
import co.electriccoin.lightwallet.client.model.CompactBlockUnsafe
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import java.io.File

internal class FileCompactBlockRepository(
    private val blocksDirectory: File,
    private val backend: TypesafeBackend
) : CompactBlockRepository {
    override suspend fun getLatestHeight() = backend.getLatestCacheHeight()

    override suspend fun findCompactBlock(height: BlockHeight) = backend.findBlockMetadata(height)

    override suspend fun write(blocks: Flow<CompactBlockUnsafe>): List<JniBlockMeta> {
        val processingBlocks = mutableListOf<JniBlockMeta>()
        val metaDataBuffer = mutableListOf<JniBlockMeta>()
        blocks.collect { block ->
            val tmpFile = block.createTemporaryFile(blocksDirectory)
            // write compact block bytes
            tmpFile.writeBytesSuspend(block.compactBlockBytes)
            // buffer metadata
            metaDataBuffer.add(block.toJniMetaData())

            val isFinalizeSuccessful = tmpFile.finalizeFile()
            check(isFinalizeSuccessful) {
                "Failed to finalize file: ${tmpFile.absolutePath}"
            }

            if (metaDataBuffer.isBufferFull()) {
                processingBlocks.addAll(metaDataBuffer)
                writeAndClearBuffer(metaDataBuffer)
            }
        }

        if (metaDataBuffer.isNotEmpty()) {
            processingBlocks.addAll(metaDataBuffer)
            writeAndClearBuffer(metaDataBuffer)
        }

        return processingBlocks
    }

    /*
     * Write block metadata to storage when the buffer is full or when we reached the current range end.
     */
    private suspend fun writeAndClearBuffer(metaDataBuffer: MutableList<JniBlockMeta>) {
        runCatching {
            backend.writeBlockMetadata(metaDataBuffer)
        }.onFailure {
            Twig.error { "Failed to write block metadata with $it" }
            // We should inform the caller about the operation failure as well
        }
        metaDataBuffer.clear()
    }

    override suspend fun rewindTo(height: BlockHeight) = backend.rewindBlockMetadataToHeight(height)

    override suspend fun deleteAllCompactBlockFiles(): Boolean {
        Twig.verbose { "Deleting all blocks from directory ${blocksDirectory.path}" }

        if (blocksDirectory.existsSuspend()) {
            blocksDirectory.listFilesSuspend()?.forEach {
                val result =
                    if (it.isDirectorySuspend()) {
                        it.deleteRecursivelySuspend()
                    } else {
                        it.deleteSuspend()
                    }
                if (!result) {
                    return false
                }
            }
        }
        return true
    }

    override suspend fun deleteCompactBlockFiles(blocks: List<JniBlockMeta>): Boolean {
        Twig.verbose { "Deleting ${blocks.size} blocks from directory ${blocksDirectory.path}" }

        if (blocksDirectory.existsSuspend()) {
            val allDeleted = coroutineScope {
                blocks.map { block ->
                    async {
                        val blockFile = block.getFile(blocksDirectory)
                        if (!blockFile.existsSuspend()) return@async true
                        blockFile.deleteSuspend()
                    }
                }.awaitAll().all { it }
            }
            if (!allDeleted) return false
        }
        return true
    }

    companion object {
        /**
         * The name of the directory for downloading blocks
         */
        const val BLOCKS_DOWNLOAD_DIRECTORY = "blocks"

        /**
         * The suffix for temporary files
         */
        const val TEMPORARY_FILENAME_SUFFIX = ".tmp"

        /**
         * The suffix for block file name
         */
        const val BLOCK_FILENAME_SUFFIX = "-compactblock"

        // TODO [#1555]: Keep BLOCKS_METADATA_BUFFER_SIZE the same as current SYNC_BATCH_SIZE
        // TODO [#1555]: https://github.com/Electric-Coin-Company/zcash-android-wallet-sdk/issues/1555

        /**
         * The size of block meta data buffer
         *
         * Note: we should keep this size as close as possible to [CompactBlockProcessor.SYNC_BATCH_SIZE]
         */
        const val BLOCKS_METADATA_BUFFER_SIZE = 1000

        /**
         * @param blockCacheRoot The root directory for the compact block cache (contains the database and a
         * subdirectory for the blocks).
         */
        suspend fun new(
            blockCacheRoot: File,
            backend: TypesafeBackend
        ): FileCompactBlockRepository {
            // create and check cache directories
            val blocksDirectory =
                File(blockCacheRoot, BLOCKS_DOWNLOAD_DIRECTORY).also {
                    it.mkdirsSuspend()
                }
            if (!blocksDirectory.existsSuspend()) {
                error("${blocksDirectory.path} directory does not exist and could not be created.")
            }

            backend.initBlockMetaDb()

            return FileCompactBlockRepository(blocksDirectory, backend)
        }
    }
}

//
// Private helper functions
//
@Suppress("MaxLineLength")
private fun List<JniBlockMeta>.isBufferFull(): Boolean = size % FileCompactBlockRepository.BLOCKS_METADATA_BUFFER_SIZE == 0

private fun CompactBlockUnsafe.toJniMetaData(): JniBlockMeta = JniBlockMeta.new(this)

private fun JniBlockMeta.createFilename(): String {
    val hashHex = hash.toHexReversed()
    return "$height-$hashHex${FileCompactBlockRepository.BLOCK_FILENAME_SUFFIX}"
}

@VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
private fun JniBlockMeta.getFile(blocksDirectory: File): File = File(blocksDirectory, createFilename())

private fun CompactBlockUnsafe.createFilename(): String {
    val hashHex = hash.toHexReversed()
    return "$height-$hashHex${FileCompactBlockRepository.BLOCK_FILENAME_SUFFIX}"
}

@VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
internal suspend fun CompactBlockUnsafe.createTemporaryFile(blocksDirectory: File): File {
    val tempFileName = "${createFilename()}${FileCompactBlockRepository.TEMPORARY_FILENAME_SUFFIX}"
    val tmpFile = File(blocksDirectory, tempFileName)

    if (tmpFile.existsSuspend()) {
        tmpFile.deleteSuspend()
    }
    tmpFile.createNewFileSuspend()

    return tmpFile
}

@VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
internal suspend fun File.finalizeFile(): Boolean {
    // rename the file
    val newFile = File(absolutePath.dropLast(FileCompactBlockRepository.TEMPORARY_FILENAME_SUFFIX.length))
    return renameToSuspend(newFile)
}
