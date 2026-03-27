package cash.z.ecc.android.sdk

import android.content.Context
import cash.z.ecc.android.sdk.Synchronizer.Companion.new
import cash.z.ecc.android.sdk.WalletInitMode.RestoreWallet
import cash.z.ecc.android.sdk.block.processor.CompactBlockProcessor
import cash.z.ecc.android.sdk.exception.InitializeException
import cash.z.ecc.android.sdk.exception.PcztException
import cash.z.ecc.android.sdk.exception.RustLayerException
import cash.z.ecc.android.sdk.exception.TorInitializationErrorException
import cash.z.ecc.android.sdk.exception.TorUnavailableException
import cash.z.ecc.android.sdk.ext.ZcashSdk
import cash.z.ecc.android.sdk.internal.FastestServerFetcher
import cash.z.ecc.android.sdk.internal.Files
import cash.z.ecc.android.sdk.internal.SaplingParamFetcher
import cash.z.ecc.android.sdk.internal.SaplingParamTool
import cash.z.ecc.android.sdk.internal.Twig
import cash.z.ecc.android.sdk.internal.db.DatabaseCoordinator
import cash.z.ecc.android.sdk.internal.exchange.UsdExchangeRateFetcher
import cash.z.ecc.android.sdk.internal.model.TorClient
import cash.z.ecc.android.sdk.internal.model.ext.toBlockHeight
import cash.z.ecc.android.sdk.internal.storage.preference.StandardPreferenceProvider
import cash.z.ecc.android.sdk.model.Account
import cash.z.ecc.android.sdk.model.AccountBalance
import cash.z.ecc.android.sdk.model.AccountCreateSetup
import cash.z.ecc.android.sdk.model.AccountImportSetup
import cash.z.ecc.android.sdk.model.AccountUuid
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.FastestServersResult
import cash.z.ecc.android.sdk.model.ObserveFiatCurrencyResult
import cash.z.ecc.android.sdk.model.Pczt
import cash.z.ecc.android.sdk.model.PercentDecimal
import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.SdkFlags
import cash.z.ecc.android.sdk.model.SingleUseTransparentAddress
import cash.z.ecc.android.sdk.model.TransactionId
import cash.z.ecc.android.sdk.model.TransactionOutput
import cash.z.ecc.android.sdk.model.TransactionOverview
import cash.z.ecc.android.sdk.model.TransactionRecipient
import cash.z.ecc.android.sdk.model.TransactionSubmitResult
import cash.z.ecc.android.sdk.model.UnifiedAddressRequest
import cash.z.ecc.android.sdk.model.UnifiedSpendingKey
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.model.ZcashNetwork
import cash.z.ecc.android.sdk.tool.CheckpointTool
import cash.z.ecc.android.sdk.type.AddressType
import cash.z.ecc.android.sdk.type.ConsensusMatchType
import cash.z.ecc.android.sdk.type.ServerValidation
import cash.z.ecc.android.sdk.util.WalletClientFactory
import co.electriccoin.lightwallet.client.ServiceMode
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import co.electriccoin.lightwallet.client.model.Response
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngineConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import java.io.Closeable

@Suppress("TooManyFunctions")
interface Synchronizer {
    // Status

    /**
     * The network to which this synchronizer is connected and from which it is processing blocks.
     */
    val network: ZcashNetwork

    /**
     * A flow of values representing the [Status] of this Synchronizer. As the status changes, a new
     * value will be emitted by this flow.
     */
    val status: Flow<Status>

    /**
     * Indicates the download progress of the Synchronizer.
     *
     * When progress reaches `PercentDecimal.ONE_HUNDRED_PERCENT`, it signals that the Synchronizer
     * is up-to-date with the network's current chain tip. Balances should be considered inaccurate
     * and outbound transactions should be prevented until this sync is complete.
     */
    val progress: Flow<PercentDecimal>

    /**
     * Indicates whether are the shielded wallet balances spendable or not during the block synchronization process.
     */
    val areFundsSpendable: Flow<Boolean>

    /**
     * A flow of processor details, updated every time blocks are processed to include the latest
     * block height, blocks downloaded and blocks scanned. Similar to the [progress] flow but with a
     * lot more detail.
     */
    val processorInfo: Flow<CompactBlockProcessor.ProcessorInfo>

    /**
     * The latest height observed on the network, which does not necessarily correspond to the
     * latest downloaded height or scanned height. Although this is present in [processorInfo], it
     * is such a frequently used value that it is convenient to have the real-time value by itself.
     */
    val networkHeight: StateFlow<BlockHeight?>

    /**
     * A stream of wallet balances
     */
    val walletBalances: StateFlow<Map<AccountUuid, AccountBalance>?>

