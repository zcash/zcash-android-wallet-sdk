package com.zodl.slipstream.model

import androidx.annotation.Keep

/**
 * Wallet-provisioning facts (the `restoreAnchor` native; see `FFI_JNI_CONTRACT.md` section 8).
 * RESTORE intent: height = the recover_until height (always valid by policy - the live tip, or
 * offline max(bundled checkpoint, birthday + 1)); treestate = null.
 * NEW intent: height + serialized TreeState protobuf = a reorg-safe recent tree state; height 0 +
 * null treestate when offline (keep the bundled checkpoint).
 *
 * Constructed by the `slipstream-jni` crate (`RESTORE_ANCHOR_CTOR = "(J[B)V"`) - field order is
 * the binding contract.
 */
@Keep
data class SlipstreamRestoreAnchor(
    val height: Long,
    val treestate: ByteArray?
)
