package co.electriccoin.lightwallet.client.internal

import android.util.Log
import co.electriccoin.lightwallet.client.model.Response
import io.grpc.Status

/**
 * This class provides conversion from GRPC Status to our predefined Server or Client error classes.
 */
object GrpcStatusResolver : ApiStatusResolver {
    override fun <T> resolveFailureFromStatus(exception: Exception): Response.Failure<T> {
        val status = Status.fromThrowable(exception)
        Log.w(Constants.LOG_TAG, "Networking error: ${status.code}: ${status.description}")

        return resolveByStatusCode(status, exception)
    }

    @Suppress("CyclomaticComplexMethod")
    private fun <T> resolveByStatusCode(
        status: Status,
        exception: Exception
    ): Response.Failure<T> =
        when (status.code) {
            Status.Code.ABORTED -> {
                Response.Failure.Server.Aborted(
                    cause = exception,
                    code = status.code.value(),
                    description = status.description
                )
            }

            Status.Code.CANCELLED -> {
                Response.Failure.Client.Canceled(
                    cause = exception,
                    code = status.code.value(),
                    description = status.description
                )
            }

            Status.Code.DEADLINE_EXCEEDED -> {
                Response.Failure.Server.DeadlineExceeded(
                    cause = exception,
                    code = status.code.value(),
                    description = status.description
                )
            }

            Status.Code.NOT_FOUND -> {
                Response.Failure.Server.NotFound(
                    cause = exception,
                    code = status.code.value(),
                    description = status.description
                )
            }

            Status.Code.PERMISSION_DENIED -> {
                Response.Failure.Server.PermissionDenied(
                    cause = exception,
                    code = status.code.value(),
                    description = status.description
                )
            }

            Status.Code.UNAVAILABLE -> {
                Response.Failure.Server.Unavailable(
                    cause = exception,
                    code = status.code.value(),
                    description = status.description
                )
            }

            Status.Code.UNKNOWN -> {
                Response.Failure.Server.Unknown(
                    cause = exception,
                    code = status.code.value(),
                    description = status.description
                )
            }

            else -> {
                Response.Failure.Server.Other(
                    cause = exception,
                    code = status.code.value(),
                    description = status.description
                )
            }
        }
}
