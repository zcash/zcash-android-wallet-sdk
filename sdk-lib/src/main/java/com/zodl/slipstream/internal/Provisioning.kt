package com.zodl.slipstream.internal

import android.content.Context
import cash.z.ecc.android.sdk.WalletInitMode
import cash.z.ecc.android.sdk.model.ZcashNetwork
import cash.z.ecc.android.sdk.tool.CheckpointTool

/**
 * `FFI_JNI_CONTRACT.md` section 8: 1 = restore, 0 = new, null = no anchor call.
 * [WalletInitMode]'s ONLY effect on provisioning (DECISIONS.md D11) - no init-mode state persists
 * or alters any later behavior; `is_recovering` (DB-derived) is the durable restore signal.
 */
internal fun resolveIntent(mode: WalletInitMode): Int? =
    when (mode) {
        WalletInitMode.RestoreWallet -> 1
        WalletInitMode.NewWallet -> 0
        WalletInitMode.ExistingWallet -> null
    }

/**
 * The height of the newest bundled checkpoint for [network] - the offline lower bound the
 * `restoreAnchor` native falls back to when the server is unreachable (`FFI_JNI_CONTRACT.md`
 * section 8). Ports `SDK_ADAPTER_PLAN.md` Appendix Z item 3: `CheckpointTool` is `internal object`,
 * directly callable from this module; `loadLast(context, network)` is `loadNearest(context,
 * network, birthdayHeight = null)`, which its own KDoc documents as "load the most recent
 * checkpoint available".
 */
internal suspend fun newestBundledCheckpointHeight(
    context: Context,
    network: ZcashNetwork
): Long = CheckpointTool.loadLast(context, network).height.value

/**
 * The idempotency guard `DerivedDataDb.new` runs before calling `RustBackend.createAccount`
 * (`DerivedDataDb.kt` ~150: `setup != null && backend.getAccounts().isEmpty()`) - mirrored here as a
 * pure predicate so `SlipstreamSynchronizer.Companion.newLocked`'s fresh-wallet account-creation fix
 * (nothing else in that factory ever wrote an `accounts` row) can unit-test its decision table
 * without a JNI [cash.z.ecc.android.sdk.internal.Backend] or Android `Context`. [hasSetup] is
 * `setup != null`; a non-null setup only ever accompanies [WalletInitMode.NewWallet]/
 * [WalletInitMode.RestoreWallet] (the UFVK derivation in `newLocked` already requires it), so this
 * predicate is what makes an `ExistingWallet` relaunch of an already-provisioned DB a no-op instead
 * of writing a second account row.
 */
internal fun shouldCreateAccount(
    hasSetup: Boolean,
    accountsAreEmpty: Boolean
): Boolean = hasSetup && accountsAreEmpty
