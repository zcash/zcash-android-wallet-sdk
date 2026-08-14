@file:Suppress("ktlint:standard:filename")

package cash.z.ecc.android.sdk.internal.ext

import cash.z.ecc.android.sdk.ext.fromHex
import java.util.Locale

internal fun ByteArray.toHexReversed(): String {
    val sb = StringBuilder(size * 2)
    var i = size - 1
    while (i >= 0) {
        sb.append(String.format(Locale.ROOT, "%02x", this[i--]))
    }
    return sb.toString()
}

internal fun String.fromHexReversed(): ByteArray = fromHex().reversedArray()