    /**
     * The latest known USD/ZEC exchange rate, paired with the time it was queried.
     *
     * The rate can be initialized and refreshed by calling [refreshExchangeRateUsd].
     */
    val exchangeRateUsd: StateFlow<ObserveFiatCurrencyResult>

    /**
     * A flow of all the transactions that are on the blockchain.
     */
    val allTransactions: Flow<List<TransactionOverview>>

    //
    // Latest Properties
    //

    /**
     * An in-memory reference to the latest height seen on the network.
     */
    val latestHeight: BlockHeight?

    /**
     * An in-memory reference to the best known birthday height, which can change if the first
     * transaction has not yet occurred.
     */
    val latestBirthdayHeight: BlockHeight?

    //
    // Operations
    //

    /**
     * Returns all the wallet accounts or throws [InitializeException.GetAccountsException]
     *
     * @return List of all wallet accounts
     * @throws [InitializeException.GetAccountsException] in case of the operation failure
     */
    suspend fun getAccounts(): List<Account>

    /**
     * Returns all the wallet accounts or throws [InitializeException.GetAccountsException]
     *
     * It's a Flow version of [getAccounts]
     *
     * @return Flow of all wallet accounts
     * @throws [InitializeException.GetAccountsException] in case of the operation failure
     */
    val accountsFlow: Flow<List<Account>?>

    /**
     * Emits error states of the synchronizer.
     */
    val initializationError: InitializationError?

    /**
     * Tells the wallet to track an account using a unified full viewing key.
     *
     * Returns details about the imported account, including the unique account identifier for
     * the newly-created wallet database entry. Unlike the other account creation APIs, no spending
     * key is returned because the wallet has no information about the mnemonic phrase from which
     * the UFVK was derived.
     *
     * @param purpose Metadata describing whether or not data required for spending should be tracked by the wallet
     * @param setup The account's setup information. See [AccountImportSetup] for more.
     *
     * @return Account containing details about the imported account, including the unique account identifier for the
     * newly-created wallet database entry
     *
     * @throws [InitializeException.ImportAccountException] in case of the operation failure
     */
    suspend fun importAccountByUfvk(setup: AccountImportSetup): Account

    /*
     * Not ready to be a public API; internal for testing only
     *
     * Adds the next available account-level spend authority, given the current set of
     * [ZIP 316](https://zips.z.cash/zip-0316) account identifiers known, to the wallet
     * database.
     *
     * The caller should store the byte encoding of the returned spending key in a secure
     * fashion. This encoding MUST NOT be exposed to users. It is an internal encoding
     * that is inherently unstable, and only intended to be passed between the SDK and the
     * storage backend. The caller MUST NOT allow this encoding to be exported or
     * imported.
     *
     * If `seed` was imported from a backup and this method is being used to restore a
     * previous wallet state, you should use this method to add all of the desired
     * accounts before scanning the chain from the seed's birthday height.
     *
     * By convention, wallets should only allow a new account to be generated after funds
     * have been received by the currently-available account (in order to enable
     * automated account recovery).
     *
     * @param recoverUntil An optional height at which the wallet should exit "recovery mode"
     * @param setup The wallet's setup information. See [AccountCreateSetup] for more.
     * @param treeState The tree state corresponding to the last block prior to the wallet's birthday height
     *
     * @return the newly created ZIP 316 account identifier, along with the binary
     * encoding of the `UnifiedSpendingKey` for the newly created account.
     *
     * @throws [InitializeException.CreateAccountException] in case of the operation failure
     *
    suspend fun createAccount(
        setup: AccountCreateSetup,
        treeState: TreeState,
        recoverUntil: BlockHeight?
    ): UnifiedSpendingKey
     */

    /**
     * Measure connection quality and speed of given [servers].
     *
     * @return a [Flow] of fastest servers which updates it's state during measurement stages
     */
    suspend fun getFastestServers(servers: List<LightWalletEndpoint>): Flow<FastestServersResult>

    /**
     * Gets the current unified address for the given account.
     *
     * @param account the account whose address is of interest.
     *
     * @return the current unified address for the given account.
     *
     * @throws RustLayerException.GetAddressException in case of the operation
     */
    @Throws(RustLayerException.GetAddressException::class)
    suspend fun getUnifiedAddress(account: Account): String

    /**
     * Gets a new unified address that conforms to the specified request.
     *
     * @param account the account whose address is of interest.
     * @param request a description of the receivers to create in the newly generated address.
     *
     * @return the current unified address for the given account.
     *
     * @throws RustLayerException.GetAddressException if the account cannot create an address with the requested
     * receivers.
     */
    @Throws(RustLayerException.GetAddressException::class)
    suspend fun getCustomUnifiedAddress(account: Account, request: UnifiedAddressRequest): String

