package cash.z.ecc.android.sdk.internal.transaction

import cash.z.ecc.android.sdk.internal.Twig
import cash.z.ecc.android.sdk.internal.ext.toHexReversed
import cash.z.ecc.android.sdk.model.FirstClassByteArray
import cash.z.ecc.android.sdk.model.SdkFlags
import cash.z.ecc.android.sdk.model.TransactionSubmitResult
import co.electriccoin.lightwallet.client.CombinedWalletClient
import co.electriccoin.lightwallet.client.ServiceMode
import co.electriccoin.lightwallet.client.model.Response
import co.electriccoin.lightwallet.client.model.SendResponseUnsafe

internal suspend fun CombinedWalletClient.submitTransaction(
    rawTransaction: FirstClassByteArray,
    txId: FirstClassByteArray,
    sdkFlags: SdkFlags
): TransactionSubmitResult {
    val initialResult =
        submitTransaction(
            tx = rawTransaction.byteArray,
            serviceMode =
                sdkFlags ifTor
                    ServiceMode.Group("submit-${txId.byteArray.toHexReversed()}")
        ).toSubmitResult(txId)

    // Trust the network over the submit-side error: if the server confirms it has this tx,
    // the broadcast already landed (Zebra's MempoolError::InMempool / AlreadyQueued, zcashd's
    // already-in-chain, or any future variant). Skip the misleading failure UI.
    if (initialResult is TransactionSubmitResult.Failure && !initialResult.grpcError) {
        if (isTransactionKnownToServer(txId, sdkFlags)) {
            Twig.info {
                "SUCCESS (verified known to server): submit transaction for: ${txId.byteArray.toHexReversed()}"
            }
            return TransactionSubmitResult.Success(txId)
        }
    }

    return initialResult
}

@Suppress("TooGenericExceptionCaught")
private suspend fun CombinedWalletClient.isTransactionKnownToServer(
    txId: FirstClassByteArray,
    sdkFlags: SdkFlags
): Boolean =
    try {
        val response =
            fetchTransaction(
                txId = txId.byteArray,
                serviceMode =
                    sdkFlags ifTor
                        ServiceMode.Group("submit-${txId.byteArray.toHexReversed()}")
            )
        when (response) {
            is Response.Success -> true
            else -> false
        }
    } catch (t: Throwable) {
        Twig.warn(t) {
            "Failed to verify whether server knows tx ${txId.byteArray.toHexReversed()}; treating as unknown"
        }
        false
    }

internal fun Response<SendResponseUnsafe>.toSubmitResult(txId: FirstClassByteArray): TransactionSubmitResult =
    when (this) {
        is Response.Success -> {
            if (result.code == 0) {
                Twig.info {
                    "SUCCESS: submit transaction completed for: ${txId.byteArray.toHexReversed()}"
                }
                TransactionSubmitResult.Success(txId)
            } else {
                Twig.error {
                    "FAILURE! submit transaction ${txId.byteArray.toHexReversed()} " +
                        "completed with response: ${result.code}: ${result.message}"
                }
                TransactionSubmitResult.Failure(
                    txId = txId,
                    grpcError = false,
                    code = result.code,
                    description = result.message
                )
            }
        }

        is Response.Failure -> {
            Twig.error {
                "FAILURE! submit transaction failed with gRPC response: $code: $description"
            }
            TransactionSubmitResult.Failure(
                txId = txId,
                grpcError = true,
                code = code,
                description = description,
                isTorFailure = this is Response.Failure.OverTor,
            )
        }
    }
