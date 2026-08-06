package cash.z.ecc.android.sdk.model

/**
 * Data structure to hold the balance of the wallet. This is what is received on the balance channel.
 *
 * @param available The amount of funds that are available for use. Typical reasons that funds
 * may be unavailable include fairly new transactions that do not have enough confirmations or
 * notes that are tied up because we are awaiting change from a transaction. When a note has
 * been spent, its change cannot be used until there are enough confirmations.
 * @param changePending The value in the account of change notes that do not yet have sufficient confirmations to be
 * spendable.
 * @param valuePending The value in the account of all remaining received notes that either do not have sufficient
 * confirmations to be spendable, or for which witnesses cannot yet be constructed without additional scanning.
 * @param locked The value in the account of notes the wallet sees as committed to be spent by a
 * transaction proposal or PCZT (e.g. a migration transfer's input notes from the moment it is
 * proved) — real, owned funds, not counted in [available] because the wallet won't select them
 * for a different spend. Deliberately excluded from [total]/[pending] (unlike upstream's own
 * `Balance.total()`, which includes it): those two fields keep their existing, established
 * meaning for every consumer already reading them; callers that need locked value folded into a
 * displayed total add [locked] themselves (see `GetBalancePoolsUseCase` in the app repo).
 * Defaults to zero for callers that don't have this data (e.g. Slipstream's own balance mapping).
 */
data class WalletBalance(
    val available: Zatoshi,
    val changePending: Zatoshi,
    val valuePending: Zatoshi,
    val locked: Zatoshi = Zatoshi(0),
) {
    /**
     * The current total balance is calculated as a sum of [available], [changePending],
     * and [valuePending]. Deliberately excludes [locked] — see its kdoc.
     */
    val total = available + changePending + valuePending

    /**
     * The current pending balance is calculated as the difference between [total] and [available] balances.
     */
    val pending = total - available
}
