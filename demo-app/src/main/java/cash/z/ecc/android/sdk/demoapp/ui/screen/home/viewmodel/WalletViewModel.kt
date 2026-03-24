package cash.z.ecc.android.sdk.demoapp.ui.screen.home.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.bip39.Mnemonics
import cash.z.ecc.android.bip39.toSeed
import cash.z.ecc.android.sdk.SdkSynchronizer
import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.WalletCoordinator
import cash.z.ecc.android.sdk.WalletInitMode
import cash.z.ecc.android.sdk.block.processor.CompactBlockProcessor
import cash.z.ecc.android.sdk.demoapp.ANDROID_STATE_FLOW_TIMEOUT
import cash.z.ecc.android.sdk.demoapp.CURRENT_ZIP_32_ACCOUNT_INDEX
import cash.z.ecc.android.sdk.demoapp.ext.defaultForNetwork
import cash.z.ecc.android.sdk.demoapp.getInstance
import cash.z.ecc.android.sdk.demoapp.preference.EncryptedPreferenceKeys
import cash.z.ecc.android.sdk.demoapp.preference.EncryptedPreferenceSingleton
import cash.z.ecc.android.sdk.demoapp.ui.common.throttle
import cash.z.ecc.android.sdk.demoapp.util.fromResources
import cash.z.ecc.android.sdk.internal.Twig
import cash.z.ecc.android.sdk.model.Account
import cash.z.ecc.android.sdk.model.AccountBalance
import cash.z.ecc.android.sdk.model.AccountUuid
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.ObserveFiatCurrencyResult
import cash.z.ecc.android.sdk.model.PercentDecimal
import cash.z.ecc.android.sdk.model.PersistableWallet
import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.TransactionSubmitResult
import cash.z.ecc.android.sdk.model.WalletAddresses
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.model.ZcashNetwork
import cash.z.ecc.android.sdk.model.ZecSend
import cash.z.ecc.android.sdk.model.proposeSend
import cash.z.ecc.android.sdk.model.send
import cash.z.ecc.android.sdk.tool.DerivationTool
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

