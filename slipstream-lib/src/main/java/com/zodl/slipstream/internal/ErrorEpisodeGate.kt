package com.zodl.slipstream.internal

/** What [ErrorEpisodeGate.reduce] decided a snapshot's `state` should do to the error episode. */
internal enum class ErrorEpisodeTransition {
    /** First `state == 2` tick since the episode was clear - dispatch `onProcessorErrorHandler` once. */
    ENTER,

    /** `state == 2` while already in an episode - do nothing (handler already fired for this episode). */
    STAY,

    /** First `state ∈ {0, 1, 3}` tick after an episode - dispatch `onProcessorErrorResolved`. */
    LEAVE,

    /** No episode active and `state != 2` - nothing to do. */
    NONE
}

/**
 * Pure state machine for `KOTLIN_ROSETTA.md` section 2.3's error-episode protocol: `state == 2` is
 * an engine logic error/panic (never transient network trouble, which the engine retries
 * internally) and must dispatch `onProcessorErrorHandler` exactly once per episode; the first
 * `state ∈ {0, 1, 3}` tick after an episode dispatches `onProcessorErrorResolved`. Side effects
 * (invoking the handlers, calling `engine.start` on retry) live at the call site
 * ([com.zodl.slipstream.internal.SlipstreamEngine]); this class only tracks ENTER/STAY/LEAVE/NONE,
 * mirroring [PollGate]'s reduce-then-apply shape.
 */
internal data class ErrorEpisodeGate(
    val active: Boolean
) {
    fun reduce(state: Int): Pair<ErrorEpisodeTransition, ErrorEpisodeGate> =
        when {
            state == 2 && !active -> ErrorEpisodeTransition.ENTER to ErrorEpisodeGate(active = true)
            state == 2 -> ErrorEpisodeTransition.STAY to this
            active -> ErrorEpisodeTransition.LEAVE to ErrorEpisodeGate(active = false)
            else -> ErrorEpisodeTransition.NONE to this
        }

    companion object {
        val INITIAL = ErrorEpisodeGate(active = false)
    }
}
