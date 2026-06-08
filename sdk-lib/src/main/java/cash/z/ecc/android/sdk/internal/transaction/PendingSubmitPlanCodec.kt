package cash.z.ecc.android.sdk.internal.transaction

import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal object PendingSubmitPlanCodec {
    fun decode(storedPlans: String): Map<String, List<LightWalletEndpoint>> {
        val lines =
            storedPlans
                .lineSequence()
                .filter { it.isNotBlank() }
                .toList()

        if (!lines.hasSupportedVersion()) {
            return emptyMap()
        }

        val plans = mutableMapOf<String, List<LightWalletEndpoint>>()
        lines.forEach { line ->
            val transactionId = line.substringBefore(KEY_VALUE_SEPARATOR)
            if (transactionId != FIELD_VERSION &&
                transactionId.isNotBlank() &&
                line.contains(KEY_VALUE_SEPARATOR)
            ) {
                plans[transactionId] = line.substringAfter(KEY_VALUE_SEPARATOR).toEndpoints()
            }
        }
        return plans
    }

    fun encode(plans: Map<String, List<LightWalletEndpoint>>): String =
        buildString {
            append(FIELD_VERSION)
            append(KEY_VALUE_SEPARATOR)
            append(VERSION)
            plans.toSortedMap().forEach { (transactionId, endpoints) ->
                appendLine()
                append(transactionId)
                append(KEY_VALUE_SEPARATOR)
                append(endpoints.toStorageText())
            }
        }

    private fun String.toEndpoints(): List<LightWalletEndpoint> =
        if (isBlank()) {
            emptyList()
        } else {
            split(ENDPOINT_SEPARATOR)
                .mapNotNull { it.toEndpoint() }
                .distinct()
        }

    private fun List<LightWalletEndpoint>.toStorageText(): String =
        joinToString(ENDPOINT_SEPARATOR) { endpoint ->
            listOf(
                endpoint.host.encode(),
                endpoint.port.toString(),
                endpoint.isSecure.toString()
            ).joinToString(FIELD_SEPARATOR)
        }

    private fun String.toEndpoint(): LightWalletEndpoint? {
        val fields = split(FIELD_SEPARATOR)
        val endpoint =
            if (fields.size == ENDPOINT_FIELD_COUNT) {
                val host = fields[ENDPOINT_HOST_INDEX].decode()
                val port = fields[ENDPOINT_PORT_INDEX].toIntOrNull()
                val isSecure = fields[ENDPOINT_IS_SECURE_INDEX].toBooleanStrictOrNull()
                if (host.isBlank() || port == null || isSecure == null) {
                    null
                } else {
                    LightWalletEndpoint(host, port, isSecure)
                }
            } else {
                null
            }
        return endpoint
    }

    private fun List<String>.hasSupportedVersion(): Boolean =
        firstOrNull { line ->
            line.contains(KEY_VALUE_SEPARATOR) &&
                line.substringBefore(KEY_VALUE_SEPARATOR) == FIELD_VERSION
        }?.substringAfter(KEY_VALUE_SEPARATOR)?.toIntOrNull() == VERSION

    private fun String.encode() = URLEncoder.encode(this, StandardCharsets.UTF_8.name())

    private fun String.decode() = URLDecoder.decode(this, StandardCharsets.UTF_8.name())

    private const val VERSION = 1
    private const val FIELD_VERSION = "version"
    private const val KEY_VALUE_SEPARATOR = "="
    private const val ENDPOINT_SEPARATOR = "|"
    private const val FIELD_SEPARATOR = ","
    private const val ENDPOINT_FIELD_COUNT = 3
    private const val ENDPOINT_HOST_INDEX = 0
    private const val ENDPOINT_PORT_INDEX = 1
    private const val ENDPOINT_IS_SECURE_INDEX = 2
}
