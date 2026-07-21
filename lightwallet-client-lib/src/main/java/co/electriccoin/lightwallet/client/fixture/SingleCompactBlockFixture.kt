package co.electriccoin.lightwallet.client.fixture

import co.electriccoin.lightwallet.client.model.CompactBlockUnsafe
import java.nio.ByteBuffer

/**
 * Used for getting single mocked compact block for processing and persisting purposes.
 */
internal object SingleCompactBlockFixture {
    internal const val DEFAULT_HEIGHT = 500_000L
    internal const val DEFAULT_TIME = 0
    internal const val DEFAULT_SAPLING_OUTPUT_COUNT = 1u
    internal const val DEFAULT_ORCHARD_OUTPUT_COUNT = 2u
    internal const val DEFAULT_IRONWOOD_OUTPUT_COUNT = 0u
    internal const val DEFAULT_HASH = DEFAULT_HEIGHT
    internal const val DEFAULT_BLOCK_BYTES = DEFAULT_HEIGHT

    internal fun heightToFixtureData(height: Long) = BytesConversionHelper.longToBytes(height)

    internal fun fixtureDataToHeight(byteArray: ByteArray) = BytesConversionHelper.bytesToLong(byteArray)

    @Suppress("LongParameterList")
    fun new(
        height: Long = DEFAULT_HEIGHT,
        hash: ByteArray = heightToFixtureData(height),
        time: Int = DEFAULT_TIME,
        saplingOutputsCount: UInt = DEFAULT_SAPLING_OUTPUT_COUNT,
        orchardOutputsCount: UInt = DEFAULT_ORCHARD_OUTPUT_COUNT,
        ironwoodOutputsCount: UInt = DEFAULT_IRONWOOD_OUTPUT_COUNT,
        blockBytes: ByteArray = heightToFixtureData(DEFAULT_BLOCK_BYTES)
    ): CompactBlockUnsafe =
        CompactBlockUnsafe(
            height = height,
            hash = hash,
            time = time,
            saplingOutputsCount = saplingOutputsCount,
            orchardOutputsCount = orchardOutputsCount,
            ironwoodOutputsCount = ironwoodOutputsCount,
            compactBlockBytes = blockBytes
        )
}

private object BytesConversionHelper {
    private fun getBuffer(): ByteBuffer = ByteBuffer.allocate(java.lang.Long.BYTES)

    fun longToBytes(x: Long): ByteArray {
        val buffer = getBuffer()
        buffer.putLong(0, x)
        return buffer.array()
    }

    fun bytesToLong(bytes: ByteArray): Long {
        val buffer = getBuffer()
        buffer.put(bytes, 0, bytes.size)
        buffer.flip()
        return buffer.long
    }
}
