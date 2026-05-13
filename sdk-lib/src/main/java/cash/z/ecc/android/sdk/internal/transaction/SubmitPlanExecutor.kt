package cash.z.ecc.android.sdk.internal.transaction

import cash.z.ecc.android.sdk.model.CreatedTransaction
import cash.z.ecc.android.sdk.model.TransactionSubmitResult

internal class SubmitPlanExecutor(
    private val transactionSubmitter: TransactionSubmitter
) {
    suspend fun submit(
        transaction: CreatedTransaction,
        submitPlan: TransactionSubmitPlan
    ): TransactionSubmitResult {
        var result: TransactionSubmitResult = TransactionSubmitResult.NotAttempted(transaction.txId)
        for (endpoint in submitPlan.endpoints.distinct()) {
            result = transactionSubmitter.submit(transaction, endpoint)
            if (result !is TransactionSubmitResult.Failure) {
                break
            }
        }
        return result
    }
}
