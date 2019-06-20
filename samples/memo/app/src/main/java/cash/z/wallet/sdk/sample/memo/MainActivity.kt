package cash.z.wallet.sdk.sample.memo

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.core.content.getSystemService
import cash.z.wallet.sdk.data.*
import cash.z.wallet.sdk.ext.convertZatoshiToZec
import cash.z.wallet.sdk.secure.Wallet
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch
import kotlin.properties.Delegates
import kotlin.properties.Delegates.observable
import kotlin.reflect.KProperty

class MainActivity : ScopedActivity() {

    private lateinit var synchronizer: Synchronizer
    private var progressJob: Job? = null
    private var balanceJob: Job? = null
    private var activeTransaction: TransactionInfo = TransactionInfo()
    private var loaded: Boolean by observable(false) {_, old: Boolean, new: Boolean ->
        if (!old && new) {
            launch {
                onBalance(synchronizer.getBalance())
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Turn on simple logging for debug builds
        Twig.plant(TroubleshootingTwig())

        synchronizer = Injection.provideSynchronizer(this.applicationContext)
        synchronizer.start(this)
    }


    override fun onResume() {
        super.onResume()
        progressJob = launchProgressMonitor(synchronizer.progress())
        balanceJob = launchBalanceMonitor(synchronizer.balances())
    }

    override fun onPause() {
        super.onPause()
        progressJob?.cancel().also { progressJob = null }
        balanceJob?.cancel().also { balanceJob = null }
    }

    override fun onDestroy() {
        super.onDestroy()
        synchronizer.stop()
    }

    private fun CoroutineScope.launchProgressMonitor(channel: ReceiveChannel<Int>) = launch {
        for (i in channel) {
            onProgress(i)
        }
    }

    private fun CoroutineScope.launchTransactionMonitor(channel: ReceiveChannel<Map<ActiveTransaction, TransactionState>>) = launch {
        for (i in channel) {
            onUpdate(i)
        }
    }

    private fun CoroutineScope.launchBalanceMonitor(channel: ReceiveChannel<Wallet.WalletBalance>) = launch {
        for (i in channel) {
            onBalance(i)
        }
    }

    @SuppressLint("SetTextI18n")
    private fun onBalance(balanceInfo: Wallet.WalletBalance) {
        text_status.text = "Available Balance: ${balanceInfo.available.convertZatoshiToZec()} TAZ" +
                "\nTotal Balance: ${balanceInfo.available.convertZatoshiToZec()} TAZ"
    }

    private fun onProgress(progress: Int) {
        twig("Launching - onProgress $progress")
        launch {
            val isComplete = progress == 100
            val status = when {
                isComplete -> "".also { loaded = true }
                progress > 90 -> "Synchronizing...finishing up"
                else -> "Synchronizing...\t$progress%"
            }
            text_progress.text = status
            button_send.isEnabled = isComplete
        }
    }

    private fun onUpdate(transactions: Map<ActiveTransaction, TransactionState>) {
        if (transactions.isNotEmpty()) {
            // primary is the last one that was inserted
            val primaryEntry =
                    transactions.entries.toTypedArray()[transactions.size - 1]
            updatePrimaryTransaction(primaryEntry.key, primaryEntry.value)
        }
    }

    private fun updatePrimaryTransaction(transaction: ActiveTransaction,
                                         transactionState: TransactionState) {
        val status = transactionState.toString()
        text_status.text = "Memo Sent!\nAwaiting confirmation...\nstatus: $status"
    }

    fun onSendMemo(v: View) {
        launchTransactionMonitor(synchronizer.activeTransactions())

        activeTransaction = TransactionInfo(memo = input_memo.text.toString())

        launch {
            synchronizer.sendToAddress(
                activeTransaction.amount,
                activeTransaction.toAddress,
                activeTransaction.memo
            )
        }

        button_send.isEnabled = false
        text_status.text = "Memo Sent!\nAwaiting confirmation..."
        input_memo.text = null
        input_memo.clearFocus()
        hideKeyboard()
    }

    private fun hideKeyboard() {
        getSystemService<InputMethodManager>()
                ?.hideSoftInputFromWindow(window.decorView.rootView.windowToken,
                        InputMethodManager.HIDE_NOT_ALWAYS)
    }

    data class TransactionInfo(
        val amount: Long = 20_000L,
        // reference wallet : Carol
        val toAddress: String = "ztestsapling1efxqj5256ywqdc3zntfa0hw6yn4f83k2h7fgngwmxr3h3w7zyd" +
                "lencvh30730ez6p8fwg56htgz",
        val memo: String = "sample memo"
    )
}
