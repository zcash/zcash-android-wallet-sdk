package cash.z.ecc.android.sdk.internal.jni

import androidx.annotation.Keep

/**
 * Thrown across the JNI boundary when creating transactions from a proposal fails because no
 * anchor is computable at the height the proposal anchors to (`zcash_client_backend`'s
 * `ProposalError::AnchorNotFound`).
 *
 * This is a distinct type so that `sdk-lib` can surface a typed error instead of matching
 * message text. It is constructed from native code, which is why it must be kept: the class
 * name and the `(String, Long)` constructor signature are part of the JNI contract with
 * `map_proposal_error` in `lib.rs`, and are never referenced from bytecode.
 */
@Keep
class ProposalAnchorNotFoundException(
    message: String,
    val anchorHeight: Long
) : RuntimeException(message)
