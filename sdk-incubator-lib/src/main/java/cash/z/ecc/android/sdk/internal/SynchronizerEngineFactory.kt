@file:Suppress("LongParameterList")

package cash.z.ecc.android.sdk.internal

import android.content.Context
import cash.z.ecc.android.sdk.CloseableSynchronizer
import cash.z.ecc.android.sdk.WalletInitMode
import cash.z.ecc.android.sdk.model.AccountCreateSetup
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.ZcashNetwork
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint

/**
 * Creates and erases the sync engine that backs [cash.z.ecc.android.sdk.WalletCoordinator].
 *
 * Which engine that is gets decided at SDK build time by the `IS_SLIPSTREAM_ENABLED` Gradle
 * property: it selects one of this module's `engineSlipstream`/`engineDefault` source directories,
 * each of which supplies exactly one [engineSynchronizerFactory] implementation.
 */
internal interface SynchronizerEngineFactory {
    suspend fun new(
        context: Context,
        zcashNetwork: ZcashNetwork,
        lightWalletEndpoint: LightWalletEndpoint,
        birthday: BlockHeight?,
        setup: AccountCreateSetup?,
        walletInitMode: WalletInitMode,
        isTorEnabled: Boolean,
        isExchangeRateEnabled: Boolean,
    ): CloseableSynchronizer

    suspend fun erase(
        appContext: Context,
        network: ZcashNetwork
    ): Boolean
}
