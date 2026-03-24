package cash.z.ecc.android.sdk.demoapp.demos.send

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.demoapp.BaseDemoFragment
import cash.z.ecc.android.sdk.demoapp.CURRENT_ZIP_32_ACCOUNT_INDEX
import cash.z.ecc.android.sdk.demoapp.DemoConstants
import cash.z.ecc.android.sdk.demoapp.databinding.FragmentSendBinding
import cash.z.ecc.android.sdk.demoapp.util.mainActivity
import cash.z.ecc.android.sdk.ext.convertZatoshiToZecString
import cash.z.ecc.android.sdk.ext.convertZecToZatoshi
import cash.z.ecc.android.sdk.ext.toZecString
import cash.z.ecc.android.sdk.model.PercentDecimal
import cash.z.ecc.android.sdk.model.UnifiedSpendingKey
import cash.z.ecc.android.sdk.model.WalletBalance
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.launch

/**
 * Demonstrates sending funds to an address. This is the most complex example that puts all of the
 * pieces of the SDK together, including monitoring transactions for completion. It begins by
 * downloading, validating and scanning any missing blocks. Once that is complete, the wallet is
 * in a SYNCED state and available to send funds. Calling `sendToAddress` produces a flow of
 * PendingTransaction objects which represent the active state of the transaction that was sent.
 * Any time the state of that transaction changes, a new instance will be emitted.
 */
@Suppress("TooManyFunctions")
class SendFragment : BaseDemoFragment<FragmentSendBinding>() {
    private lateinit var amountInput: TextView
    private lateinit var addressInput: TextView

    // in a normal app, this would be stored securely with the trusted execution environment (TEE)
    // but since this is a demo, we'll derive it on the fly
    private lateinit var spendingKey: UnifiedSpendingKey

    //
    // Observable properties (done without livedata or flows for simplicity)
    //

    private var balance: WalletBalance? = null
        set(value) {
            field = value
            onUpdateSendButton()
        }
    private var isSending = false
        set(value) {
            field = value
            onUpdateSendButton()
        }
    private var isSyncing = true
        set(value) {
            field = value
            onUpdateSendButton()
        }

    //
    // Private functions
    //

    private fun initSendUi() {
        amountInput =
            binding.inputAmount.apply {
                setText(DemoConstants.SEND_AMOUNT.toZecString())
            }
        addressInput =
            binding.inputAddress.apply {
                setText(DemoConstants.TO_ADDRESS)
            }
        binding.buttonSend.setOnClickListener(::onSend)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun monitorChanges() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    sharedViewModel.synchronizerFlow
                        .filterNotNull()
                        .flatMapLatest { it.status }
                        .collect { onStatus(it) }
                }
                launch {
                    sharedViewModel.synchronizerFlow
                        .filterNotNull()
                        .flatMapLatest { it.progress }
                        .collect { onProgress(it) }
                }
                launch {
                    sharedViewModel.synchronizerFlow
                        .filterNotNull()
                        .flatMapLatest {
                            val account = it.getAccounts()[CURRENT_ZIP_32_ACCOUNT_INDEX.toInt()]
                            it.walletBalances.mapLatest { balances ->
                                balances?.let {
                                    val walletBalance = balances[account.accountUuid]!!.sapling
                                    walletBalance
                                }
                            }
                        }.collect { onBalance(it) }
                }
            }
        }
    }

    //
    // Change listeners
    //

    private fun onStatus(status: Synchronizer.Status) {
        binding.textStatus.text = "Status: $status"
        isSyncing = status != Synchronizer.Status.SYNCED
        if (status == Synchronizer.Status.SYNCING) {
            binding.textBalance.text = "Calculating balance..."
        } else {
            if (!isSyncing) onBalance(balance)
        }
    }

    @Suppress("MagicNumber")
    private fun onProgress(percent: PercentDecimal) {
        if (percent.isLessThanHundredPercent()) {
            binding.textStatus.text = "Syncing blocks...${percent.toPercentage()}%"
            binding.textBalance.visibility = View.INVISIBLE
        } else {
            binding.textBalance.visibility = View.VISIBLE
        }
    }

    @Suppress("MagicNumber")
    private fun onBalance(balance: WalletBalance?) {
        this.balance = balance
        if (!isSyncing) {
            binding.textBalance.text =
                """
                Available balance: ${balance?.available.convertZatoshiToZecString(12)}
                Total balance: ${balance?.total.convertZatoshiToZecString(12)}
                """.trimIndent()
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun onSend(unused: View) {
        isSending = true
        val amount =
            amountInput.text
                .toString()
                .toDouble()
                .convertZecToZatoshi()
        val toAddress = addressInput.text.toString().trim()
        lifecycleScope.launch {
            sharedViewModel.synchronizerFlow.value?.let { synchronizer ->
                val account = synchronizer.getAccounts()[CURRENT_ZIP_32_ACCOUNT_INDEX.toInt()]
                synchronizer.createProposedTransactions(
                    synchronizer.proposeTransfer(
                        account,
                        toAddress,
                        amount,
                        "Funds from Demo App"
                    ),
                    spendingKey
                )
            }
        }

        mainActivity()?.hideKeyboard()
    }

    private fun onUpdateSendButton() {
        with(binding.buttonSend) {
            when {
                isSending -> {
                    text = "➡ sending"
                    isEnabled = false
                }

                isSyncing -> {
                    text = "⌛ syncing"
                    isEnabled = false
                }

                (balance?.available?.value ?: 0) <= 0 -> {
                    isEnabled = false
                }

                else -> {
                    text = "send"
                    isEnabled = true
                }
            }
        }
    }

    //
    // Android Lifecycle overrides
    //

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = super.onCreateView(inflater, container, savedInstanceState)
        return view
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?
    ) {
        super.onViewCreated(view, savedInstanceState)
        initSendUi()
        monitorChanges()
    }

    //
    // BaseDemoFragment overrides
    //
    @Suppress("MaxLineLength")
    override fun inflateBinding(layoutInflater: LayoutInflater): FragmentSendBinding = FragmentSendBinding.inflate(layoutInflater)
}