    /**
     * Gets the legacy Sapling address corresponding to the current unified address for the given account.
     *
     * @param account the account whose address is of interest.
     *
     * @return a legacy Sapling address for the given account.
     *
     * @throws RustLayerException.GetAddressException in case of the operation
     */
    @Throws(RustLayerException.GetAddressException::class)
    suspend fun getSaplingAddress(account: Account): String

    /**
     * Gets the legacy transparent address corresponding to the current unified address for the given account.
     *
     * @param account the account whose address is of interest.
     *
     * @return a legacy transparent address for the given account.
     *
     * @throws RustLayerException.GetAddressException in case of the operation
     */
    @Throws(RustLayerException.GetAddressException::class)
    suspend fun getTransparentAddress(account: Account): String

    /**
     * Refreshes [exchangeRateUsd].
     */
    fun refreshExchangeRateUsd()

    /**
     * Creates a proposal for transferring funds to the given recipient.
     *
     * @param account the account from which to transfer funds.
     * @param recipient the recipient's address.
     * @param amount the amount of zatoshi to send.
     * @param memo the optional memo to include as part of the proposal's transactions.
     *
     * @return the proposal or an exception
     */
    suspend fun proposeTransfer(
        account: Account,
        recipient: String,
        amount: Zatoshi,
        memo: String = ""
    ): Proposal

    /**
     * Creates a proposal for fulfilling a payment ZIP-321 URI
     *
     * @param account the account from which to transfer funds.
     * @param uri a ZIP-321 compliant payment URI String
     *
     * @return the proposal or an exception
     */
    suspend fun proposeFulfillingPaymentUri(
        account: Account,
        uri: String
    ): Proposal

    /**
     * Creates a proposal for shielding any transparent funds received by the given account.
     *
     * @param account the account for which to shield funds.
     * @param shieldingThreshold the minimum transparent balance required before a
     *                           proposal will be created.
     * @param memo the optional memo to include as part of the proposal's transactions.
     * @param transparentReceiver a specific transparent receiver within the account that
     *                            should be the source of transparent funds. Default is
     *                            null which will select whichever of the account's
     *                            transparent receivers has funds to shield.
     *
     * @return the proposal, or null if the transparent balance that would be shielded is
     *         zero or below `shieldingThreshold`.
     *
     * @throws Exception if `transparentReceiver` is null and there are transparent funds
     *         in more than one of the account's transparent receivers.
     */
    suspend fun proposeShielding(
        account: Account,
        shieldingThreshold: Zatoshi,
        memo: String = ZcashSdk.DEFAULT_SHIELD_FUNDS_MEMO_PREFIX,
        transparentReceiver: String? = null
    ): Proposal?

    /**
     * Creates the transactions in the given proposal.
     *
     * @param proposal the proposal for which to create transactions.
     * @param usk the unified spending key associated with the account for which the
     *            proposal was created.
     *
     * @return a flow of result objects for the transactions that were created as part of
     *         the proposal, indicating whether they were submitted to the network or if
     *         an error occurred.
     */
    suspend fun createProposedTransactions(
        proposal: Proposal,
        usk: UnifiedSpendingKey
    ): Flow<TransactionSubmitResult>

    /**
     * Creates a partially-created (unsigned without proofs) transaction from the given proposal.
     *
     * Do not call this multiple times in parallel, or you will generate PCZT instances that, if
     * finalized, would double-spend the same notes.
     *
     * @param accountUuid The account for which the proposal was created.
     * @param proposal The proposal for which to create the transaction.
     *
     * @return The partially created transaction in [Pczt] format.
     *
     * @throws PcztException.CreatePcztFromProposalException as a common indicator of the operation failure
     */
    @Throws(PcztException.CreatePcztFromProposalException::class)
    suspend fun createPcztFromProposal(
        accountUuid: AccountUuid,
        proposal: Proposal
    ): Pczt

    /**
     * Redacts information from the given PCZT that is unnecessary for the Signer role.
     *
     * @param pczt The partially created transaction in its serialized format.
     *
     * @return the updated PCZT in its serialized format.
     *
     * @throws PcztException.RedactPcztForSignerException as a common indicator of the operation failure
     */
    @Throws(PcztException.RedactPcztForSignerException::class)
    suspend fun redactPcztForSigner(pczt: Pczt): Pczt

    /**
     * Checks whether the caller needs to have downloaded the Sapling parameters.
     *
     * @param pczt The partially created transaction in its serialized format.
     *
     * @return `true` if this PCZT requires Sapling proofs.
     *
     * @throws PcztException.PcztRequiresSaplingProofsException as a common indicator of the operation failure
     */
    @Throws(PcztException.PcztRequiresSaplingProofsException::class)
    suspend fun pcztRequiresSaplingProofs(pczt: Pczt): Boolean

