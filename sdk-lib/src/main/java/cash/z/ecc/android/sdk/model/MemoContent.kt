@file:Suppress("MagicNumber")

package cash.z.ecc.android.sdk.model

import kotlin.text.Charsets.UTF_8

/**
 * ZIP-302 compliant Memo representation.
 * See https://zips.z.cash/zip-0302
 */
sealed interface MemoContent {
    /**
     * Empty memo (0xF6 followed by 511 null bytes)
     */
    data object Empty : MemoContent

    /**
     * Text memo (first byte 0x00-0xF4, UTF-8 encoded text)
     */
    data class Text(
        val text: MemoText
    ) : MemoContent

    /**
     * Future memo format (reserved for future ZIP updates)
     * - First byte is 0xF5
     * - First byte is 0xF6 with non-zero bytes following
     * - First byte is 0xF7-0xFE
     */
    data class Future(
        val bytes: MemoBytes
    ) : MemoContent

    /**
     * Arbitrary data memo (first byte is 0xFF)
     * The data doesn't include the 0xFF leading byte.
     */
    data class Arbitrary(
        val data: FirstClassByteArray
    ) : MemoContent

    /**
     * Represent memo as String. Only `.text` memo can be represented as String.
     * @return Valid String if it can be created from memo; otherwise `null`.
     */
    fun toStringOrNull(): String? =
        when (this) {
            is Empty, is Future, is Arbitrary -> null
            is Text -> text.string
        }

    /**
     * Converts this memo to MemoBytes representation
     */
    @Throws(MemoException::class)
    fun asMemoBytes(): MemoBytes =
        when (this) {
            is Empty -> {
                MemoBytes.empty()
            }

            is Text -> {
                val bytes = text.string.toByteArray(UTF_8)
                MemoBytes.fromBytes(bytes)
            }

            is Future -> {
                bytes
            }

            is Arbitrary -> {
                val arbitraryBytes = ByteArray(data.byteArray.size + 1)
                arbitraryBytes[0] = 0xFF.toByte()
                data.byteArray.copyInto(arbitraryBytes, destinationOffset = 1)
                MemoBytes.fromBytes(arbitraryBytes)
            }
        }

    companion object {
        /**
         * The maximum length of a memo in bytes.
         */
        const val MAX_MEMO_LENGTH_BYTES = 512

        /**
         * Parses the given bytes as per ZIP-302
         */
        @Throws(MemoException::class)
        fun fromBytes(bytes: ByteArray): MemoContent = MemoBytes.fromBytes(bytes).intoMemo()

        /**
         * Creates a text memo from a string
         */
        @Throws(MemoException::class)
        fun fromString(string: String): MemoContent = Text(MemoText.fromString(string))

        /**
         * Use this function to know the size of the text in memo bytes.
         */
        fun length(memoText: String): Int = memoText.sizeInUtf8Bytes()
    }
}

/**
 * Exceptions related to Memo operations
 */
sealed class MemoException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause) {
    /**
     * Invalid UTF-8 bytes detected when attempting to create a Text Memo
     */
    class InvalidUTF8 : MemoException("Invalid UTF-8 bytes detected in memo")

    /**
     * Trailing null-bytes were found when attempting to create a Text memo
     */
    class EndsWithNullBytes : MemoException("Memo text ends with null bytes")

    /**
     * The resulting bytes provided are too long to be stored as a Memo
     */
    class TooLong(
        val length: Int
    ) : MemoException("Memo is too long: $length bytes (max: ${MemoContent.MAX_MEMO_LENGTH_BYTES})")
}

/**
 * A wrapper on `String` so that `Memo` can't be created with an invalid String
 */
@ConsistentCopyVisibility
data class MemoText internal constructor(
    val string: String
) {
    companion object {
        /**
         * Creates a MemoText from a string, validating it doesn't end with null bytes
         * and doesn't exceed the maximum length.
         */
        @Throws(MemoException::class)
        fun fromString(string: String): MemoText {
            // Check for trailing null bytes
            val trimmedString = string.trimEnd('\u0000')
            if (trimmedString.length != string.length) {
                throw MemoException.EndsWithNullBytes()
            }

            // Check length
            val byteLength = string.sizeInUtf8Bytes()
            if (byteLength > MemoContent.MAX_MEMO_LENGTH_BYTES) {
                throw MemoException.TooLong(byteLength)
            }

            return MemoText(string)
        }
    }
}

