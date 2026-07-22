package cash.z.ecc.android.sdk

import android.content.Context
import cash.z.ecc.android.sdk.ext.onFirst
import cash.z.ecc.android.sdk.internal.Twig
import cash.z.ecc.android.sdk.model.AccountCreateSetup
import cash.z.ecc.android.sdk.model.FirstClassByteArray
import cash.z.ecc.android.sdk.model.PersistableWallet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * @param persistableWallet flow of the user's stored wallet.  Null indicates that no wallet has been stored.
 * @param isTorEnabled flow indicating whether tor has been enabled for Synchronizer features supporting tor connection
 * @param isSyncBlocked flow indicating whether some external condition requires sync to stay stopped (e.g. a
 * pending Orchard migration transfer needs sync and broadcast decoupled in time for privacy). Gated the same way
 * as [isTorEnabled] — while true, the synchronizer is closed and will not reopen, regardless of [persistableWallet].
 * @param accountName A human-readable name for the account, that will be used while instantiating [Synchronizer.new]
 * @param keySource A string identifier or other metadata describing the source of the seed, that will be used while
 * instantiating [Synchronizer.new]
 *
 * One area where this class needs to change before it can be moved out of the incubator is that we need to be able to
 * start synchronization without necessarily decrypting the wallet.
 *
 * Another area that likely needs change is to alter the persistableWallet flow to support a status of "needs
 * authentication."
 */