    /**
     * Adds proofs to the given PCZT.
     *
     * @param pczt The partially created transaction in its serialized format.
     *
     * @return The updated PCZT in its serialized format.
     *
     * @throws PcztException.AddProofsToPcztException as a common indicator of the operation failure
     */
    @Throws(PcztException.AddProofsToPcztException::class)
    suspend fun addProofsToPczt(pczt: Pczt): Pczt

    /**
     * Takes a PCZT that has been separately proven and signed, finalizes it, and stores
     * it in the wallet. Internally, this logic also submits and checks the newly stored and encoded transaction.
     *
     * @param pcztWithProofs
     * @param pcztWithSignatures
     *
     * @return The submission result of the completed transaction.
     *
     * @throws PcztException.ExtractAndStoreTxFromPcztException as a common indicator of the operation failure
     */
    @Throws(PcztException.ExtractAndStoreTxFromPcztException::class)
    suspend fun createTransactionFromPczt(
        pcztWithProofs: Pczt,
        pcztWithSignatures: Pczt,
    ): Flow<TransactionSubmitResult>

    // TODO [#1534]: Add RustLayerException.ValidateAddressException
    // TODO [#1534]: https://github.com/Electric-Coin-Company/zcash-android-wallet-sdk/issues/1534

    /**
     * Returns true when the given address is a valid z-addr. Invalid addresses will throw an
     * exception. See valid z-addresses characteristics in related ZIP.
     *
     * @param address the address to validate.
     *
     * @return true when the given address is a valid z-addr.
     *
     * @throws RuntimeException when the address is invalid.
     */
    suspend fun isValidShieldedAddr(address: String): Boolean

    /**
     * Returns true when the given address is a valid t-addr. Invalid addresses will throw an
     * exception. See valid t-addresses characteristics in related ZIP.
     *
     * @param address the address to validate.
     *
     * @return true when the given address is a valid t-addr.
     *
     * @throws RuntimeException when the address is invalid.
     */
    suspend fun isValidTransparentAddr(address: String): Boolean

    /**
     * Returns true when the given address is a valid ZIP 316 unified address.
     *
     * This method is intended for type checking (e.g. form validation). Invalid
     * addresses will throw an exception.
     *
     * @param address the address to validate.
     *
     * @return true when the given address is a valid unified address.
     *
     * @throws RuntimeException when the address is invalid.
     */
    suspend fun isValidUnifiedAddr(address: String): Boolean

    /**
     * Returns true when the given address is a valid ZIP 320 TEX address.
     *
     * This method is intended for type checking (e.g. form validation). Invalid
     * addresses will throw an exception. See valid t-addresses characteristics
     * in the related ZIP.
     *
     * @param address the address to validate.
     *
     * @return true when the given address is a valid ZIP 320 TEX address.
     *
     * @throws RuntimeException when the address is invalid.
     */
    suspend fun isValidTexAddr(address: String): Boolean

    /**
     * Validate whether the server and this SDK share the same consensus branch. This is
     * particularly important to check around network updates so that any wallet that's connected to
     * an incompatible server can surface that information effectively. For the SDK, the consensus
     * branch is used when creating transactions as each one needs to target a specific branch. This
     * function compares the server's branch id to this SDK's and returns information that helps
     * determine whether they match.
     *
     * @return an instance of [ConsensusMatchType] that is essentially a wrapper for both branch ids
     * and provides helper functions for communicating detailed errors to the user.
     */
    suspend fun validateConsensusBranch(): ConsensusMatchType

    /**
     * This function checks whether the provided server endpoint is valid. The validation is based on comparing:
     * - network types,
     * - sapling activation heights
     * - consensus branches
     *
     * @param endpoint LightWalletEndpoint data to be validated
     * @param context Context
     *
     * @return an instance of [ServerValidation] that provides details about the validation result
     */
    suspend fun validateServerEndpoint(
        context: Context,
        endpoint: LightWalletEndpoint
    ): ServerValidation

    /**
     * Validates the given address, returning information about why it is invalid. This is a
     * convenience method that combines the behavior of [isValidShieldedAddr],
     * [isValidTransparentAddr], and [isValidUnifiedAddr] into one call so that the developer
     * doesn't have to worry about handling the exceptions that they throw. Rather, exceptions
     * are converted to [AddressType.Invalid] which has a `reason` property describing why it is
     * invalid.
     *
     * @param address the address to validate.
     *
     * @return an instance of [AddressType] providing validation info regarding the given address.
     */
    suspend fun validateAddress(address: String): AddressType

    /**
     * Download all UTXOs for the given account addresses and store any new ones in the database.
     *
     * @param account The Account, for which all addresses blocks will be downloaded.
     * @param since The BlockHeight, from which blocks will be downloaded.
     *
     * @return the number of utxos that were downloaded and added to the UTXO table.
     */
    suspend fun refreshUtxos(
        account: Account,
        since: BlockHeight = network.saplingActivationHeight
    ): Int?