/**
 * MemoBytes represents the raw 512-byte memo data as stored in transactions.
 */
@ConsistentCopyVisibility
data class MemoBytes internal constructor(
    val bytes: FirstClassByteArray
) {
    init {
        require(bytes.byteArray.size == MemoContent.MAX_MEMO_LENGTH_BYTES) {
            "MemoBytes must be exactly ${MemoContent.MAX_MEMO_LENGTH_BYTES} bytes"
        }
    }

    companion object {
        /**
         * Creates MemoBytes from the given bytes, padding to 512 bytes if needed.
         */
        @Throws(MemoException::class)
        fun fromBytes(bytes: ByteArray): MemoBytes {
            if (bytes.size > MemoContent.MAX_MEMO_LENGTH_BYTES) {
                throw MemoException.TooLong(bytes.size)
            }

            val paddedBytes = ByteArray(MemoContent.MAX_MEMO_LENGTH_BYTES)
            bytes.copyInto(paddedBytes)
            return MemoBytes(FirstClassByteArray(paddedBytes))
        }

        /**
         * Creates an empty memo (0xF6 followed by 511 null bytes)
         */
        fun empty(): MemoBytes {
            val emptyBytes = ByteArray(MemoContent.MAX_MEMO_LENGTH_BYTES)
            emptyBytes[0] = 0xF6.toByte()
            return MemoBytes(FirstClassByteArray(emptyBytes))
        }
    }

    /**
     * Returns raw bytes, excluding null padding
     */
    fun unpaddedRawBytes(): ByteArray {
        val lastNonZeroIndex = bytes.byteArray.indexOfLast { it != 0.toByte() }
        if (lastNonZeroIndex == -1) {
            return ByteArray(0)
        }
        return bytes.byteArray.copyOfRange(0, lastNonZeroIndex + 1)
    }

    /**
     * Parsing of the MemoBytes in terms of ZIP-302 Specification
     * See https://zips.z.cash/zip-0302#specification
     *
     * Returns:
     * - `.text(MemoText)` if the first byte has a value of 0xF4 or smaller
     * - `.future(MemoBytes)` if the memo matches any of these patterns (reserved for future updates):
     *   - The first byte has a value of 0xF5
     *   - The first byte has a value of 0xF6, and the remaining 511 bytes are not all 0x00
     *   - The first byte has a value between 0xF7 and 0xFE inclusive
     * - `.arbitrary(Bytes)` when the first byte is 0xFF (Bytes don't include the 0xFF leading byte)
     *
     * @throws MemoException.InvalidUTF8 when the case of Text memo is found but invalid UTF-8 is detected
     */
    @Throws(MemoException::class)
    fun intoMemo(): MemoContent {
        val firstByte = bytes.byteArray[0].toInt() and 0xFF

        return when (firstByte) {
            in 0x00..0xF4 -> {
                // Text memo
                val unpaddedBytes = unpaddedRawBytes()
                val text =
                    try {
                        String(unpaddedBytes, UTF_8)
                    } catch (_: Exception) {
                        throw MemoException.InvalidUTF8()
                    }

                // Verify no replacement characters were inserted
                if (text.contains('\uFFFD')) {
                    throw MemoException.InvalidUTF8()
                }

                MemoContent.Text(MemoText.fromString(text))
            }

            0xF5 -> {
                // Future memo format
                MemoContent.Future(this)
            }

            0xF6 -> {
                // Check if remaining bytes are all zero (empty memo) or not (future memo)
                val hasNonZero = bytes.byteArray.drop(1).any { it != 0.toByte() }
                if (hasNonZero) {
                    MemoContent.Future(this)
                } else {
                    MemoContent.Empty
                }
            }

            in 0xF7..0xFE -> {
                // Future memo format
                MemoContent.Future(this)
            }

            0xFF -> {
                // Arbitrary data memo (exclude the 0xFF prefix)
                val arbitraryData = bytes.byteArray.copyOfRange(1, bytes.byteArray.size)
                MemoContent.Arbitrary(FirstClassByteArray(arbitraryData))
            }

            else -> {
                // Shouldn't reach here, but treat as future memo
                MemoContent.Future(this)
            }
        }
    }
}

private fun String.sizeInUtf8Bytes() = toByteArray(UTF_8).size