// To make this more multiplatform compatible, we need to remove the dependency on Context
// for loading the preferences.
@Suppress("TooManyFunctions")
class WalletViewModel(
    private val application: Application
) : AndroidViewModel(application) {
    private val walletCoordinator = WalletCoordinator.getInstance(application)

    /*
     * Using the Mutex may be overkill, but it ensures that if multiple calls are accidentally made
     * that they have a consistent ordering.
     */
    private val persistWalletMutex = Mutex()

    /**
     * Synchronizer that is retained long enough to survive configuration changes.
     */
    val synchronizer =
        walletCoordinator.synchronizer.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
            null
        )

    val secretState: StateFlow<SecretState> =
        walletCoordinator.persistableWallet
            .map { persistableWallet ->
                if (null == persistableWallet) {
                    SecretState.None
                } else {
                    SecretState.Ready(persistableWallet)
                }
            }.stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
                SecretState.Loading
            )

    val spendingKey =
        secretState
            .filterIsInstance<SecretState.Ready>()
            .map { it.persistableWallet }
            .map { secretState ->
                val bip39Seed =
                    withContext(Dispatchers.IO) {
                        Mnemonics.MnemonicCode(secretState.seedPhrase.joinToString()).toSeed()
                    }
                getCurrentAccount().hdAccountIndex?.let { accountIndex ->
                    DerivationTool.getInstance().deriveUnifiedSpendingKey(
                        seed = bip39Seed,
                        network = secretState.network,
                        accountIndex = accountIndex
                    )
                }
            }.stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
                null
            )

    @OptIn(ExperimentalCoroutinesApi::class)
    val walletSnapshot: StateFlow<WalletSnapshot?> =
        synchronizer
            .flatMapLatest {
                if (null == it) {
                    flowOf(null)
                } else {
                    it.toWalletSnapshot()
                }
            }.throttle(1.seconds)
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
                null
            )

    val addresses: StateFlow<WalletAddresses?> =
        synchronizer
            .filterNotNull()
            .map {
                runCatching {
                    WalletAddresses.new(getCurrentAccount(), it)
                }.onFailure {
                    Twig.warn { "Wait until the SDK starts providing the addresses" }
                }.getOrNull()
            }.stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
                null
            )

    private val mutableSendState = MutableStateFlow<SendState>(SendState.None)

    val sendState: StateFlow<SendState> = mutableSendState

    /**
     * Creates a wallet asynchronously and then persists it.  Clients observe
     * [secretState] to see the side effects.  This would be used for a user creating a new wallet.
     *
     * Although waiting for the wallet to be written and then read back is slower, it is probably
     * safer because it 1. guarantees the wallet is written to disk and 2. has a single source of truth.
     */
    fun persistNewWallet() {
        val application = getApplication<Application>()

        viewModelScope.launch {
            val network = ZcashNetwork.fromResources(application)
            val newWallet =
                PersistableWallet.new(
                    application = application,
                    zcashNetwork = network,
                    endpoint = LightWalletEndpoint.defaultForNetwork(network),
                    walletInitMode = WalletInitMode.NewWallet,
                )
            persistWallet(newWallet)
        }
    }

    /**
     * Persists a wallet asynchronously.  Clients observe [secretState]
     * to see the side effects.  This would be used for a user restoring a wallet from a backup.
     */
    fun persistExistingWallet(persistableWallet: PersistableWallet) {
        persistWallet(persistableWallet)
    }

    /**
     * Persists a wallet asynchronously.  Clients observe [secretState] to see the side effects.
     */
    private fun persistWallet(persistableWallet: PersistableWallet) {
        val application = getApplication<Application>()

        viewModelScope.launch {
            val preferenceProvider = EncryptedPreferenceSingleton.getInstance(application)
            persistWalletMutex.withLock {
                EncryptedPreferenceKeys.PERSISTABLE_WALLET.putValue(preferenceProvider, persistableWallet)
            }
        }
    }

    /**
     * Asynchronously sends funds.  Note that two sending operations cannot occur at the same time.
     *
     * Observe the result via [sendState].
     */
    fun send(zecSend: ZecSend) {
        if (sendState.value is SendState.Sending) {
            return
        }

        mutableSendState.value = SendState.Sending

        val synchronizer = synchronizer.value

        if (null != synchronizer) {
            val account = getCurrentAccount()
            viewModelScope.launch {
                val spendingKey = spendingKey.filterNotNull().first()
                runCatching { synchronizer.send(spendingKey, account, zecSend) }
                    .onSuccess { mutableSendState.value = SendState.Sent(it.toList()) }
                    .onFailure { mutableSendState.value = SendState.Error(it) }
            }
        } else {
            SendState.Error(IllegalStateException("Unable to send funds because synchronizer is not loaded."))
        }
    }

    /**
     * Synchronously provides proposal object for the given [spendingKey] and [zecSend] objects
     */
    fun getSendProposal(zecSend: ZecSend): Proposal? {
        if (sendState.value is SendState.Sending) {
            return null
        }

        val synchronizer = synchronizer.value

        return if (null != synchronizer) {
            val account = getCurrentAccount()
            // Calling the proposal API within a blocking coroutine should be fine for the showcase purpose
            runBlocking {
                kotlin
                    .runCatching {
                        synchronizer.proposeSend(account, zecSend)
                    }.onFailure {
                        Twig.error(it) { "Failed to get transaction proposal" }
                    }.getOrNull()
            }
        } else {
            error("Unable to send funds because synchronizer is not loaded.")
        }
    }

    /**
     * Synchronously provides proposal object for the given [spendingKey] and [uri] objects
     */
    fun getSendProposalFromUri(uri: String): Proposal? {
        if (sendState.value is SendState.Sending) {
            return null
        }

        val synchronizer = synchronizer.value

        return if (null != synchronizer) {
            val account = getCurrentAccount()
            // Calling the proposal API within a blocking coroutine should be fine for the showcase purpose
            runBlocking {
                kotlin
                    .runCatching {
                        synchronizer.proposeFulfillingPaymentUri(account, uri)
                    }.onFailure {
                        Twig.error(it) { "Failed to get transaction proposal from uri" }
                    }.getOrNull()
            }
        } else {
            error("Unable to send funds because synchronizer is not loaded.")
        }
    }

    /**
     * Asynchronously shields transparent funds.  Note that two shielding operations cannot occur at the same time.
     *
     * Observe the result via [sendState].
     */
    fun shieldFunds() {
        if (sendState.value is SendState.Sending) {
            return
        }

        mutableSendState.value = SendState.Sending

        val synchronizer = synchronizer.value
        if (null != synchronizer) {
            val account = getCurrentAccount()
            viewModelScope.launch {
                val spendingKey = spendingKey.filterNotNull().first()
                kotlin
                    .runCatching {
                        @Suppress("MagicNumber")
                        synchronizer.proposeShielding(account, Zatoshi(100000))?.let {
                            synchronizer.createProposedTransactions(
                                it,
                                spendingKey
                            )
                        }
                    }.onSuccess { it?.let { mutableSendState.value = SendState.Sent(it.toList()) } }
                    .onFailure { mutableSendState.value = SendState.Error(it) }
            }
        } else {
            SendState.Error(IllegalStateException("Unable to send funds because synchronizer is not loaded."))
        }
    }

    fun clearSendOrShieldState() {
        mutableSendState.value = SendState.None
    }

    /**
     * This method only has an effect if the synchronizer currently is loaded.
     */
    fun rescanBlockchain() {
        viewModelScope.launch {
            walletCoordinator.rescanBlockchain()
        }
    }

    /**
     * This asynchronously resets the SDK state.  This is non-destructive, as SDK state can be rederived.
     *
     * This could be used as a troubleshooting step in debugging.
     */
    fun resetSdk() {
        walletCoordinator.resetSdk()
    }

    /**
     * This rewinds to the nearest height, i.e. 100 blocks back from the current chain tip.
     */
    fun rewind() {
        val synchronizer = synchronizer.value
        val currentBlockHeight = synchronizer?.networkHeight?.value
        if (null != synchronizer && null != currentBlockHeight) {
            viewModelScope.launch {
                synchronizer.rewindToNearestHeight(
                    BlockHeight.new(currentBlockHeight.value - QUICK_REWIND_BLOCKS)
                )
            }
        }
    }

    /**
     * This safely and asynchronously stops the [Synchronizer].
     */
    fun closeSynchronizer() {
        val synchronizer = synchronizer.value
        if (null != synchronizer) {
            viewModelScope.launch {
                (synchronizer as SdkSynchronizer).close()
            }
        }
    }

    fun getAccounts(): List<Account> {
        val synchronizer = synchronizer.value

        return if (null != synchronizer) {
            runBlocking {
                kotlin
                    .runCatching {
                        synchronizer.getAccounts()
                    }.onFailure {
                        Twig.error(it) { "Failed to get wallet accounts" }
                    }.getOrThrow()
            }
        } else {
            error("Unable get wallet accounts.")
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val accounts: StateFlow<List<Account>> =
        synchronizer
            .filterNotNull()
            .flatMapLatest {
                it.accountsFlow.filterNotNull()
            }.stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
                emptyList()
            )

    fun getCurrentAccount(): Account = getAccounts()[CURRENT_ZIP_32_ACCOUNT_INDEX.toInt()]

    fun estimateBirthday(selection: Instant): BlockHeight =
        runBlocking {
            runCatching {
                SdkSynchronizer.estimateBirthdayHeight(
                    application.applicationContext,
                    selection,
                    ZcashNetwork.fromResources(application)
                )
            }.onFailure {
                Twig.error(it) { "Failed to estimate the wallet birthday height based on: $selection." }
            }.getOrThrow()
        }

    companion object {
        private const val QUICK_REWIND_BLOCKS = 100
    }
}

