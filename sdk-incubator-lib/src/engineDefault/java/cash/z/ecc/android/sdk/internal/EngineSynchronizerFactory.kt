package cash.z.ecc.android.sdk.internal

import android.content.Context
import cash.z.ecc.android.sdk.CloseableSynchronizer
import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.WalletInitMode
import cash.z.ecc.android.sdk.model.AccountCreateSetup
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.ZcashNetwork
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint

/**
 * The `IS_SLIPSTREAM_ENABLED=false` variant of the engine seam, compiled only when this SDK build
 * excludes `:slipstream-lib`.
 */
internal val engineSynchronizerFactory: SynchronizerEngineFactory = DefaultEngineFactory

private object DefaultEngineFactory : SynchronizerEngineFactory {
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
        Synchronizer.new(
            context = context,
            zcashNetwork = zcashNetwork,
            lightWalletEndpoint = lightWalletEndpoint,
            birthday = birthday,
            setup = setup,
            walletInitMode = walletInitMode,
            isTorEnabled = isTorEnabled,
            isExchangeRateEnabled = isExchangeRateEnabled
        )

    /**
     * There is no engine-owned database beside the ones [Synchronizer.erase] already removes, so
     * this reports success without touching the filesystem.
     */
    override suspend fun erase(
        appContext: Context,
        network: ZcashNetwork
    ): Boolean = true
}
