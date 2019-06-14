package cash.z.wallet.sdk.ext

import android.content.Context
import cash.z.wallet.sdk.data.twig
import cash.z.wallet.sdk.rpc.Service
import kotlinx.coroutines.delay
import java.io.File

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

internal fun dbExists(appContext: Context, dbFileName: String): Boolean {
    return File(appContext.getDatabasePath(dbFileName).absolutePath).exists()
}