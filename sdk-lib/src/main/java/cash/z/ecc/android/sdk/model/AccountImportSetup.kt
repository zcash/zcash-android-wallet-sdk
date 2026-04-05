package cash.z.ecc.android.sdk.model

/**
 * Wrapper for the import account API based on viewing key.
 *
 * @param accountName A human-readable name for the account. This will be visible to the wallet
 *        user, and the wallet app may obtain it from them.
 * @param keySource A string identifier or other metadata describing the location of the spending
 *        key corresponding to the provided UFVK. This should be set internally by the wallet app
 *        based on its private enumeration of spending methods it supports.
 * @param purpose Metadata describing whether or not data required for spending should be tracked by the wallet
 * @param ufvk The UFVK used to detect transactions involving the account
 * account's UFVK.
 * @param birthdayHeight Optional birthday height for the account. When provided, the wallet will
 *        sync from this height instead of the current chain tip. When null, the current chain tip
 *        is used (default behavior).
 */
data class AccountImportSetup(
    val accountName: String,
    val keySource: String?,
    val purpose: AccountPurpose,
    val ufvk: UnifiedFullViewingKey,
    val birthdayHeight: BlockHeight? = null,
)