    /**
     * Returns the balance that the wallet knows about. This should be called after [refreshUtxos].
     */
    suspend fun getTransparentBalance(tAddr: String): Zatoshi

    /**
     * Rewinds to the safest height to which we can rewind, given a desire to rewind to the height
     * provided.
     *
     * Due to how witness incrementing works, a wallet cannot simply rewind to any
     * arbitrary height. This handles all that complexity yet remains flexible in the future as
     * improvements are made.
     *
     * Returns the height to which we actually rewound, or `null` if the rewind failed.
     */
    suspend fun rewindToNearestHeight(height: BlockHeight): BlockHeight?

    /**
     * Truncates the data database to the specified chain state.
     *
     * In contrast to [rewindToNearestHeight], this method allows the caller to truncate the wallet
     * database to a precise height. The SDK internally loads the checkpoint for the given height
     * and extracts the TreeState needed for the rewind operation.
     *
     * @param height The target block height to rewind to.
     */
    suspend fun rewindToChainState(height: BlockHeight)

    /**
     * Returns a stream of memos for a transaction. It works for both received and sent transaction.
     *
     * Note that this function internally resolves any error which comes, logs it, and then transforms it to an empty
     * string.
     *
     * @param transactionOverview For which the memos will be queried
     * @return Flow of memo strings
     */
    fun getMemos(transactionOverview: TransactionOverview): Flow<String>

    /**
     * Filters the current transactions based on their memos. It returns the transaction ID of those transactions
     * whose memo contains the given [query] or its part. Note that white space trimming or normalization is the
     * client's responsibility.
     *
     * @param query Memo or its substring
     * @return List of transaction IDs which memo contains given [query] or its part wrapped in [Flow]
     */
    fun getTransactionsByMemoSubstring(query: String): Flow<List<TransactionId>>

    /**
     * Returns a list of recipients for a transaction.
     */
    fun getRecipients(transactionOverview: TransactionOverview): Flow<TransactionRecipient>

    /**
     * Checks and provides file path to the existing data database file for the given input parameters or throws
     * [InitializeException.MissingDatabaseException] if the database does not exist yet.
     *
     * Note that it's the caller's responsibility to provide a [network] and [alias] to an existing database. Otherwise
     * [InitializeException.MissingDatabaseException] is thrown.
     *
     * @return Path to the already created data database file, or null in case none exists yet
     * @throws [InitializeException.MissingDatabaseException] When the requested database for the given inputs
     * does not exist yet.
     */
    @Throws(InitializeException.MissingDatabaseException::class)
    suspend fun getExistingDataDbFilePath(
        context: Context,
        network: ZcashNetwork,
        alias: String = ZcashSdk.DEFAULT_ALIAS
    ): String

    /**
     * Returns a list of all transaction outputs for a transaction.
     */
    suspend fun getTransactionOutputs(transactionOverview: TransactionOverview): List<TransactionOutput>

    /**
     * Returns all transactions belonging to the given account UUID
     *
     * @param accountUuid The given account UUID
     * @return Flow of transactions by the given account UUID
     */
    suspend fun getTransactions(accountUuid: AccountUuid): Flow<List<TransactionOverview>>

    /**
     * Generates and returns an ephemeral address for one-time use, such as when receiving a swap
     * from a decentralized exchange.
     */
    suspend fun getSingleUseTransparentAddress(accountUuid: AccountUuid): SingleUseTransparentAddress

    /**
     * Checks to find any single-use ephemeral addresses exposed in the past day that have not yet
     * received funds, excluding any whose next check time is in the future. This will then choose the
     * address that is most overdue for checking, retrieve any UTXOs for that address over Tor, and
     * add them to the wallet database. If no such UTXOs are found, the check will be rescheduled
     * following an expoential-backoff-with-jitter algorithm.
     */
    suspend fun checkSingleUseTransparentAddress(accountUuid: AccountUuid): Boolean

    /**
     * Queries the light wallet server to find any UTXOs associated with the given transparent
     * address, and adds any UTXOs discovered to the wallet.
     *
     * This check will cover the block range starting at the exposure height for that address, if
     * known, or otherwise at the birthday height of the specified account.
     */
    suspend fun fetchUtxosByAddress(accountUuid: AccountUuid, address: String): Boolean

    /**
     * Requests to enhance transaction enhancement for the [txId].
     */
    fun enhanceTransaction(txId: TransactionId)

    fun onBackground()

    fun onForeground()

    /**
     *
     * @param config to configure [HttpClient]
     *
     * @return http client that does http communication over Tor network
     *
     * @throws TorInitializationErrorException if an error occurred during Tor setup
     * @throws TorUnavailableException if Tor or exchange rate is not enabled
     */
    @Throws(TorInitializationErrorException::class, TorUnavailableException::class)
    suspend fun getTorHttpClient(config: HttpClientConfig<HttpClientEngineConfig>.() -> Unit = {}): HttpClient

