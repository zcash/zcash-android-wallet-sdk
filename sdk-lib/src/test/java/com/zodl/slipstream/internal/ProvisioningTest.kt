package com.zodl.slipstream.internal

import cash.z.ecc.android.sdk.WalletInitMode
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProvisioningTest {
    @Test
    fun restore_wallet_maps_to_intent_one() = assertEquals(1, resolveIntent(WalletInitMode.RestoreWallet))

    @Test
    fun new_wallet_maps_to_intent_zero() = assertEquals(0, resolveIntent(WalletInitMode.NewWallet))

    @Test
    fun existing_wallet_never_calls_the_anchor() = assertNull(resolveIntent(WalletInitMode.ExistingWallet))
}
