package com.zodl.slipstream.internal

import cash.z.ecc.android.sdk.WalletInitMode
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProvisioningTest {
    @Test
    fun restore_wallet_maps_to_intent_one() = assertEquals(1, resolveIntent(WalletInitMode.RestoreWallet))

    @Test
    fun new_wallet_maps_to_intent_zero() = assertEquals(0, resolveIntent(WalletInitMode.NewWallet))

    @Test
    fun existing_wallet_never_calls_the_anchor() = assertNull(resolveIntent(WalletInitMode.ExistingWallet))

    /**
     * Fresh-wallet provisioning bug fix: `newLocked` skipped account creation entirely, so every
     * fresh restore/new-wallet synced blocks against zero `accounts` rows. This table pins
     * [shouldCreateAccount]'s guard - the same one `DerivedDataDb.new` uses
     * (`setup != null && backend.getAccounts().isEmpty()`) - across all 4 combinations.
     */
    @Test
    fun creates_only_when_setup_is_present_and_the_db_has_no_accounts_yet() =
        assertTrue(shouldCreateAccount(hasSetup = true, accountsAreEmpty = true))

    @Test
    fun skips_creation_on_a_relaunch_of_an_already_provisioned_db() =
        assertFalse(shouldCreateAccount(hasSetup = true, accountsAreEmpty = false))

    @Test
    fun skips_creation_for_existing_wallet_mode_which_never_carries_a_setup() =
        assertFalse(shouldCreateAccount(hasSetup = false, accountsAreEmpty = true))

    @Test
    fun skips_creation_when_neither_condition_holds() =
        assertFalse(shouldCreateAccount(hasSetup = false, accountsAreEmpty = false))
}
