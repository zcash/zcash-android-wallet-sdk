package cash.z.ecc.android.sdk.demoapp.demos.listutxos

import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import cash.z.ecc.android.sdk.demoapp.R
import cash.z.ecc.android.sdk.ext.convertZatoshiToZecString
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.TransactionOverview
import cash.z.ecc.android.sdk.model.Zatoshi
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Simple view holder for displaying confirmed transactions in the recyclerview.
 */
class UtxoViewHolder(
    itemView: View
) : RecyclerView.ViewHolder(itemView) {
    private val amountText = itemView.findViewById<TextView>(R.id.text_transaction_amount)
    private val timeText = itemView.findViewById<TextView>(R.id.text_transaction_timestamp)
    private val formatter = SimpleDateFormat("M/d h:mma", Locale.getDefault())

    @Suppress("MagicNumber")
    fun bindTo(transaction: TransactionOverview) {
        bindToHelper(transaction.netValue, transaction.minedHeight, transaction.blockTimeEpochSeconds)
    }

    @Suppress("MagicNumber")
    private fun bindToHelper(
        amount: Zatoshi,
        minedHeight: BlockHeight?,
        time: Long?
    ) {
        amountText.text = amount.convertZatoshiToZecString(Locale.getDefault())
        timeText.text = minedHeight?.let {
            time?.let {
                formatter.format(it * 1000L)
            } ?: "Unknown"
        } ?: "Pending"
    }
}
