package cash.z.ecc.android.sdk.demoapp

import android.content.Context
import cash.z.ecc.android.sdk.WalletCoordinator
import cash.z.ecc.android.sdk.demoapp.preference.EncryptedPreferenceKeys
import cash.z.ecc.android.sdk.demoapp.preference.EncryptedPreferenceSingleton
import cash.z.ecc.android.sdk.demoapp.util.LazyWithArgument
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf

private val lazy =
    LazyWithArgument<Context, WalletCoordinator> {
    /*
     * A flow of the user's stored wallet.  Null indicates that no wallet has been stored.
     */
        val persistableWalletFlow =
            flow {
                // EncryptedPreferenceSingleton.getInstance() is a suspending function, which is why we need
                // the flow builder to provide a coroutine context.
                val encryptedPreferenceProvider = EncryptedPreferenceSingleton.getInstance(it)

                emitAll(EncryptedPreferenceKeys.PERSISTABLE_WALLET.observe(encryptedPreferenceProvider))
            }

        WalletCoordinator(
            context = it,
            persistableWallet = persistableWalletFlow,
            accountName = "Zcash Account 1",
            keySource = "ZCASH",
            isTorEnabled = flowOf(null),
            isExchangeRateEnabled = flowOf(null)
        )
    }

fun WalletCoordinator.Companion.getInstance(context: Context) = lazy.getInstance(context)