class WalletCoordinator(
    context: Context,
    val persistableWallet: Flow<PersistableWallet?>,
    val isTorEnabled: Flow<Boolean?>,
    val isExchangeRateEnabled: Flow<Boolean?>,
    val isSyncBlocked: Flow<Boolean>,
    val accountName: String,
    val keySource: String?,
) {
    private val applicationContext = context.applicationContext

    /*
     * We want a global scope that is independent of the lifecycles of either
     * WorkManager or the UI.
     */
    @OptIn(DelicateCoroutinesApi::class)
    private val walletScope = CoroutineScope(GlobalScope.coroutineContext + Dispatchers.Main)

    private val synchronizerMutex = Mutex()

    private val lockoutMutex = Mutex()
    private val synchronizerLockoutId = MutableStateFlow<UUID?>(null)

    private sealed class InternalSynchronizerStatus {
        object NoWallet : InternalSynchronizerStatus()

        class Available(
            val synchronizer: Synchronizer
        ) : InternalSynchronizerStatus()

        class Lockout(
            val id: UUID
        ) : InternalSynchronizerStatus()

        object Blocked : InternalSynchronizerStatus()
    }

    @Suppress("DestructuringDeclarationWithTooManyEntries")
    @OptIn(ExperimentalCoroutinesApi::class)
    private val synchronizerOrLockoutId: Flow<InternalSynchronizerStatus> =
        combine(
            persistableWallet,
            synchronizerLockoutId,
            isTorEnabled,
            isExchangeRateEnabled,
            isSyncBlocked
        ) { persistableWallet, lockoutId, isTorEnabled, isExchangeRateEnabled, isSyncBlocked ->
            SynchronizerLockoutInternalState(
                persistableWallet = persistableWallet,
                lockoutId = lockoutId,
                isTorEnabled = isTorEnabled,
                isExchangeRateEnabled = isExchangeRateEnabled,
                isSyncBlocked = isSyncBlocked
            )
            // isSyncBlocked's first real value always arrives asynchronously, after this combine()
            // has already fired once using its stale placeholder — without dropping that redundant
            // re-emission, flatMapLatest below would cancel an in-flight Synchronizer.new() (and
            // therefore its checkpoint loading) on every cold start, even though nothing changed.
        }.distinctUntilChanged()
            .flatMapLatest { (persistableWallet, lockoutId, isTorEnabled, isExchangeRateEnabled, isSyncBlocked) ->
            if (null != lockoutId) { // this one needs to come first
                flowOf(InternalSynchronizerStatus.Lockout(lockoutId))
            } else if (isSyncBlocked) {
                flowOf(InternalSynchronizerStatus.Blocked)
            } else if (null == persistableWallet) {
                flowOf(InternalSynchronizerStatus.NoWallet)
            } else {
                callbackFlow<InternalSynchronizerStatus.Available> {
                    val closeableSynchronizer =
                        Synchronizer.new(
                            context = context,
                            zcashNetwork = persistableWallet.network,
                            lightWalletEndpoint = persistableWallet.endpoint,
                            birthday = persistableWallet.birthday,
                            setup =
                                AccountCreateSetup(
                                    accountName = accountName,
                                    keySource = keySource,
                                    seed = FirstClassByteArray(persistableWallet.seedPhrase.toByteArray())
                                ),
                            walletInitMode = persistableWallet.walletInitMode,
                            isTorEnabled = isTorEnabled == true,
                            isExchangeRateEnabled = isExchangeRateEnabled == true
                        )

                    trySend(InternalSynchronizerStatus.Available(closeableSynchronizer))
                    awaitClose {
                        Twig.info { "Closing flow and stopping synchronizer" }
                        closeableSynchronizer.close()
                    }
                }
            }
        }

    /**
     * Synchronizer for the Zcash SDK. Emits null until a wallet secret is persisted.
     *
     * Note that this synchronizer is closed as soon as it stops being collected.  For UI use
     * cases, see [WalletViewModel].
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val synchronizer: StateFlow<Synchronizer?> =
        synchronizerOrLockoutId
            .map {
                when (it) {
                    is InternalSynchronizerStatus.Available -> it.synchronizer
                    is InternalSynchronizerStatus.Lockout -> null
                    InternalSynchronizerStatus.NoWallet -> null
                    InternalSynchronizerStatus.Blocked -> null
                }
            }.stateIn(
                scope = walletScope,
                started = SharingStarted.WhileSubscribed(0, 0),
                initialValue = null
            )

    /**
     * Rescans the blockchain.
     *
     * In order for a rescan to occur, the synchronizer must be loaded already
     * which would happen if the UI is collecting it.
     *
     * @return True if the rescan was performed and false if the rescan was not performed.
     */
    suspend fun rescanBlockchain(): Boolean {
        synchronizerMutex.withLock {
            synchronizer.value?.let {
                it.latestBirthdayHeight?.let { height ->
                    it.rewindToNearestHeight(height)
                    return true
                }
            }
        }

        return false
    }

    fun resetSynchronizer() {
        walletScope.launch {
            lockoutMutex.withLock {
                if (synchronizer.value != null || persistableWallet.first() == null) {
                    synchronizerLockoutId.update { UUID.randomUUID() }
                    synchronizer.first { it == null }
                    synchronizerLockoutId.update { null }
                }
            }
        }
    }

    /**
     * Resets persisted data in the SDK, but preserves the wallet secret.  This will cause the
     * WalletCoordinator to emit a new synchronizer instance.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun resetSdk() {
        walletScope.launch {
            val zcashNetwork = persistableWallet.first()?.network
            if (null != zcashNetwork) {
                lockoutMutex.withLock {
                    val lockoutId = UUID.randomUUID()
                    synchronizerLockoutId.update { lockoutId }

                    synchronizerOrLockoutId
                        .filterIsInstance<InternalSynchronizerStatus.Lockout>()
                        .filter { it.id == lockoutId }
                        .onFirst {
                            synchronizerMutex.withLock {
                                val didDelete =
                                    Synchronizer.erase(
                                        appContext = applicationContext,
                                        network = zcashNetwork
                                    )
                                Twig.info { "SDK erase result: $didDelete" }
                            }
                        }

                    synchronizerLockoutId.update { null }
                }
            }
        }
    }

    /**
     * This Flow-providing function deletes all the persisted data in the SDK (databases associated with this wallet,
     * all compact blocks, and data derived from those blocks) but preserves the wallet secrets. This function
     * requires secrets available on the device at the time of running.
     */
    fun deleteSdkDataFlow(): Flow<Boolean> =
        callbackFlow {
            walletScope.launch {
                val zcashNetwork = persistableWallet.first()?.network
                if (null != zcashNetwork) {
                    synchronizerMutex.withLock {
                        val didDelete =
                            Synchronizer.erase(
                                appContext = applicationContext,
                                network = zcashNetwork
                            )
                        Twig.info { "SDK erase result: $didDelete" }
                        trySend(didDelete)
                    }
                }
            }
            awaitClose {
                // Nothing to close here
            }
        }

    // Allows for extension functions
    companion object
}

private data class SynchronizerLockoutInternalState(
    val persistableWallet: PersistableWallet?,
    val lockoutId: UUID?,
    val isTorEnabled: Boolean?,
    val isExchangeRateEnabled: Boolean?,
    val isSyncBlocked: Boolean
)
