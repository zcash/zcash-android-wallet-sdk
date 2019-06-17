package cash.z.wallet.sdk.ext

import android.content.Context
import cash.z.wallet.sdk.data.twig
import cash.z.wallet.sdk.rpc.Service
import kotlinx.coroutines.delay
import java.io.File
import kotlin.random.Random

inline fun Int.toBlockHeight(): Service.BlockID = Service.BlockID.newBuilder().setHeight(this.toLong()).build()

suspend inline fun retryUpTo(retries: Int, initialDelay: Int = 10, block: () -> Unit) {
    var failedAttempts = 0
    while (failedAttempts < retries) {
        try {
            block()
            return
        } catch (t: Throwable) {
            failedAttempts++
            if (failedAttempts >= retries) throw t
            val duration = Math.pow(initialDelay.toDouble(), failedAttempts.toDouble()).toLong()
            twig("failed due to $t retrying (${failedAttempts + 1}/$retries) in ${duration}s...")
            delay(duration)
        }
    }
}

suspend inline fun retryWithBackoff(noinline onErrorListener: ((Throwable) -> Boolean)? = null, initialDelayMillis: Long = 1000L, maxDelayMillis: Long = DEFAULT_MAX_BACKOFF_INTERVAL, block: () -> Unit) {
    var sequence = 0 // count up to the max and then reset to half. So that we don't repeat the max but we also don't repeat too much.
    while (true) {
        try {
            block()
            return
        } catch (t: Throwable) {
            // offer to listener first
            if (onErrorListener?.invoke(t) == false) {
                throw t
            }

            sequence++
            // I^(1/4)n + jitter
            var duration = Math.pow(initialDelayMillis.toDouble(), (sequence.toDouble()/4.0)).toLong() + Random.nextLong(1000L)
            if (duration > maxDelayMillis) {
                duration = maxDelayMillis - Random.nextLong(1000L) // include jitter but don't exceed max delay
                sequence /= 2
            }
            twig("Failed due to $t retrying in ${duration}ms...")
            delay(duration)
        }
    }
}

internal fun dbExists(appContext: Context, dbFileName: String): Boolean {
    return File(appContext.getDatabasePath(dbFileName).absolutePath).exists()
}