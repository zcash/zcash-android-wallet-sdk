package cash.z.ecc.android.sdk.internal

import cash.z.ecc.android.sdk.internal.model.ext.toBlockHeight
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.FastestServersResult
import cash.z.ecc.android.sdk.model.SdkFlags
import cash.z.ecc.android.sdk.model.ZcashNetwork
import cash.z.ecc.android.sdk.util.WalletClientFactory
import co.electriccoin.lightwallet.client.CombinedWalletClient
import co.electriccoin.lightwallet.client.ServiceMode
import co.electriccoin.lightwallet.client.model.BlockHeightUnsafe
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import co.electriccoin.lightwallet.client.model.LightWalletEndpointInfoUnsafe
import co.electriccoin.lightwallet.client.model.Response
import co.electriccoin.lightwallet.client.util.Disposable
import co.electriccoin.lightwallet.client.util.use
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

internal class FastestServerFetcher(
    private val backend: TypesafeBackend,
    private val network: ZcashNetwork,
    private val walletClientFactory: WalletClientFactory,
    private val sdkFlags: SdkFlags
) {
    operator fun invoke(servers: List<LightWalletEndpoint>): Flow<FastestServersResult> =
        flow {
            emit(FastestServersResult.Measuring)

            // MOB-1728: candidates share no Tor circuit -- each derives its own via
            // walletClientFactory.create(endpoint), same as before this file was touched. An
            // earlier version of this fix shared one isolated circuit across every candidate, on
            // the theory that too many concurrent Tor connections were the problem; that turned
            // out not to be it (see ValidateServerResult.rankingLatency's kdoc for what actually
            // was: the noisy ranking metric), the sharing was reverted, and parallelMapNotNull
            // below still runs every candidate concurrently regardless -- N independent circuits
            // built at once, same as it would have been without ever touching this file at all.
            val serversByRankingLatency =
                servers
                    .parallelMapNotNull {
                        validateServerEndpointAndMeasure(it)
                    }.sortedBy {
                        it.rankingLatency
                    }.mapIndexedNotNull { index, result ->
                        if (index <= K - 1 || result.rankingLatency <= LATENCY_THRESHOLD) {
                            Twig.debug { "Fastest Server: '${result.endpoint}' VALIDATED by SORTING by RPC latency" }
                            result
                        } else {
                            Twig.debug { "Fastest Server: '${result.endpoint}' RULED OUT by SORTING by RPC latency" }
                            null
                        }
                    }

            Twig.debug {
                "Fastest Server: '${serversByRankingLatency.map { it.endpoint }}' VALIDATED by MEASURING RPC latency"
            }

            emit(FastestServersResult.Validating(serversByRankingLatency.map { it.endpoint }.take(K)))

            val serversByGetBlockRangeTimeout =
                serversByRankingLatency
                    .asFlow()
                    .mapNotNull { result ->
                        result.use {
                            val didTimeOut =
                                withTimeoutOrNull(FETCH_THRESHOLD) {
                                    runCatching {
                                        val to = result.remoteInfo.blockHeightUnsafe
                                        val from = BlockHeightUnsafe((to.value - N).coerceAtLeast(0))
                                        // Fetched the same way as in `downloadBatchOfBlocks()`.
                                        result.lightWalletClient.getBlockRange(
                                            heightRange = from..to,
                                            serviceMode = ServiceMode.Direct
                                        )
                                    }.getOrNull()
                                } == null

                            if (didTimeOut) {
                                Twig.debug { "Fastest Server: '${result.endpoint}' RULED OUT by getBlockRange timeout" }
                                null
                            } else {
                                Twig.debug { "Fastest Server: '${result.endpoint}' VALIDATED by getBlockRange timeout" }
                                result.endpoint
                            }
                        }
                    }.take(K)
                    .toList()

            Twig.debug { "Fastest Server: '$serversByGetBlockRangeTimeout' VALIDATED by getBlockRange timeout" }

            emit(FastestServersResult.Done(serversByGetBlockRangeTimeout))
        }.flowOn(Dispatchers.Default)

    @Suppress("LongMethod", "ReturnCount", "CyclomaticComplexMethod")
    private suspend fun validateServerEndpointAndMeasure(endpoint: LightWalletEndpoint): ValidateServerResult? {
        fun logRuledOut(
            reason: String,
            throwable: Throwable? = null
        ) {
            val message =
                "Fastest Server: Server '$endpoint' RULED OUT during validating and measuring RPC " +
                    "latency. Reason: $reason"

            if (throwable != null) {
                Twig.debug(throwable) { message }
            } else {
                Twig.debug { message }
            }
        }

        val lightWalletClient =
            kotlin.runCatching { walletClientFactory.create(endpoint) }.getOrNull()
                ?: return null

        // Not timed: MOB-1728 ranks candidates on getLatestBlockHeightDuration alone (see
        // ValidateServerResult.rankingLatency's kdoc for why), so this call's own duration isn't
        // needed -- only remoteInfo, for the checks below.
        // 5 seconds timeout in case server is very unresponsive
        val remoteInfo: LightWalletEndpointInfoUnsafe? =
            withTimeoutOrNull(5.seconds) {
                when (
                    val response =
                        lightWalletClient.getServerInfo(
                            sdkFlags ifTor
                                ServiceMode.Group(
                                    "validateServerEndpointAndMeasure(${endpoint.host}:${endpoint.port})"
                                )
                        )
                ) {
                    is Response.Success -> {
                        response.result
                    }

                    is Response.Failure -> {
                        logRuledOut("getServerInfo failed", response.toThrowable())
                        null
                    }
                }
            }

        if (remoteInfo == null) {
            lightWalletClient.dispose()
            return null
        }

        // Check network type
        if (!remoteInfo.matchingNetwork(network.networkName)) {
            logRuledOut("matchingNetwork failed")
            lightWalletClient.dispose()
            return null
        }

        // Check sapling activation height
        runCatching {
            val remoteSaplingActivationHeight = remoteInfo.saplingActivationHeightUnsafe.toBlockHeight()
            if (network.saplingActivationHeight != remoteSaplingActivationHeight) {
                logRuledOut("invalid saplingActivationHeight")
                lightWalletClient.dispose()
                return null
            }
        }.getOrElse {
            logRuledOut("saplingActivationHeight failed", it)
            lightWalletClient.dispose()
            return null
        }

        val currentChainTip: BlockHeight
        val getLatestBlockHeightDuration =
            measureTime {
                currentChainTip =
                    when (
                        val response =
                            lightWalletClient.getLatestBlockHeight(
                                serviceMode =
                                    sdkFlags ifTor
                                        ServiceMode.Group(
                                            "validateServerEndpointAndMeasure(${endpoint.host}:${endpoint.port})"
                                        )
                            )
                    ) {
                        is Response.Success -> {
                            runCatching { response.result.toBlockHeight() }.getOrElse {
                                logRuledOut("toBlockHeight failed", it)
                                lightWalletClient.dispose()
                                return null
                            }
                        }

                        is Response.Failure -> {
                            logRuledOut("getLatestBlockHeight failed", response.toThrowable())
                            lightWalletClient.dispose()
                            return null
                        }
                    }
            }

        val sdkBranchId =
            runCatching {
                "%x".format(
                    Locale.ROOT,
                    backend.getBranchIdForHeight(currentChainTip)
                )
            }.getOrElse {
                logRuledOut("getBranchIdForHeight failed", it)
                lightWalletClient.dispose()
                return null
            }

        if (!remoteInfo.consensusBranchId.equals(sdkBranchId, true)) {
            logRuledOut("consensusBranchId does not match")
            lightWalletClient.dispose()
            return null
        }

        if (remoteInfo.estimatedHeight >= remoteInfo.blockHeightUnsafe.value + SYNCED_THRESHOLD_BLOCKS) {
            logRuledOut("estimatedHeight does not match")
            lightWalletClient.dispose()
            return null
        }

        Twig.debug { "Fastest Server: Server '$endpoint' VALIDATED during validating and measuring RPC latency" }

        return ValidateServerResult(
            remoteInfo = remoteInfo,
            lightWalletClient = lightWalletClient,
            endpoint = endpoint,
            getLatestBlockHeightDuration = getLatestBlockHeightDuration
        )
    }

    // MOB-1728: coroutineScope must wrap the whole map, not be created per element -- a
    // per-element coroutineScope { async { ... } } suspends on that one child before returning,
    // so map (plain sequential iteration) never starts the next element until the previous one's
    // block() has fully completed. That was this function's actual behavior despite its name
    // until this fix (see the fixed git blame for the previous, sequential version and the
    // comment this replaced in invoke() above).
    private suspend inline fun <T, R> Iterable<T>.parallelMapNotNull(crossinline block: suspend (T) -> R?): List<R> =
        coroutineScope {
            map { async { block(it) } }
        }.awaitAll().filterNotNull()
}

