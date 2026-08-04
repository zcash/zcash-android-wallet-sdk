package cash.z.ecc.android.sdk.model

/**
 * How a transaction classifies against ZIP 318, the Orchard to Ironwood pool migration.
 *
 * This is a conformance class, not a provenance: it describes the shape a transaction has on
 * chain, and can never establish that a transaction came from this wallet's own migration run.
 * Only [PREPARATION] and [TRANSFER] are the wallet's own migration. librustzcash deliberately does
 * not distinguish a migration transfer from an ordinary payment to a third party that happens to
 * share the same on-chain shape (a canonical crossing joining the migration anonymity set) — it
 * cannot make that call honestly at this layer, so both decode as [TRANSFER].
 */
@Suppress("MagicNumber")
enum class Zip318Kind(
    val code: Int
) {
    /**
     * The transaction has not been classified, either because it predates the classification or
     * because the wallet has not decrypted it yet. It says nothing about whether the transaction
     * is a ZIP 318 transaction; deciding that requires rescanning it. Present it with no label
     * rather than as [NONCONFORMING].
     */
    NOT_CLASSIFIED(0),

    /** Classified, and not a ZIP 318 transaction. */
    NONCONFORMING(1),

    /** A note-preparation self-send that a migration run makes before it crosses. */
    PREPARATION(2),

    /**
     * A pool crossing shaped like a migration transfer — either the account's own internal
     * migration, or an ordinary payment to a third party that happens to share the same shape
     * (see the class doc). The wallet cannot honestly tell these apart.
     */
    TRANSFER(3);

    companion object {
        /**
         * Decodes the `zip318_kind` column value.
         *
         * An unrecognized code decodes to [NOT_CLASSIFIED]: a newer librustzcash may classify a
         * transaction in a way this SDK does not know, and having learned nothing about it is the
         * honest reading, rather than claiming it is [NONCONFORMING].
         */
        internal fun new(code: Int?): Zip318Kind = entries.firstOrNull { it.code == code } ?: NOT_CLASSIFIED
    }
}
