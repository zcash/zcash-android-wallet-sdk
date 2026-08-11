package cash.z.ecc.android.sdk.internal

import android.content.Context
import cash.z.ecc.android.sdk.CloseableSynchronizer
import cash.z.ecc.android.sdk.WalletInitMode
import cash.z.ecc.android.sdk.model.AccountCreateSetup
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.ZcashNetwork
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import com.zodl.slipstream.SlipstreamSynchronizer

/**
 * The `IS_SLIPSTREAM_ENABLED=true` variant of the engine seam, compiled only when this SDK build
 * includes `:slipstream-lib`.
 */
internal val engineSynchronizerFactory: SynchronizerEngineFactory = SlipstreamEngineFactory

private object SlipstreamEngineFactory : SynchronizerEngineFactory {
    override suspend fun new(
        context: Context,
        zcashNetwork: ZcashNetwork,
        lightWalletEndpoint: LightWalletEndpoint,
        birthday: BlockHeight?,
        setup: AccountCreateSetup?,
        walletInitMode: WalletInitMode,
        isTorEnabled: Boolean,
        isExchangeRateEnabled: Boolean,
    ): CloseableSynchronizer =
        SlipstreamSynchronizer.new(
            context = context,
            zcashNetwork = zcashNetwork,
            lightWalletEndpoint = lightWalletEndpoint,
            birthday = birthday,
            setup = setup,
            walletInitMode = walletInitMode,
            isTorEnabled = isTorEnabled,
            isExchangeRateEnabled = isExchangeRateEnabled
        )

    override suspend fun erase(
        appContext: Context,
        network: ZcashNetwork
    ): Boolean =
        SlipstreamSynchronizer.erase(
            appContext = appContext,
            network = network
        )
}