private data class ValidateServerResult(
    val remoteInfo: LightWalletEndpointInfoUnsafe,
    val lightWalletClient: CombinedWalletClient,
    val endpoint: LightWalletEndpoint,
    val getLatestBlockHeightDuration: Duration,
) : Disposable {
    // MOB-1728: this used to rank on the mean of getServerInfoDuration and
    // getLatestBlockHeightDuration, but both calls go through the same
    // ServiceMode.Group(endpoint) key, so CombinedWalletClientImpl's per-serviceMode cache
    // (getOrCreate) makes the SECOND call reuse the Tor circuit the first call just built —
    // getServerInfoDuration paid a one-time, highly variable circuit-build cost that had nothing
    // to do with this server's real responsiveness, while getLatestBlockHeightDuration measures
    // the actual round-trip over an already-warm connection. Averaging the two baked half that
    // circuit-build noise into the ranking metric, which — live-verified: 4/4 consecutive app
    // launches on a 7-candidate mainnet config, each different — was enough to make the "fastest"
    // server flip almost every single evaluation, forcing a real endpoint switch (and the full
    // Synchronizer rebuild that comes with it) on nearly every app launch. Ranking on the warm
    // measurement alone removes that noise source; getServerInfoDuration is no longer measured.
    val rankingLatency = getLatestBlockHeightDuration

    override suspend fun dispose() {
        lightWalletClient.dispose()
    }
}

/**
 * Amount of fastest servers to return.
 */
private const val K = 3

/**
 * Latest N amount of blocks.
 */
private const val N = 100

/**
 * Threshold for warm RPC call latency (see [ValidateServerResult.rankingLatency]).
 */
private val LATENCY_THRESHOLD = 300.milliseconds

/**
 * Threshold for getBlockRange RPC call latency of latest [N] blocks.
 */
private val FETCH_THRESHOLD = 60.seconds

private const val SYNCED_THRESHOLD_BLOCKS = 288
