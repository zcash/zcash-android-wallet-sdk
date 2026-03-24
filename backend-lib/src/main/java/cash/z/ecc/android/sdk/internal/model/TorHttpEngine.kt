package cash.z.ecc.android.sdk.internal.model

import io.ktor.client.engine.HttpClientEngineBase
import io.ktor.client.engine.callContext
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.Headers
import io.ktor.http.HeadersBuilder
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpProtocolVersion
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.util.date.GMTDate
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.InternalAPI
import kotlinx.coroutines.runBlocking

class TorHttpEngine(
    override val config: TorHttpConfig
) : HttpClientEngineBase("TorEngine") {
    private val torClient: TorClient
        get() = config.tor!!

    init {
        require(config.tor != null) {
            "TorHttp requires a TorClient"
        }
        @Suppress("MagicNumber")
        require(config.retryLimit in 0..255) {
            "retryLimit must be a valid 8-bit unsigned integer"
        }
    }

    @OptIn(InternalAPI::class)
    override suspend fun execute(data: HttpRequestData): HttpResponseData {
        val callContext = callContext()
        val requestTime = GMTDate()

        val response =
            when (data.method.value) {
                "GET" -> executeGet(data)
                "POST" -> executePost(data)
                else -> throw UnsupportedHttpMethodOverTor("Unsupported HTTP method " + data.method)
            }

        val headers = HeadersBuilder()
        response.headers.iterator().forEach { headers.append(it.key, it.value) }

        return HttpResponseData(
            statusCode = HttpStatusCode(value = response.status, description = ""),
            requestTime = requestTime,
            headers = headers.build(),
            version = HttpProtocolVersion.parse(response.version),
            body = ByteReadChannel(response.body),
            callContext = callContext
        )
    }

    private suspend fun executeGet(data: HttpRequestData): JniHttpResponseBytes =
        when (data.body) {
            is OutgoingContent.NoContent -> {
                torClient.httpGet(
                    data.url.toString(),
                    data.headers.toJniHeaders(),
                    config.retryLimit
                )
            }

            else -> {
                throw IllegalArgumentException("HTTP GET does not support body")
            }
        }

    @OptIn(InternalAPI::class)
    private suspend fun executePost(data: HttpRequestData): JniHttpResponseBytes =
        when (data.body) {
            is OutgoingContent.NoContent -> {
                torClient.httpPost(
                    data.url.toString(),
                    data.headers.toJniHeaders(),
                    ByteArray(0),
                    config.retryLimit
                )
            }

            is OutgoingContent.ByteArrayContent -> {
                torClient.httpPost(
                    data.url.toString(),
                    HeadersBuilder()
                        .apply {
                            appendAll(data.headers)
                            appendAll(data.body.headers)
                            data.body.contentType?.let { append(HttpHeaders.ContentType, it.toString()) }
                        }.build()
                        .toJniHeaders(),
                    (data.body as OutgoingContent.ByteArrayContent).bytes(),
                    config.retryLimit
                )
            }

            else -> {
                throw IllegalArgumentException("HTTP POST requires ByteArray body")
            }
        }

    override fun close() {
        runBlocking { torClient.dispose() }
        super.close()
    }
}

private fun Headers.toJniHeaders(): List<JniHttpHeader> =
    entries().flatMap { (key, values) -> values.map { value -> JniHttpHeader(key, value) } }

class UnsupportedHttpMethodOverTor(
    message: String
) : RuntimeException(message)