/**
 * Represents the state of the wallet secret.
 */
sealed class SecretState {
    object Loading : SecretState()

    object None : SecretState()

    class Ready(
        val persistableWallet: PersistableWallet
    ) : SecretState()
}

sealed class SendState {
    object None : SendState() {
        override fun toString(): String = "None"
    }

    object Sending : SendState() {
        override fun toString(): String = "Sending"
    }

    class Sent(
        val txIds: List<TransactionSubmitResult>
    ) : SendState() {
        override fun toString(): String = "Sent"
    }

    class Error(
        val error: Throwable
    ) : SendState() {
        override fun toString(): String = "Error ${error.message}"
    }
}

// TODO [#529]: Localize Synchronizer Errors
// TODO [#529]: https://github.com/zcash/secant-android-wallet/issues/529

/**
 * Represents all kind of Synchronizer errors
 */

sealed class SynchronizerError {
    abstract fun getCauseMessage(): String?

    class Critical(
        val error: Throwable?
    ) : SynchronizerError() {
        override fun getCauseMessage(): String? = error?.localizedMessage
    }

    class Processor(
        val error: Throwable?
    ) : SynchronizerError() {
        override fun getCauseMessage(): String? = error?.localizedMessage
    }

    class Submission(
        val error: Throwable?
    ) : SynchronizerError() {
        override fun getCauseMessage(): String? = error?.localizedMessage
    }

