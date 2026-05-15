package cash.z.ecc.android.sdk.internal.model

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory

/**
 * An `HttpClientEngine` implementation that makes requests over the provided `TorClient`.
 *
 * The engine does not isolate individual connections from each other. It also does not
 * isolate its usage from other Tor usage; construct it with a `TorClient` obtained from
 * `isolatedTorClient()` if you need this.
 */
data object TorHttp : HttpClientEngineFactory<TorHttpConfig> {
    override fun create(block: TorHttpConfig.() -> Unit): HttpClientEngine =
        TorHttpEngine(TorHttpConfig().apply(block))
}
