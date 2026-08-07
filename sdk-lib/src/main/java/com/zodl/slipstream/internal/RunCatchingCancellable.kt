package com.zodl.slipstream.internal

import kotlinx.coroutines.CancellationException

/**
 * Coroutine-safe drop-in for [runCatching]: identical `Result<T>` shape, but a
 * [CancellationException] is always rethrown rather than captured as a failure - swallowing it
 * would break structured concurrency (a coroutine that keeps "succeeding" through its own
 * cancellation looks alive to its parent, when it must not). Every other [Throwable] is captured
 * into [Result.failure], exactly like [runCatching].
 */
@Suppress("TooGenericExceptionCaught")
internal inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }
