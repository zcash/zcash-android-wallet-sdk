package cash.z.ecc.android.sdk.demoapp.demos.listtransactions

import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
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
class TransactionViewHolder(
    itemView: View
) : RecyclerView.ViewHolder(itemView) {
    private val amountText = itemView.findViewById<TextView>(R.id.text_transaction_amount)
    private val timeText = itemView.findViewById<TextView>(R.id.text_transaction_timestamp)
    private val icon = itemView.findViewById<ImageView>(R.id.image_transaction_type)
    private val formatter = SimpleDateFormat("M/d h:mma", Locale.getDefault())

    @Suppress("MagicNumber")
    fun bindTo(transaction: TransactionOverview) {
        bindTo(
            !transaction.isSentTransaction,
            transaction.minedHeight,
            transaction.blockTimeEpochSeconds,
            transaction.netValue
        )
    }

    @Suppress("MagicNumber")
    fun bindTo(
        isInbound: Boolean,
        minedHeight: BlockHeight?,
        time: Long?,
        value: Zatoshi
    ) {
        amountText.text = value.convertZatoshiToZecString(Locale.getDefault())
        timeText.text = minedHeight?.let {
            time?.let {
                formatter.format(it * 1000L)
            } ?: "Unknown"
        } ?: "Pending"

        icon.rotation = if (isInbound) 0f else 180f
        icon.rotation = if (isInbound) 0f else 180f
        icon.setColorFilter(
            ContextCompat.getColor(itemView.context, if (isInbound) R.color.tx_inbound else R.color.tx_outbound)
        )
    }
}