    class Setup(
        val error: Throwable?
    ) : SynchronizerError() {
        override fun getCauseMessage(): String? = error?.localizedMessage
    }

    class Chain(
        val x: BlockHeight,
        val y: BlockHeight
    ) : SynchronizerError() {
        override fun getCauseMessage(): String = "$x, $y"
    }
}

private fun Synchronizer.toCommonError(): Flow<SynchronizerError?> =
    callbackFlow {
        // just for initial default value emit
        trySend(null)

        onCriticalErrorHandler = {
            Twig.error { "WALLET - Error Critical: $it" }
            trySend(SynchronizerError.Critical(it))
            false
        }
        onProcessorErrorHandler = {
            Twig.error { "WALLET - Error Processor: $it" }
            trySend(SynchronizerError.Processor(it))
            false
        }
        onSetupErrorHandler = {
            Twig.error { "WALLET - Error Setup: $it" }
            trySend(SynchronizerError.Setup(it))
            false
        }
        onChainErrorHandler = { x, y ->
            Twig.error { "WALLET - Error Chain: $x, $y" }
            trySend(SynchronizerError.Chain(x, y))
        }

        awaitClose {
            // nothing to close here
        }
    }

// No good way around needing magic numbers for the indices
@Suppress("MagicNumber", "UNCHECKED_CAST")
private fun Synchronizer.toWalletSnapshot() =
    combine(
        // 0
        status,
        // 1
        processorInfo,
        // 2
        walletBalances.filterNotNull(),
        // 3
        exchangeRateUsd,
        // 4
        progress,
        // 5
        areFundsSpendable,
        // 6
        toCommonError()
    ) { flows ->
        val exchangeRateUsd = flows[3] as ObserveFiatCurrencyResult
        val progressPercentDecimal = (flows[4] as PercentDecimal)

        WalletSnapshot(
            flows[0] as Synchronizer.Status,
            flows[1] as CompactBlockProcessor.ProcessorInfo,
            flows[2] as Map<AccountUuid, AccountBalance>,
            exchangeRateUsd.currencyConversion?.priceOfZec?.toBigDecimal(),
            progressPercentDecimal,
            flows[5] as Boolean,
            flows[6] as SynchronizerError?
        )
    }