    suspend fun debugQuery(query: String): String

    //
    // Error Handling
    //

    /**
     * Gets or sets a global error handler. This is a useful hook for handling unexpected critical
     * errors.
     *
     * @return true when the error has been handled and the Synchronizer should attempt to continue.
     * False when the error is unrecoverable and the Synchronizer should [stop].
     */
    var onCriticalErrorHandler: ((Throwable?) -> Boolean)?

    /**
     * An error handler for exceptions during processing. For instance, a block might be missing or
     * a reorg may get mishandled or the database may get corrupted.
     *
     * @return true when the error has been handled and the processor should attempt to continue.
     * False when the error is unrecoverable and the processor should [stop].
     */
    var onProcessorErrorHandler: ((Throwable?) -> Boolean)?

    var onProcessorErrorResolved: (() -> Unit)?

    /**
     * Callback for setup errors that occur prior to processing compact blocks. Can be used to
     * override any errors encountered during setup. When this listener is missing then all setup
     * errors will result in the synchronizer not starting. This is particularly useful for wallets
     * to receive a callback right before the SDK will reject a lightwalletd server because it
     * appears not to match.
     *
     * @return true when the setup error should be ignored and processing should be allowed to
     * start. Otherwise, processing will not begin.
     */
    var onSetupErrorHandler: ((Throwable?) -> Boolean)?

    /**
     * A callback to invoke whenever a chain error is encountered. These occur whenever the
     * processor detects a missing or non-chain-sequential block (i.e. a reorg). At a minimum, it is
     * best to log these errors because they are the most common source of bugs and unexpected
     * behavior in wallets, due to the chain data mutating and wallets becoming out of sync.
     */
    var onChainErrorHandler: ((BlockHeight, BlockHeight) -> Any)?

    /**
     * Represents the status of this Synchronizer, which is useful for communicating to the user.
     */
    enum class Status {
        /**
         * Indicates the initial state of Synchronizer
         */
        INITIALIZING,

        /**
         * Indicates that [stop] has been called on this Synchronizer and it will no longer be used.
         */
        STOPPED,

        /**
         * Indicates that this Synchronizer is disconnected from its lightwalletd server.
         * When set, a UI element may want to turn red.
         */
        DISCONNECTED,

        /**
         * Indicates that the Synchronizer is actively syncing new blocks. It goes through these stages internally:
         * downloading new blocks, validating these blocks, then scanning them, deleting the temporary persisted block
         * files, and enhancing transaction details at the end.
         *
         * In the downloading stage, the Synchronizer is actively downloading new blocks from the
         * server.
         *
         * In the validating stage, the Synchronizer is actively validating new blocks that were downloaded
         * from the server. Blocks need to be verified before they are scanned. This confirms that
         * each block is chain-sequential, thereby detecting missing blocks and reorgs.
         *
         * In the scanning stage, Synchronizer is actively decrypting new blocks that were downloaded
         * from the server.
         *
         * In the deleting stage are all temporary persisted block files removed from the persistence.
         *
         * In the enhancing stage is the Synchronizer actively enhancing newly scanned blocks with additional
         * transaction details, fetched from the server.
         */
        SYNCING,

        /**
         * Indicates that this Synchronizer is fully up to date and ready for all wallet functions.
         * When set, a UI element may want to turn green. In this state, the balance can be trusted.
         */
        SYNCED
    }

    enum class InitializationError {
        /**
         * Indicates that tor is required but not available.
         *
         * Typically this means that [SdkFlags.isTorEnabled] is set to true but Tor instantiation
         * failed.
         */
        TOR_NOT_AVAILABLE,
    }

