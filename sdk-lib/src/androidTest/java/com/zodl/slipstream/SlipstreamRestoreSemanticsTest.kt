package com.zodl.slipstream

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.WalletInitMode
import cash.z.ecc.android.sdk.model.AccountCreateSetup
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.FirstClassByteArray
import cash.z.ecc.android.sdk.model.ZcashNetwork
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `SDK_ADAPTER_PLAN.md` T7 item 4, the restore-semantics truth test: open+start with a UFVK whose
 * birthday is far below the chain tip should show `snapshot.isRecovering == true` and a climbing
 * `progressPermille` within one poll. **Ignored** - `SDK_ADAPTER_PLAN.md` Appendix Z item 7: a
 * funded, deep-birthday testnet fixture must be provisioned by the team (the iOS fleet fixtures are
 * Zodl-internal); this repo does not embed mnemonics. Written now so the assertions are ready the
 * moment a fixture seed is provided - only the `TODO` seed bytes below need to change.
 */
@RunWith(AndroidJUnit4::class)
class SlipstreamRestoreSemanticsTest {
    @Ignore("Needs a funded, deep-birthday testnet fixture seed - SDK_ADAPTER_PLAN.md Appendix Z item 7; not embedded in this repo")
    @Test
    fun restoring_a_deep_birthday_wallet_reports_recovering_and_climbing_progress() =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val fixtureSeed = FirstClassByteArray(ByteArray(64))
            val synchronizer =
                SlipstreamSynchronizer.new(
                    alias = "restore_semantics_test",
                    birthday = BlockHeight.new(1_000_000L),
                    context = context,
                    lightWalletEndpoint = LightWalletEndpoint(host = "lightwalletd.testnet.z.cash", port = 9067, isSecure = true),
                    setup = AccountCreateSetup(accountName = "fixture", keySource = null, seed = fixtureSeed),
                    walletInitMode = WalletInitMode.RestoreWallet,
                    zcashNetwork = ZcashNetwork.Testnet,
                    isTorEnabled = false,
                    isExchangeRateEnabled = false
                )
            try {
                val status = synchronizer.status.first()
                assertTrue("expected a live status while recovering", status != Synchronizer.Status.STOPPED)
            } finally {
                synchronizer.close()
            }
        }
}
