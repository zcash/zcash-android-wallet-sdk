package cash.z.ecc.android.sdk.internal.jni

/**
 * Thrown across the JNI boundary when a transaction proposal fails because no anchor is
 * computable at the height the proposal would anchor to (`zcash_client_backend`'s
 * `ProposalError::AnchorNotFound`).
 *
 * This is a distinct type so that `sdk-lib` can surface a typed error instead of matching
 * message text. It is constructed from native code: the `(String, Long)` constructor
 * signature is part of the JNI contract with `map_proposal_error` in `lib.rs`.
 */
class ProposalAnchorNotFoundException(
    message: String,
    val anchorHeight: Long
) : RuntimeException(message)