    companion object {
        /**
         * Primary method that SDK clients will use to construct a synchronizer.
         *
         * @param zcashNetwork the network to use.
         *
         * @param alias A string used to segregate multiple wallets in the filesystem.  This implies the string
         * should not contain characters unsuitable for the platform's filesystem.  The default value is
         * generally used unless an SDK client needs to support multiple wallets.
         *
         * @param lightWalletEndpoint Server endpoint.  See [cash.z.ecc.android.sdk.model.defaultForNetwork]. If a
         * client wishes to change the server endpoint, the active synchronizer will need to be stopped and a new
         * instance created with a new value.
         *
         * @param birthday Block height representing the "birthday" of the wallet.  When creating a new wallet, see
         * [BlockHeight.ofLatestCheckpoint].  When restoring an existing wallet, use block height that was first used
         * to create the wallet.  If that value is unknown, null is acceptable but will result in longer
         * sync times.  After sync completes, the birthday can be determined from [Synchronizer.latestBirthdayHeight].
         *
         * @param setup An optional Account setup data that holds seed and other account related information.
         * See [AccountCreateSetup] for more.
         *
         * @param walletInitMode a required parameter with one of [WalletInitMode] values. Use
         * [WalletInitMode.NewWallet] when starting synchronizer for a newly created wallet. Or use
         * [WalletInitMode.RestoreWallet] when restoring an existing wallet that was created at some point in the
         * past. Or use the last [WalletInitMode.ExistingWallet] type for a wallet which is already initialized
         * and needs follow-up block synchronization.
         *
         * @throws InitializerException.SeedRequired Indicates clients need to call this method again, providing the
         * seed bytes.
         *
         * @throws IllegalStateException If multiple instances of synchronizer with the same network+alias are
         * active at the same time.  Call `close` to finish one synchronizer before starting another one with the same
         * network+alias.
         *
         * If customized initialization is required (e.g. for dependency injection or testing), see
         * [DefaultSynchronizerFactory].
         */
        @Suppress("LongParameterList", "LongMethod", "TooGenericExceptionCaught")
        suspend fun new(
            alias: String = ZcashSdk.DEFAULT_ALIAS,
            birthday: BlockHeight?,
            context: Context,
            lightWalletEndpoint: LightWalletEndpoint,
            setup: AccountCreateSetup?,
            walletInitMode: WalletInitMode,
            zcashNetwork: ZcashNetwork,
            isTorEnabled: Boolean,
            isExchangeRateEnabled: Boolean
        ): CloseableSynchronizer {
            val applicationContext = context.applicationContext

            val sdkFlags =
                SdkFlags(
                    isTorEnabled = isTorEnabled,
                    isExchangeRateEnabled = isExchangeRateEnabled
                )

            validateAlias(alias)

            val saplingParamTool = SaplingParamTool.new(applicationContext)

            val loadedCheckpoint =
                CheckpointTool.loadNearest(
                    context = applicationContext,
                    network = zcashNetwork,
                    birthdayHeight = birthday ?: zcashNetwork.saplingActivationHeight
                )

            val coordinator = DatabaseCoordinator.getInstance(context)
            // The pending transaction database no longer exists, so we can delete the file
            coordinator.deletePendingTransactionDatabase(zcashNetwork, alias)

            val backend =
                DefaultSynchronizerFactory.defaultBackend(
                    zcashNetwork,
                    alias,
                    saplingParamTool,
                    coordinator
                )

            val saplingParamFetcher = SaplingParamFetcher(saplingParamTool, backend)

            val blockStore =
                DefaultSynchronizerFactory
                    .defaultCompactBlockRepository(coordinator.fsBlockDbRoot(zcashNetwork, alias), backend)

            val torDir = Files.getTorDir(context)
            val torClient =
                if (sdkFlags.isTorEnabled || sdkFlags.isExchangeRateEnabled) {
                    try {
                        TorClient.new(torDir, backend.backend)
                    } catch (e: Exception) {
                        Twig.error(e) { "Error instantiating Tor Client" }
                        null
                    }
                } else {
                    null
                }

            val exchangeRateIsolatedTorClient =
                if (sdkFlags.isExchangeRateEnabled) {
                    try {
                        torClient?.isolatedTorClient()
                    } catch (e: Exception) {
                        Twig.error(e) { "Error instantiating an isolated Tor Client" }
                        null
                    }
                } else {
                    null
                }

            val walletClientFactory =
                WalletClientFactory(
                    context = applicationContext,
                    torClient = torClient.takeIf { sdkFlags.isTorEnabled }
                )

            val walletClient = walletClientFactory.create(endpoint = lightWalletEndpoint)
            val downloader = DefaultSynchronizerFactory.defaultDownloader(walletClient, blockStore)

            val chainTip =
                when (walletInitMode) {
                    is RestoreWallet -> {
                        when (
                            val response = downloader.getLatestBlockHeight(sdkFlags ifTor ServiceMode.UniqueTor)
                        ) {
                            is Response.Success -> {
                                Twig.info { "Chain tip for recovery until param fetched: ${response.result.value}" }
                                runCatching { response.result.toBlockHeight() }.getOrNull()
                            }

                            is Response.Failure -> {
                                Twig.error {
                                    "Chain tip fetch for recovery until failed with: ${response.toThrowable()}"
                                }
                                null
                            }
                        }
                    }

                    else -> {
                        null
                    }
                }

            val repository =
                DefaultSynchronizerFactory.defaultDerivedDataRepository(
                    context = applicationContext,
                    rustBackend = backend,
                    databaseFile = coordinator.dataDbFile(zcashNetwork, alias),
                    checkpoint = loadedCheckpoint,
                    recoverUntil = chainTip,
                    setup = setup,
                )

            val encoder = DefaultSynchronizerFactory.defaultEncoder(backend, saplingParamFetcher, repository)

            val txManager = DefaultSynchronizerFactory.defaultTxManager(encoder, walletClient, sdkFlags)
            val processor =
                DefaultSynchronizerFactory.defaultProcessor(
                    backend = backend,
                    birthdayHeight = birthday ?: zcashNetwork.saplingActivationHeight,
                    downloader = downloader,
                    repository = repository,
                    txManager = txManager,
                    sdkFlags = sdkFlags,
                    saplingParamFetcher = saplingParamFetcher
                )

            val standardPreferenceProvider = StandardPreferenceProvider(context)

            return SdkSynchronizer.new(
                context = context.applicationContext,
                zcashNetwork = zcashNetwork,
                alias = alias,
                repository = repository,
                txManager = txManager,
                processor = processor,
                backend = backend,
                fastestServerFetcher =
                    FastestServerFetcher(
                        backend = backend,
                        network = processor.network,
                        walletClientFactory = walletClientFactory,
                        sdkFlags = sdkFlags
                    ),
                fetchExchangeChangeUsd =
                    exchangeRateIsolatedTorClient?.let { UsdExchangeRateFetcher(isolatedTorClient = it) },
                preferenceProvider = standardPreferenceProvider(),
                torClient = torClient,
                walletClient = walletClient,
                walletClientFactory = walletClientFactory,
                sdkFlags = sdkFlags
            )
        }

        /**
         * Effectively the same as [new] although designed to be a blocking call with better
         * interoperability with Java clients.
         *
         * This is a blocking call, so it should not be called from the main thread.
         */
        @JvmStatic
        @Suppress("LongParameterList")
        fun newBlocking(
            alias: String = ZcashSdk.DEFAULT_ALIAS,
            birthday: BlockHeight?,
            context: Context,
            lightWalletEndpoint: LightWalletEndpoint,
            setup: AccountCreateSetup?,
            walletInitMode: WalletInitMode,
            zcashNetwork: ZcashNetwork,
            isTorEnabled: Boolean,
            isExchangeRateEnabled: Boolean
        ): CloseableSynchronizer =
            runBlocking {
                new(
                    alias = alias,
                    birthday = birthday,
                    context = context,
                    lightWalletEndpoint = lightWalletEndpoint,
                    setup = setup,
                    walletInitMode = walletInitMode,
                    zcashNetwork = zcashNetwork,
                    isTorEnabled = isTorEnabled,
                    isExchangeRateEnabled = isExchangeRateEnabled
                )
            }

        /**
         * Delete the databases associated with this wallet. This removes all compact blocks and
         * data derived from those blocks. Although most data can be regenerated by setting up a new
         * Synchronizer instance with the seed, there are two special cases where data is not retained:
         * 1. Outputs created with a `null` OVK
         * 2. The UA to which a transaction was sent (recovery from seed will only reveal the receiver, not the full UA)
         *
         * @param appContext the application context.
         * @param network the network associated with the data to be erased.
         * @param alias the alias used to create the local data.
         *
         * @return true when one of the associated files was found. False most likely indicates
         * that the wrong alias was provided.
         */
        suspend fun erase(
            appContext: Context,
            network: ZcashNetwork,
            alias: String = ZcashSdk.DEFAULT_ALIAS
        ): Boolean = SdkSynchronizer.erase(appContext, network, alias)
    }
}

