package cash.z.ecc.android.sdk.internal

/**
 * Matches an `import_account_ufvk` failure against the marker the Rust backend tags a
 * transient, retryable cross-pool checkpoint-parity failure with, as opposed to a genuinely
 * fatal import error.
 *
 * Mirrors `IMPORT_CHECKPOINTS_NOT_READY_MARKER` / `map_import_account_error` in
 * `backend-lib/src/main/rust/lib.rs`, which prefixes the message of the mapped
 * `anyhow::Error` with this exact string when `import_account_ufvk` fails due to
 * `SqliteClientError::CorruptedData` raised by `rewind_to_chain_state`'s cross-pool checkpoint
 * parity check.
 */
internal object ImportAccountErrors {
    private const val CHECKPOINTS_NOT_READY_MARKER = "ImportAccountCheckpointsNotReady"

    /**
     * @return `true` if [t]'s message indicates the transient checkpoint-parity condition
     * described in [ImportAccountErrors], `false` otherwise (including when [t] has no
     * message).
     */
    fun isCheckpointsNotReady(t: Throwable): Boolean = t.message?.startsWith(CHECKPOINTS_NOT_READY_MARKER) == true
}
