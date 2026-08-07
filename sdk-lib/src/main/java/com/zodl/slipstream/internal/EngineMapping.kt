@file:Suppress("MagicNumber")

package com.zodl.slipstream.internal

import cash.z.ecc.android.sdk.Synchronizer.Status
import cash.z.ecc.android.sdk.model.AccountBalance
import cash.z.ecc.android.sdk.model.AccountUuid
import cash.z.ecc.android.sdk.model.PercentDecimal
import cash.z.ecc.android.sdk.model.WalletBalance
import cash.z.ecc.android.sdk.model.Zatoshi
import com.zodl.slipstream.model.SlipstreamPoolBalance
import com.zodl.slipstream.model.SlipstreamWalletSummary

/**
 * snapshot.state -> [Status]. State 0 = idle-while-running -> DISCONNECTED
 * (`KOTLIN_ROSETTA.md` section 2.3: a state-0 snapshot while running stays DISCONNECTED - iOS
 * parity). `INITIALIZING`/`STOPPED` are lifecycle values the synchronizer sets outside the tick
 * (pre-open / after stop), never here.
 */
internal fun mapEngineState(state: Int): Status =
    when (state) {
        1 -> Status.SYNCING
        3 -> Status.SYNCED
        else -> Status.DISCONNECTED // 0 idle, 2 error (offer retry), and any unknown value
    }

/**
 * Render, never derive (DECISIONS.md D11) - the only arithmetic allowed is the unit change;
 * [PercentDecimal.newLenient] tolerates any out-of-range permille without throwing (the canonical
 * progress idiom).
 */
internal fun permilleToPercentDecimal(permille: Int): PercentDecimal = PercentDecimal.newLenient(permille / 1000f)

private fun SlipstreamPoolBalance.toWalletBalance() =
    WalletBalance(Zatoshi(spendableValue), Zatoshi(changePendingConfirmation), Zatoshi(valuePendingSpendability))

/**
 * `FFI_JNI_CONTRACT.md` section 5.5 mask: while NOT recovering AND tip is stale, shield-pool
 * spendable -> pending (a spendable claim about an unverified tip is a bug). Recovery balances
 * are never masked (safe by construction). The unshielded arm is left unmasked in v1 -
 * `AccountBalance.unshielded` is a bare [Zatoshi] with no pending slot; zeroing it would vanish
 * funds (`KOTLIN_ROSETTA.md` section 4.5 / OQ-2).
 */
private fun SlipstreamPoolBalance.maskIfStale(mask: Boolean): SlipstreamPoolBalance =
    if (!mask) {
        this
    } else {
        SlipstreamPoolBalance(0, changePendingConfirmation, valuePendingSpendability + spendableValue)
    }

/**
 * Maps the engine's phase-resolving summary to the SDK's public balance model. `ironwood` is
 * read straight from the engine's own `SlipstreamAccountBalance.ironwood` field, which the JNI
 * layer populates from the linked librustzcash summary (`balance.ironwood_balance()`), so migrated
 * Orchard→Ironwood funds recorded in the wallet DB surface here.
 */
internal fun SlipstreamWalletSummary.toAccountBalances(
    isRecovering: Boolean,
    tipFresh: Boolean
): Map<AccountUuid, AccountBalance> {
    val mask = !isRecovering && !tipFresh
    return accountBalances.associate { ab ->
        AccountUuid.new(ab.accountUuid) to
            AccountBalance(
                sapling = ab.sapling.maskIfStale(mask).toWalletBalance(),
                orchard = ab.orchard.maskIfStale(mask).toWalletBalance(),
                ironwood = ab.ironwood.maskIfStale(mask).toWalletBalance(),
                unshielded = Zatoshi(ab.unshielded)
            )
    }
}