/**
 * Sealed class describing wallet initialization mode.
 *
 * Use [NewWallet] type if the seed was just created as part of a
 * new wallet initialization.
 *
 * Use [RestoreWallet] type if an existed wallet is initialized
 * from a restored seed with older birthday height.
 *
 * Use [ExistingWallet] type if the wallet is already initialized.
 */
sealed class WalletInitMode {
    data object NewWallet : WalletInitMode()

    data object RestoreWallet : WalletInitMode()

    data object ExistingWallet : WalletInitMode()
}

interface CloseableSynchronizer :
    Synchronizer,
    Closeable

/**
 * Validate that the alias doesn't contain malicious characters by enforcing simple rules which
 * permit the alias to be used as part of a file name for the preferences and databases. This
 * enables multiple wallets to exist on one device, which is also helpful for sweeping funds.
 *
 * @param alias the alias to validate.
 *
 * @throws IllegalArgumentException whenever the alias is not less than 100 characters or
 * contains something other than alphanumeric characters. Underscores and hyphens are allowed.
 */
private fun validateAlias(alias: String) {
    require(
        alias.length in ZcashSdk.ALIAS_MIN_LENGTH..ZcashSdk.ALIAS_MAX_LENGTH &&
            alias.all { it.isLetterOrDigit() || it == '_' || it == '-' }
    ) {
        "ERROR: Invalid alias ($alias). For security, the alias must be shorter than 100 " +
            "characters and only contain letters, digits, hyphens, and underscores."
    }
}
