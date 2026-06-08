package cash.z.ecc.android.sdk.ext

import java.util.Locale

private const val HEX_CHARS_PER_BYTE = 2
private const val HEX_RADIX = 16

fun ByteArray.toHex(): String {
    val sb = StringBuilder(size * HEX_CHARS_PER_BYTE)
    for (b in this) {
        sb.append(String.format(Locale.ROOT, "%02x", b))
    }
    return sb.toString()
}

@Suppress("MagicNumber")
fun String.fromHex(): ByteArray {
    require(length % HEX_CHARS_PER_BYTE == 0) {
        "Hex string must have an even length, got $length"
    }

    val len = length
    val data = ByteArray(len / HEX_CHARS_PER_BYTE)
    var i = 0
    while (i < len) {
        val high = Character.digit(this[i], HEX_RADIX)
        val low = Character.digit(this[i + 1], HEX_RADIX)
        require(high >= 0 && low >= 0) {
            "Invalid hex character at index $i"
        }
        data[i / 2] =
            ((high shl 4) + low).toByte()
        i += HEX_CHARS_PER_BYTE
    }
    return data
}
