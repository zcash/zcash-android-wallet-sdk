package cash.z.ecc.android.sdk.internal.model

import io.ktor.client.engine.HttpClientEngineConfig

class TorHttpConfig : HttpClientEngineConfig() {
    /**
     * Specifies the Tor client to use for this HTTP engine.
     */
    var tor: TorClient? = null

    /**
     * Specified the maximum number of times that a failed request should be retried.
     * Set this property to `0` to disable retries.
     */
    @Suppress("MagicNumber")
    var retryLimit: Int = 3
}
