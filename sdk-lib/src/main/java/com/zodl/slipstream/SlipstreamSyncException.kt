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

/**
 * Thrown by every engine- or database-backed [SlipstreamSynchronizer] member that was called while
 * the synchronizer's deferred preparation (`Companion.new`'s heavy tail - anchor resolution, data-DB
 * provisioning, `engine.open`/`start`) had already failed, or while it was cancelled by
 * [SlipstreamSynchronizer.close]. Callers that arrive while preparation is still running suspend
 * until it settles instead of seeing this.
 *
 * [cause] is the preparation failure when there was one, and `null` when preparation never
 * completed because the synchronizer was closed underneath it.
 */
class SlipstreamNotReadyException(
    cause: Throwable?
) : IllegalStateException(
        "Slipstream synchronizer preparation did not complete; this instance is not usable",
        cause
    )
