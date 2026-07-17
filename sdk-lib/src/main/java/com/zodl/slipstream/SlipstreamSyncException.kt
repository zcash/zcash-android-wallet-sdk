package com.zodl.slipstream

/**
 * Dispatched to [SlipstreamSynchronizer.onProcessorErrorHandler] when the engine snapshot reports
 * `state == 2` - an engine logic error or panic (transient network trouble is retried internally
 * and never surfaces this way, `FFI_JNI_CONTRACT.md` section 3.5). [chainTip] mirrors the iOS
 * `ZRUST0096` twin's semantics: it is the chain tip at failure, not an error code
 * (`KOTLIN_ROSETTA.md` section 2.3).
 */
class SlipstreamSyncException(
    val chainTip: Long
) : RuntimeException("Slipstream sync failed (state=2, chainTip=$chainTip)")
