# Changelog
All notable changes to this library will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this library adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Changed

## [2.4.5] - 2025-02-16

### Changed
- Migrated to Rust 1.92.0.

## [2.4.4] - 2025-12-16

### Changed
- [SaplingParamTool.ensureParams] is now called as part of a sync loop

## [2.4.3] - 2025-12-02

### Changed
- Reduced the number of exchanges queried for ZEC/USD back to the number we had
  in 2.3.9 and earlier, to reduce power consumption.

## [2.4.2] - 2025-12-02

### Added
- `WalletCoordinator.resetSynchronizer` function added to kill current synchronizer and recreate a new one
- `Synchronizer.debugQuery` function added to request read-only database queries
- `Synchronizer.enhanceTransaction` function added to request an enhancement of specified transaction
- `TransactionId.new(String)` function added to create a new transaction ID from a string

### Changed
- `ResponseException` is now thrown instead of generic `Throwable` when `Response.Failure` occurs during networking
- `Synchronizer.getTorHttpClient` is now able to return a client if either tor or exchange rate has been enabled

## [2.4.1] - 2025-11-14

### Fixed
- `LightWalletClient.submitTransaction` now correctly handles errors if StatusRuntimeException is thrown

## [2.4.0] - 2025-11-05

### Added
- `Synchronizer.fetchUtxosByAddress` function added to query light wallet server to find any UTXOs associated with 
  given transparent address
- `Synchronizer.getSingleUseTransparentAddress` function added that returns an ephemeral transparent address for 
  one-time use
- `Synchronizer.checkSingleUseTransparentAddress` function added to check for most overdue ephemeral address within 
  24h window to retrieve and store it's UTXOs.

### Changed
- Migrated to Rust 1.90.0.

## [2.3.9] - 2025-10-23

### Changed
- Updated to `zcash_client_sqlite-0.18.9` to fix problems in transparent UTXO
  selection for shielding, including incorrect handling of outputs received at
  ephemeral addresses and selection of dust transparent outputs for shielding.

## [2.3.8] - 2025-10-20

### Fixed
- A state when transaction is not found in mempool or main chain is now correctly handled

### Changed
- Updated to `zcash_client_sqlite-0.18.7` to improve consistency of spentness
  determination, reliability of transaction status request generation,
  and fix removal of already-fulfilled transaction enhancement requests.

## [2.3.7] - 2025-10-09

### Fixed
- Updated to `zcash_client_sqlite-0.18.4` to fix a problem with balance calculation
  related to detection of spends of outputs received by the wallet's ephemeral
  addresses.

## [2.3.6] - 2025-10-02

### Fixed
- Updated librustzcash crates to released version

## [2.3.5] - 2025-10-01

### Fixed
- Fully transparent transactions that are added to the wallet as a consequence of mempool scanning are subsequently
checked to determine when they are mined into a block.

## [2.3.4] - 2025-09-29

### Added
- `RustBackend.decryptAndStoreTransaction` now returns transaction ID
- Mempool is now being observed in order to store the transactions locally

### Fixed
- Filtering transactions by memo now returns more than one item

## [2.3.3] - 2025-09-15

### Fixed
- Transactions received on a transparent address should now appear in history views when they are detected before any
  compact blocks have been scanned.
- Column expired_unmined in v_transactions is now propagated to transaction state as expired if non-null and true

## [2.3.2] - 2025-08-22

### Added
- `Synchronizer.getTorHttpClient` function added which now returns Ktors' `HttpClient` which does http requests over
  Tor Network

## [2.3.1] - 2025-08-05

### Changed
- Exchange rate calculation is now decoupled from Tor flag
- [WalletCoordinator] now takes [isExchangeRateEnabled] as a constructor parameter
  - When set to `true`, exchange rate fetching will be enabled
  - When set to `false` or `null`, exchange rate fetching will be disabled

## [2.3.0] - 2025-07-28

### Added
- [WalletCoordinator] now takes [isTorEnabled] as a constructor parameter.
  - When set to `true`, lightwalletd RPC queries will be made over Tor (where possible and beneficial).
  - When set to `false` or `null`, lightwalletd RPC queries will always be made directly to the server.
- [Synchronizer] now exposes [initializationError] property containing synchronizer errors that happened during
  synchronizer init

### Fixed
- Tor client is now optional in case it's instantiation fails to prevent SDK

## [2.2.15] - 2025-06-26

### Fixed
- Tor client is now optional in case it's instantiation fails to prevent SDK from crashing

## [2.2.14] - 2025-06-16

## Fixed
- FFI 0.17.0 introduces retry logic for Tor, significantly improving the reliability of currency conversion fetches.

### Changed
- Added a `ServiceOption` parameter for functions `WalletClient.getServerInfo`, `WalletClient.getLatestBlockHeight`,
  `WalletClient.fetchTransaction`, `WalletClient.submitTransaction` and `WalletClient.getTreeState` to add the
  option to execute over Tor. Custom lightwalletd servers over VPNs like Tailscape might stop working when using Tor.
- `Synchronizer.getFastestServers` function signature changed and does not require `Context` parameter anymore

## [2.2.13] - 2025-05-16

### Added
- `Synchronizer.getCustomUnifiedAddress` allows the caller to obtain a newly-generated
  unified address with user-specified `UnifiedAddressRequest` of type `P2PKH`, `Sapling` and `Orchard` supporting
  the ability to combine these using an infix `and` function.

## [2.2.12] - 2025-04-28

### Added
- `Synchronizer.areFundsSpendable` that indicates whether are the shielded wallet balances spendable or not during
  the block synchronization process.
- `SdkSynchronizer.estimateBirthdayHeight(date: Date)` has been added to get an estimated height for a given date,
  typically used for estimating birthday.

### Changed
- The base sapling params download URL has been changed to `https://download.z.cash/downloads/`
- Checkpoints update

### Fixed
- As part of the sapling params download URL change, the extra `/` character has been removed from the result path

## [2.2.11] - 2025-04-04

### Fixed
- Database migration bugs in `zcash_client_sqlite 0.16.0` and `0.16.1` have
  been fixed by updating to `zcash_client_sqlite 0.16.2`. These caused a few
  wallets to stop working after the 2.2.9 upgrade due to failed database
  migrations.

## [2.2.9] - 2025-03-25

### Fixed
- The note commitment tree bug has been resolved using a new internal `Backend.fixWitnesses()` API

### Changed
- Dependency update:
  - Gradle 8.13
  - Android Gradle Plugin 8.9.0
  - Kotlin 2.1.10
  - Bip39 1.0.9
  - Other dependencies update
- Migrated to `zcash_client_backend 0.18.0`, `zcash_client_sqlite 0.16.0`
- Added support for gap-limit-based discovery of transparent wallet addresses.
- The internal `fetch-utxos` logic is now triggered only in every `init` and `complete` block sync phases, and it
  fetches UTXOs from height 0 to support the Ledger funds rescue requirement.
- Checkpoints update

## [2.2.8] - 2025-03-03

### Added
- `AccountMetadataKey`
- `DerivationTool.deriveAccountMetadataKey`
- `DerivationTool.derivePrivateUseMetadataKey`
- `Synchronizer.getTransactionsByMemoSubstring()` has been added
- `Synchronizer.redactPcztForSigner`
- `Synchronizer.pcztRequiresSaplingProofs`
- `TransactionId` object has been added and used instead of `FirstClassByteArray` in `TransactionOverview` and
  `PendingTransaction` model classes
- `TransactionOverview.totalSpent` and `TransactionOverview.totalReceived` properties added to provide more
  information about shielding transaction

### Changed
- Migrated to Rust 1.84.1.
- `Synchronizer.getTransactions(accountUuid)` and `Synchronizer.transactions` now internally fill in
  `TransactionOverview.blockTimeEpochSeconds` based on the related block time
- `Synchronizer.transactions` has been renamed to `Synchronizer.allTransactions` to emphasize the fact the API
  returns transactions for all the wallet accounts
- `Synchronizer.getRecipients` now returns both address and an account existing in database

## [2.2.7] - 2024-12-18

### Added
- `Synchronizer.importAccountByUfvk()` has been added
- `Synchronizer.getAccounts()` returning all the created or imported accounts. See the documentation in `Account`.
- `Synchronizer.walletBalances: StateFlow<Map<AccountUuid, AccountBalance>?>` that is replacement for the removed
  `orchardBalances`, `saplingBalances`, and `transparentBalance`
- `getTransactions(accountUuid: AccountUuid)` to get transactions belonging to the given account
- `Synchronizer.createPcztFromProposal`
- `Synchronizer.addProofsToPczt`
- `Synchronizer.createTransactionFromPczt`
- `Zip32AccountIndex`, `AccountUuid`, `AccountUsk`, `AccountPurpose`, `AccountCreateSetup`, `AcountImportSetup`, and
  `Pczt` model classes have been added to support the new or the changed APIs

### Changed
- `Account` data class works with `accountUuid: AccountUuid` instead of the previous ZIP 32 account index
- These functions from `DerivationTool` have been refactored to work with the new `Zip32AccountIndex` instead of the
  `Account` data class: `deriveUnifiedSpendingKey`, `deriveUnifiedAddress`, `deriveArbitraryAccountKey`
- `WalletCoordinator` now provides a way to instantiate `Synchronizer` with the new `accountName` and `keySource`
  parameters
- `UnifiedSpendingKey` does not hold `Account` information anymore, it has been replaced by `AccountUsk` model class
  in a few internal cases
- `Synchronizer.send` extension function receives `Account` on input
- `PendingTransaction` sealed class descendants have been renamed
- `RustLayerException.GetCurrentAddressException` has been renamed to `RustLayerException.GetAddressException`
- Checkpoints update

### Removed
- `Synchronizer.sendToAddress` and `Synchronizer.shieldFunds` have been removed, use
  `Synchronizer.createProposedTransactions` and `Synchronizer.proposeShielding` instead
- `Synchronizer.orchardBalances`, `Synchronizer.saplingBalances`, and `Synchronizer.transparentBalance`
  (use `Synchronizer.walletBalances` instead).

### Fixed
- The `CompactBlockProcessor` now correctly distinguishes between `Response.Failure.Server.Unavailable` and other
  errors in its `refreshUtxos` API. It then sets its state to `State.Disconnected` in such a case.

## [2.2.6] - 2024-11-16

### Added
- `DerivationTool.deriveArbitraryWalletKey`
- `DerivationTool.deriveArbitraryAccountKey`
- `Synchronizer.getTransactionOutputs` API has been added. It enables to fetch all transaction outputs from database.

## [2.2.5] - 2024-10-22

### Added
- The new `Synchronizer.proposeFulfillingPaymentUri` API has been added. It enables constructing Proposal object from
  the given ZIP-321 Uri, and then creating transactions from it.

### Changed
- Migrated to Rust 1.82.0.
- `Synchronizer.rewindToNearestHeight` now returns the block height that was
  actually rewound to, or `null` if no rewind was performed.
- `Synchronizer.proposeTransfer` throws `TransactionEncoderException.ProposalFromParametersException`
- `Synchronizer.proposeShielding` throws `TransactionEncoderException.ProposalShieldingException`
- `Synchronizer.createProposedTransactions` throws `TransactionEncoderException.TransactionNotCreatedException` and `TransactionEncoderException.TransactionNotFoundException`
- `LightWalletClient` now implements `Closeable` and is thus correctly cleaned up in `SdkSynchronizer` and
  `FastestServerFetcher` after it's used
- Checkpoints update

### Fixed
- `FailedSynchronizationException` reported using `Synchronizer.onProcessorErrorHandler` now contains the full
  stacktrace history

### Removed
- `Synchronizer.getNearestRewindHeight` (its function is now handled internally
  by `Synchronizer.rewindToNearestHeight`).
- `Synchronizer.quickRewind` and `CompactBlockProcessor.quickRewind` have been removed as they triggered the block
  rewind action at an invalid height. Use `Synchronizer.rewindToNearestHeight` instead.

## [2.2.4] - 2024-09-16

### Added
- `TransactionOverview.isShielding` has been added to indicate the shielding transaction type

### Changed
- NDK version has been updated to `27.0.12077973`
- Android `compileSdkVersion` and `targetSdkVersion` has been updated to 35
- `CompackBlockProcessor.calculatePollInterval` now uses a randomized poll interval to avoid exposing computation time

### Fixed
- Android 15 (SDK level 35) support added for 16 KB memory page size
- The broken disposing logic `TorClient.freeTorRuntime` for Android SDK API level 27 has been fixed

## [2.2.3] - 2024-09-09

### Changed
- Several functions have been updated to accept `cash.z.ecc.android.sdk.model.Locale` instead of
  `cash.z.ecc.android.sdk.model.MonetarySeparators` as an argument. MonetarySeparators are derived from Locale now.
- `FiatCurrencyConversion.toZatoshi`
- `Zatoshi.toFiatCurrencyState`
- `Zatoshi.toFiatString`
- `BigDecimal.convertFiatDecimalToFiatString`
- `Zatoshi.Companion.fromZecString`

### Added
- `Double?.convertUsdToZec` has been added as we are moving away from `BigDecimal` in favor of primitive types
- `Locale.getDefault()` has been added
- Transaction resubmission feature has been added to the CompactBlockProcessor's regular actions. This new action
  periodically checks unmined sent transactions that are still within their expiry window and resubmits them if
  there are any.

### Fixed
- Fastest Server calculation changed for estimated height

## [2.2.2] - 2024-09-03

### Fixed
- Migrated to `zcash_client_sqlite 0.11.2` to remove use of a database feature
  that prevented use of Zashi on older devices.

### Changed
- Checkpoints update

## [2.2.1] - 2024-08-22

### Fixed
- A database migration misconfiguration that could result in problems with wallet
  initialization was fixed.

## [2.2.0] - 2024-08-22

This release adds several important new features:
- Currency exchange rates (currently just USD/ZEC) are now made available via the SDK.
  The exchange rate computed as the median of values provided by at least three separate
  cryptocurrency exchanges, and is fetched over Tor connections in order to avoid leaking
  the wallet's IP address to the exchanges.
- Sending to ZIP 320 (TEX) addresses is now supported. When sending to a ZIP 320 address,
  the wallet will first automatically de-shield the required funds to a fresh ephemeral
  transparent address, and then will make a second fully-transparent transaction sending
  the funds to the eventual recipient that is not linkable via on-chain information to any
  other transaction in the  user's wallet.
- As part of adding ZIP 320 support, the SDK now also provides full support for recovering
  transparent transaction history. Prior to this release, only transactions belonging to the
  wallet that contained either some shielded component OR a member of the current
  transparent UTXO set were included in transaction history.

### Changed
- Migrated to Rust 1.80.0.
- `Synchronizer.proposeTransfer` now supports TEX addresses (ZIP 320).
- Internal transactions-enhancing logic has changed to support the history of transactions made to TEX addresses

### Added
- `Synchronizer.isValidTexAddr` which checks whether the given address is a valid ZIP 320 TEX address
- `Synchronizer.exchangeRateUsd` is a `StateFlow` containing the latest USD/ZEC
  exchange rate, along with the `Instant` it was fetched. It can be initialized
  and refreshed by calling `Synchronizer.refreshExchangeRateUsd()`.
- `ZatoshiExt.toFiatString` is now a public function
- `Synchronizer.getFastestServers([LightWalletEndpoint])` is a flow that measures connections to given endpoints and
  returns the three fastest ones
- `Synchronizer.getTAddressTransactions` returns all the transactions for a given t-address over the given range

### Changed
- Checkpoints update

## [2.1.3] - 2024-08-08

### Changed
- The fetch UTXOs action is now hooked up at the beginning of every scanning phase of the block synchronization logic
  instead of being called every 1000 blocks together with shielded transactions enhancing. It uses
  `fullyScannedHeight` as its lower bound.
- The fetch UTXOs action reports `FetchUtxosException` to the wrapping `onProcessorErrorHandler` or
  `onCriticalErrorHandler` in case any error occurs
- The internal `CompactBlockProcessor.SYNC_BATCH_SIZE` has changed. Block synchronization logic now works above
  batch of blocks with size 1000 blocks instead of just 100 blocks, except the Zcash sandblasting period in which
  batch size of 100 blocks is still used.
- The internal `FileCompactBlockRepository.BLOCKS_METADATA_BUFFER_SIZE` constant has been raised from 10 to 1000 to
  match the block synchronization batch size.
- The overall speed-up of the entire block synchronization logic, thanks to the both mentioned synchronization
  improvements above is about 50% out of the Zcash sandblasting period. There is still some improvement in the
  sandblasting period.
- Checkpoints update

### Fixed
- `Synchronizer.refreshUtxos(account: Account, since: BlockHeight)` now correctly uses the `since` parameter in the
  underlying logic and fetches UTXOs from that height

## [2.1.2] - 2024-07-16

### Added
- `SdkSynchronizer.closeFlow()` is a Flow-providing version of `Synchronizer.close()`. It safely closes the
  Synchronizer together with the related components.
- `WalletCoordinator.deleteSdkDataFlow` is a Flow-providing function that deletes all the persisted data in the SDK
  (databases associated with the wallet, all compact blocks, and data derived from those blocks) but preserves the
  wallet secrets.

### Changed
- The Android SDK target API level has been updated to version 34
- `ZecString` and `Zatoshi` APIs now handle `MonetarySeparators` with the same grouping and decimal characters
- Checkpoints update

### Fixed
- `MonetarySeparators` API does not signal an unsupported state to clients if used on a device with Locale with the
 same decimal and grouping separators. Instead, it will just omit the grouping separator.

## [2.1.1] - 2024-04-23

### Changed
- The SDK components no longer contain logging statements in the release build
- `safelyConvertToBigDecimal()` API from `CurrencyFormatter.kt` now expects decimal separator Char on input
- Gradle 8.7
- Android Gradle Plugin 8.3.0
- Kotlin 1.9.23
- Other dependencies update
- Checkpoints update

## [2.1.0] - 2024-04-09

### Added
- The Orchard support has been finished, and the SDK now fully supports sending and receiving funds on the Orchard
  addresses

### Fixed
- SDK release 1.11.0-beta01 documented that `Synchronizer.new` would throw an
  exception indicating that an internal migration requires the wallet seed, if
  called with `null`. This has been unintentionally broken the entire time: the
  handling logic for this case was accidentally removed shortly after it was
  added. The SDK now correctly throws `InitializeException.SeedRequired`.

### Changed
- `Synchronizer.refreshAllBalances` now refreshes the Orchard balances as well
- The SDK uses ZIP-317 fee system internally
- `ZcashSdk.MINERS_FEE` has been deprecated, and will be removed in 2.1.x
- `ZecSend` data class now provides `Proposal?` object initiated using `Synchronizer.proposeTransfer`
- Wallet initialization using `Synchronizer.new` now could throw a new `SeedNotRelevant` exception when the provided
  seed is not relevant to any of the derived accounts in the wallet database
- Checkpoints update

## [2.0.7] - 2024-03-08

### Fixed
- `Synchronizer.sendToAddress` and `Synchronizer.shieldFunds` now throw an
  exception if the created transaction successfully reaches `lightwalletd` but
  fails to reach its backing full node's mempool.

### Changed
- `WalletBalance` now contains new fields `changePending` and `valuePending`. Fields `total` and `pending` are
  still provided. See more in the class documentation
  `sdk-lib/src/main/java/cash/z/ecc/android/sdk/model/WalletBalance.kt`
- `Synchronizer.transparentBalances: WalletBalance` to `Synchronizer.transparentBalance: Zatoshi`
- `WalletSnapshot.transparentBalance: WalletBalance` to `WalletSnapshot.transparentBalance: Zatoshi`
- `Memo.MAX_MEMO_LENGTH_BYTES` is now available in public API
- `Synchronizer.sendToAddress` and `Synchronizer.shieldFunds` have been
  deprecated, and will be removed in 2.1.x (which will create multiple
  transactions at once for some recipients).

### Added
- APIs that enable constructing a proposal for transferring or shielding funds,
  and then creating transactions from a proposal. The intermediate proposal can
  be used to determine the required fee, before committing to producing
  transactions.
  - `Synchronizer.proposeTransfer`
  - `Synchronizer.proposeShielding`
  - `Synchronizer.createProposedTransactions`
- `WalletBalanceFixture` class with mock values that are supposed to be used only for testing purposes
- `Memo.countLength(memoString: String)` to count memo length in bytes
- `PersistableWallet.toSafeString` is a safe alternative for the regular [toString] function that prints only
  non-sensitive parts
- `Synchronizer.validateServerEndpoint` this function checks whether the provided server endpoint is valid.
  The validation is based on comparing:
  * network type
  * sapling activation height
  * consensus branch id

## [2.0.6] - 2024-01-30

### Fixed
- In 2.0.5, `Synchronizer.shieldFunds` always returned an error due to a crash
  on the Rust side. This release fixes the underlying bug.

## [2.0.5] - 2024-01-30

### Added
- `cash.z.ecc.android.sdk.model.Proposal` (currently unused in the public API).
- System tracing to `CompactBlockProcessor` and the Rust backend.

### Changed
- Migrated to NDK 26.1.10909125 and Rust 1.75.0.
- The wallet balances are now updated immediately upon synchronizer start.
- Existing wallets will now only fetch the most recent subtree roots, improving
  synchronizer startup times.
- Performance of block scanning and `SdkSynchronizer.refreshAllBalances` has
  been improved.
- `WalletAddressFixture` fixture properties have been updated

### Fixed
- The transparent wallet balance `StateFlow` now shows the total transparent
  balance in the wallet, instead of the balance of the default address. It also
  now treats all zero-conf balance as available.

### Removed
- `SdkSynchronizer.refreshSaplingBalance` and
  `SdkSynchronizer.refreshTransparentBalance`
  (use `SdkSynchronizer.refreshAllBalances` instead).

## [2.0.4] - 2024-01-08

### Added
- `TransactionOverview.txIdString()` to provide a readable transaction ID to SDK-consuming apps
- `MonetarySeparators.current(locale: Locale? = null)` now accepts `Locale` on input to force separators locale. If
  no value is provided, the default one is used.

### Removed
- `LightWalletEndpointExt` and its functions and variables were removed from the SDK's public APIs entirely. It's
  preserved only for testing and wallet Demo app purposes. The calling wallet app should provide its own
  `LightWalletEndpoint` instance within `PersistableWallet` or `SdkSynchronizer` APIs.

### Changed
- Gradle 8.5
- Kotlin 1.9.21
- Other dependency update
- Checkpoints update

### Removed
- Several internally unused exceptions from `Exceptions.kt`

## [2.0.3] - 2023-11-08

### Added
- `Synchronizer.getExistingDataDbFilePath` public API to check and provide file path to the existing data database
  file or throws [InitializeException.MissingDatabaseException] if the database doesn't exist yet. See #1292.

### Changed
- `CompactBlockProcessor` switched internally from balance and progress FFIs to wallet summary FFI APIs. This change
  brings a block synchronization speed up. No action is required on the client side. See #1282.
- Checkpoints update

## [2.0.2] - 2023-10-20

### Fixed
- Incorrect note deduplication in the `v_transactions` database view: This is a fix in the Rust layer. The amount
  sent in the transaction was incorrectly reported even though the actual amount was correctly sent. Now, clients
  should see the amount they expect to see.

### Changed
- Checkpoints update

## [2.0.1] - 2023-10-02

### Changed
- `PersistableWallet` API provides a new `endpoint` parameter of type `LightWalletEndpoint`, which could be used for
  the Lightwalletd server customization. The new parameter is part of PersistableWallet persistence. The SDK handles
  the persistence migration internally.
- The **1_000** Zatoshi fee proposed in ZIP-313 is deprecated now, so the minimum is **10_000** Zatoshi, defined in
  ZIP-317—the `ZcashSdk.MINERS_FEE` now returns the correct value as described above. Note that the actual fee is
  handled in a rust layer.
- Adopted the latest Bip39 library v1.0.6

## [2.0.0] - 2023-09-25

## [2.0.0-rc.4] - 2023-09-22

### Fixed
Transparent balance is now correctly updated after a shielding transaction is
created, instead of only once the transaction is mined.

## [2.0.0-rc.3] - 2023-09-21

### Fixed
The Kotlin layer of the SDK now correctly matches the Rust layer `PrevHashMismatch` exception with `ContinuityError`
and triggers rewind action.

## [2.0.0-rc.2] - 2023-09-20

### Changed
- Some of the `TransactionOverview` class parameters changed:
  - `id` was removed
  - `index` is nullable
  - `feePaid` is nullable
  - `blockTimeEpochSeconds` is nullable

### Removed
- Block heights are absolute, not relative. Thus, these two operations above the `BlockHeight` object were removed:
  - `plus(other: BlockHeight): BlockHeight`
  - `minus(other: BlockHeight): BlockHeight`

## [2.0.0-rc.1] - 2023-09-12

### Notable Changes

- `CompactBlockProcessor` now processes compact blocks from the lightwalletd
  server using the **Spend-before-Sync** algorithm, which allows scanning of
  wallet blocks to be performed in arbitrary order and optimized to make it
  possible to spend received notes without waiting for synchronization to be
  complete. This feature shortens the time until a wallet's spendable balance
  can be used.
- The block synchronization mechanism is additionally about one-third faster
  thanks to an optimized `CompactBlockProcessor.SYNC_BATCH_SIZE` (issue **#1206**).

### Removed
- `CompactBlockProcessor.ProcessorInfo.lastSyncHeight` no longer had a
  well-defined meaning after implementation of the **SpendBeforeSync**
  synchronization algorithm and has been removed.
  `CompactBlockProcessor.ProcessorInfo.overallSyncRange` provides related
  information.
- `CompactBlockProcessor.ProcessorInfo.isSyncing`. Use `Synchronizer.status` instead.
- `CompactBlockProcessor.ProcessorInfo.syncProgress`. Use `Synchronizer.progress` instead.
- `alsoClearBlockCache` parameter from rewind functions of `Synchronizer` and
  `CompactBlockProcessor`, as it has no effect on the current behaviour of
  these functions.
- Internally, we removed access to the shared block table from the Kotlin
  layer, which resulted in eliminating these APIs:
  - `SdkSynchronizer.findBlockHash()`
  - `SdkSynchronizer.findBlockHashAsHex()`

### Changed
- `CompactBlockProcessor.quickRewind()` and `CompactBlockProcessor.rewindToNearestHeight()`
  now might fail due to internal changes in getting scanned height. Thus, these
  functions now return `Boolean` results.
- `Synchronizer.new()` and `PersistableWallet` APIs require a new
  `walletInitMode` parameter of type `WalletInitMode`, which describes wallet
  initialization mode. See related function and sealed class documentation.

### Fixed
- `Synchronizer.getMemos()` now correctly returns a flow of strings for sent
  and received transactions. Issue **#1154**.
- `CompactBlockProcessor` now triggers transaction polling while block
  synchronization is in progress as expected. Clients will be notified shortly
  after every new transaction is discovered via `Synchronizer.transactions`
  API. Issue **#1170**.

## [1.21.0-beta01]

Note: This is the last _1.x_ version release. The upcoming version _2.0_ brings the **Spend-before-Sync** feature,
which speeds up discovering the wallet's spendable balance.

### Changed
- Updated dependencies:
   - Gradle 8.3
   - AGP 8.1.1
   - Kotlin 1.9.10
   - Coroutines 1.7.3
   - Compose
   - AndroidX
   - gRPC/Protobuf
   - etc.
- Checkpoints

## 1.20.0-beta01
- The SDK internally migrated from `BackendExt` rust backend extension functions to more type-safe `TypesafeBackend`.
- `Synchronizer.getMemos()` now internally handles expected `RuntimeException` from the rust layer and transforms it
  in an empty string.

## 1.19.0-beta01
### Changed
- Adopted the latest Bip39 version 1.0.5

### Fixed
- `TransactionOverview` object returned with `SdkSynchronizer.transactions` now contains a correct `TransactionState.
  Pending` in case of the transaction is mined,but not fully confirmed.
- When the SDK internally works with a recently created transaction there was a moment in which could the transaction
  causes the SDK to crash, because of its invalid mined height. Fixed now.

## 1.18.0-beta01
- Synchronizer's functions `getUnifiedAddress`, `getSaplingAddress`, `getTransparentAddress`, and `refreshUtxos` now
  do not provide `Account.DEFAULT` value for the account argument. As accounts are not fully supported by the SDK
  yet, the caller should explicitly set Account.DEFAULT as the account argument to keep the same behavior.
- Gradle 8.1.1
- AGP 8.0.2

## 1.17.0-beta01
- Transparent fund balances are now displayed almost immediately
- Synchronization of shielded balances and transaction history is about 30% faster
- Disk space usage is reduced by about 90%
- `Synchronizer.status` has been simplified by combining `DOWNLOADING`, `VALIDATING`, and `SCANNING` states into a single `SYNCING` state.
- `Synchronizer.progress` now returns `Flow<PercentDecimal>` instead of `Flow<Int>`. PercentDecimal is a type-safe model. Use `PercentDecimal.toPercentage()` to get a number within 0-100% scale.
- `Synchronizer.clearedTransactions` has been renamed to `Synchronizer.transactions` and includes sent, received, and pending transactions.  Synchronizer APIs for listing sent, received, and pending transactions have been removed.  Clients can determine whether a transaction is sent, received, or pending by filtering the `TransactionOverview` objects returned by `Synchronizer.transactions`
- `Synchronizer.send()` and `shieldFunds()` are now `suspend` functions with `Long` return values representing the ID of the newly created transaction.  Errors are reported by thrown exceptions.
 - `DerivationTool` is now an interface, rather than an `object`, which makes it easier to inject alternative implementations into tests.  To adapt to the new API, replace calls to `DerivationTool.methodName()` with `DerivationTool.getInstance().methodName()`.
 - `DerivationTool` methods are no longer suspending, which should make it easier to call them in various situations.  Obtaining a `DerivationTool` instance via `DerivationTool.getInstance()` frontloads the need for a suspending call.
 - `DerivationTool.deriveUnifiedFullViewingKeys()` no longer has a default argument for `numberOfAccounts`.  Clients should now pass `DerivationTool.DEFAULT_NUMBER_OF_ACCOUNTS` as the value. Note that the SDK does not currently have proper support for multiple accounts.
 - The SDK's internals for connecting with librustzcash have been refactored to a separate Gradle module `backend-lib` (and therefore a separate artifact) which is a transitive dependency of the Zcash Android SDK.  SDK consumers that use Gradle dependency locks may notice this difference, but otherwise it should be mostly an invisible change.

## 1.16.0-beta01
(This version was only deployed as a snapshot and not released on Maven Central)
### Changed
 - The minimum supported version of Android is now API level 27.

## 1.15.0-beta01
### Changed
- A new package `sdk-incubator-lib` is now available as a public API.  This package contains experimental APIs that may be promoted to the SDK in the future.  The APIs in this package are not guaranteed to be stable, and may change at any time.
- `Synchronizer.refreshUtxos` now takes `Account` type as first parameter instead of transparent address of type
    `String`, and thus it downloads all UTXOs for the given account addresses. The Account object provides a default `0` index Account with `Account.DEFAULT`.

## 1.14.0-beta01
### Changed
 - The minimum supported version of Android is now API level 24.

## 1.13.0-beta01
### Changed
- The SDK's internal networking has been refactored to a separate Gradle module `lightwallet-client-lib` (and
  therefore a separate artifact) which is a transitive dependency of the Zcash Android SDK.
    - The `z.cash.ecc.android.sdk.model.LightWalletEndpoint` class has been moved to `co.electriccoin.lightwallet.client.model.LightWalletEndpoint`
    - The new networking module now provides a `LightWalletClient` for asynchronous calls.
    - Most unary calls respond with the new `Response` class and its subclasses. Streaming calls will be updated
      with the Response class later.
    - SDK clients should avoid using generated GRPC objects, as these are an internal implementation detail and are in process of being removed from the public API.  Any clients using GRPC objects will find these have been repackaged from `cash.z.wallet.sdk.rpc` to `cash.z.wallet.sdk.internal.rpc` to signal they are not a public API.

## 1.12.0-beta01
### Changed
 - `TransactionOverview`, `Transaction.Sent`, and `Transaction.Received` have `minedHeight` as a nullable field now.  This fixes a potential crash when fetching transactions when a transaction is in the mempool

## 1.11.0-beta01
### Added
- `cash.z.ecc.android.sdk`:
  - `Synchronizer.getUnifiedAddress`
  - `Synchronizer.getSaplingAddress`
  - `Synchronizer.isValidUnifiedAddr`
  - `Synchronizer.getMemos(TransactionOverview)`
  - `Synchronizer.getReceipients(TransactionOverview)`
- `cash.z.ecc.android.sdk.model`:
  - `Account`
  - `FirstClassByteArray`
  - `PendingTransaction`
  - `Transaction`
  - `UnifiedSpendingKey`
- `cash.z.ecc.android.sdk.tool`:
  - `DerivationTool.deriveUnifiedSpendingKey`
  - `DerivationTool.deriveUnifiedFullViewingKey`
  - `DerivationTool.deriveTransparentAccountPrivateKey`
  - `DerivationTool.deriveTransparentAddressFromAccountPrivateKey`
  - `DerivationTool.deriveUnifiedAddress`
  - `DerivationTool.deriveUnifiedFullViewingKeys`
  - `DerivationTool.validateUnifiedFullViewingKey`
    - Still unimplemented.
- `cash.z.ecc.android.sdk.type`:
  - `AddressType.Unified`
  - `UnifiedFullViewingKey`, representing a Unified Full Viewing Key as specified in
    [ZIP 316](https://zips.z.cash/zip-0316#encoding-of-unified-full-incoming-viewing-keys).

### Changed
- The following methods now take or return `UnifiedFullViewingKey` instead of
  `UnifiedViewingKey`:
    - `cash.z.ecc.android.sdk`:
      - `Initializer.Config.addViewingKey`
      - `Initializer.Config.importWallet`
      - `Initializer.Config.newWallet`
      - `Initializer.Config.setViewingKeys`
- `cash.z.ecc.android.sdk`:
  - `Synchronizer.Companion.new` now takes many of the arguments previously passed to `Initializer`. In addition, an optional `seed` argument is required for first-time initialization or if `Synchronizer.new` throws an exception indicating that an internal migration requires the wallet seed.  (This second case will be true the first time existing clients upgrade to this new version of the SDK).
  - `Synchronizer.new()` now returns an instance that implements the `Closeable` interface.  `Synchronizer.stop()` is effectively renamed to `Synchronizer.close()`
  - `Synchronizer` ensures that multiple instances cannot be running concurrently with the same network and alias
  - `Synchronizer.sendToAddress` now takes a `UnifiedSpendingKey` instead of an encoded
    Sapling extended spending key, and the `fromAccountIndex` argument is now implicit in
    the `UnifiedSpendingKey`.
  - `Synchronizer.shieldFunds` now takes a `UnifiedSpendingKey` instead of separately
    encoded Sapling and transparent keys.
  - `Synchronizer` methods that previously took an `Int` for account index now take an `Account` object
  - `Synchronizer.sendToAddress()` and `Synchronizer.shieldFunds()` return flows that can now be collected multiple times.  Prior versions of the SDK had a bug that could submit transactions multiple times if the flow was collected more than once.
- Updated dependencies:
  - Kotlin 1.7.21
  - AndroidX
  - etc.
- Updated checkpoints

### Removed
- `cash.z.ecc.android.sdk`:
  - `Initializer` (use `Synchronizer.new` instead)
  - `Synchronizer.start()` - Synchronizer is now started automatically when constructing a new instance.
  - `Synchronizer.getAddress` (use `Synchronizer.getUnifiedAddress` instead).
  - `Synchronizer.getShieldedAddress` (use `Synchronizer.getSaplingAddress` instead)
  - `Synchronizer.cancel`
  - `Synchronizer.cancelSpend`
- `cash.z.ecc.android.sdk.type.UnifiedViewingKey`
  - This type had a bug where the `extpub` field actually was storing a plain transparent
    public key, and not the extended public key as intended. This made it incompatible
    with ZIP 316.
- `cash.z.ecc.android.sdk.tool`:
  - `DerivationTool.deriveSpendingKeys` (use `DerivationTool.deriveUnifiedSpendingKey` instead)
  - `DerivationTool.deriveViewingKey` (use `DerivationTool.deriveUnifiedFullViewingKey` instead)
  - `DerivationTool.deriveTransparentAddress` (use `Synchronizer.getLegacyTransparentAddress` instead).
  - `DerivationTool.deriveTransparentAddressFromPrivateKey` (use `Synchronizer.getLegacyTransparentAddress` instead).
  - `DerivationTool.deriveTransparentAddressFromPublicKey` (use `Synchronizer.getLegacyTransparentAddress` instead).
  - `DerivationTool.deriveTransparentSecretKey` (use `DerivationTool.deriveUnifiedSpendingKey` instead).
  - `DerivationTool.deriveShieldedAddress`
  - `DerivationTool.deriveUnifiedViewingKeys` (use `DerivationTool.deriveUnifiedFullViewingKey` instead)
  - `DerivationTool.validateUnifiedViewingKey`

## Version 1.9.0-beta05
- The minimum version of Android supported is now API 21
- Fixed R8/ProGuard consumer rule, which eliminates a runtime crash for minified apps

## Version 1.9.0-beta04
- The SDK now stores sapling param files in `no_backup/co.electricoin.zcash` folder instead of the `cache/params`
  folder. Besides that, `SaplingParamTool` also does validation of downloaded sapling param file hash and size.
**No action required from client app**.

## Version 1.9.0-beta03
- No changes; this release is a test of a new deployment process

## Version 1.9.0-beta02
- The SDK now stores database files in `no_backup/co.electricoin.zcash` folder instead of the `database` folder. **No action required from client app**.

## Version 1.9.0-beta01
 - Split `ZcashNetwork` into `ZcashNetwork` and `LightWalletEndpoint` to decouple network and server configuration
 - Gradle 7.5.1
 - Updated checkpoints

## Version 1.8.0-beta01
- Enabled automated unit tests run on the CI server
- Added `BlockHeight` typesafe object to represent block heights
- Significantly reduced memory usage, fixing potential OutOfMemoryError during block download
- Kotlin 1.7.10
- Updated checkpoints

## Version 1.7.0-beta01
- Added `Zatoshi` typesafe object to represent amounts.
- Kotlin 1.7.0

## Version 1.6.0-beta01
- Updated checkpoints for Mainnet and Testnet
- Fix: SDK can now be used on Intel x86_64 emulators
- Prevent R8 warnings for apps consuming the SDK

## Version 1.5.0-beta01
- New: Transactions can be created after NU5 activation.
- New: Support for receiving v5 transactions.
- Known issues: The SDK will not run on Intel 64-bit API 31+ emulators.  Workarounds include: testing on a physical device, using an older 32-bit API version Intel emulator, or using an ARM emulator.

## Version 1.4.0-beta01
- Main entrypoint to the SDK has changed.  See [MIGRATIONS.md](MIGRATIONS.md)
- The minimum version of Android supported is now API 19
- Updated checkpoints for Mainnet and Testnet
- Internal bugfixes around concurrent access to resources, which could cause transient failures and data corruption
- Added ProGuard rules so that SDK clients can use R8 to shrink their apps
- Updated dependencies, including Kotlin 1.6.21, Coroutines 1.6.1, GRPC 1.46.0, Okio 3.1.0, NDK 23
- Known issues: The SDK will not run on Intel 64-bit API 31+ emulators.  Workarounds include: testing on a physical device, using an older 32-bit API version Intel emulator, or using an ARM emulator.

## Version 1.3.0-beta20
- New: Updated checkpoints for Mainnet and Testnet

## Version 1.3.0-beta19
- New: Updated checkpoints for Mainnet and Testnet
- Fix: Repackaged internal classes to a new `internal` package name
- Fix: Testnet checkpoints have been corrected
- Updated dependencies

## Version 1.3.0-beta18
- Fix: Corrected logic when calculating birthdates for wallets with zero received notes.

## Version 1.3.0-beta17
- Fix: Autoshielding confirmation count error so funds are available after 10 confirmations.
- New: Allow developers to enable Rust logs.
- New: Accept GZIP compression from lightwalletd.
- New: Reduce the UTXO retry time.

## Version 1.3.0-beta16
- Fix: Gracefully handle failures while fetching UTXOs.
- New: Expose StateFlows for balances.
- New: Make it easier to subscribe to transactions.
- New: Cleanup default logs.
- New: Convenience functions for WalletBalance objects.

## Version 1.3.0-beta15
- Fix: Increase reconnection attempts on failed app restart.
- New: Updated checkpoints for testnet and mainnet.

## Version 1.3.0-beta14
- New: Add separate flows for sapling, orchard and tranparent balances.
- Fix: Continue troubleshooting and fixing server disconnects.
- Updated dependencies.

## Version 1.3.0-beta12
- New: Expose network height as StateFlow.
- Fix: Reconnect to lightwalletd when a service exception occurs.

## Version 1.3.0-beta11
- Fix: Remove unused flag that was breaking new wallet creation for some wallets.

## Version 1.3.0-beta10
- Fix: Make it safe to call the new prepare function more than once.

## Version 1.3.0-beta09
- New: Add quick rewind feature, which makes it easy to rescan blocks after an upgrade.
- Fix: Repair complex data migration bug that caused crashes on upgrades.

## Version 1.3.0-beta08
- Fix: Disable librustzcash logs by default.

## Version 1.3.0-beta07
- Fix: Address issues with key migration, allowing wallets to reset viewing keys, when needed.

## Version 1.3.0-beta06
- Fix: Repair publishing so that AARs work on Windows machines [issue #222].
- Fix: Incorrect BranchId on 32-bit devics [issue #224].
- Fix: Rescan should not go beyond the wallet checkpoint.
- New: Drop Android Jetifier since it is no longer used.
- Updated checkpoints, improved tests (added Test Suites) and better error messages.

## Version 1.3.0-beta05
- Major: Consolidate product flavors into one library for the SDK instead of two.
- Major: Integrates with latest Librustzcash including full Data Access API support.
- Major: Move off of JCenter and onto Maven Central.
- New: Adds Ktlint [Credit: @nighthawk24]
- Fix: Added SaplingParamTool and ability to clear param files from cache [Credit: @herou]
- New: Added responsible disclosure document for vulnerabilities [Credit: @zebambam]
- New: UnifiedViewingKey concept.
- New: Adds support for autoshielding, including database migrations.
- New: Adds basic support for UTXOs, including refresh during scan.
- New: Support the ability to wipe all sqlite data and rebuild from keys.
- New: Switches to ZOMG lightwalletd instances.
- Fix: Only notify subscribers when a new block is detected.
- New: Add scan metrics and callbacks for apps to measure performance.
- Fix: Improve error handling and surface critical Initialization errors.
- New: Adds cleanup and removal of failed transactions.
- New: Improved logic for determining the wallet birthday.
- New: Add the ability to rewind and rescan blocks.
- New: Better safeguards against testnet v mainnet data contamination.
- New: Improved troubleshooting of ungraceful shutdowns.
- Docs: Update README to draw attention to the demo app.
- New: Expose transaction count.
- New: Derive sapling activation height from the active network.
- New: Latest checkpoints for mainnet and testnet.

## Version 1.2.1-beta04
- New: Updated to latest versions of grpc, grpc-okhttp and protoc
- Fix: Addresses root issue of Android 11 crash on SSL sockets

## Version 1.2.1-beta03
- New: Implements ZIP-313, reducing the default fee from 10,000 to 1,000 zats.
- Fix: 80% reduction in build warnings from 90 -> 18 and improved docs [Credit: @herou].

## Version 1.2.1-beta02
- New: Improve birthday configuration and config functions.
- Fix: Broken layout in demo app transaction list.

## Version 1.2.1-beta01
- New: Added latest checkpoints for testnet and mainnet.
- New: Added display name for Canopy.
- New: Update to the latest lightwalletd service definition.
- Fix: Convert Initializer.Builder to Initializer.Config to simplify the constructors.

## Version 1.2.0-beta01
- New: Added ability to erase initializer data.
- Fix: Updated to latest librustzcash, fixing send functionality on Canopy.

## Version 1.1.0-beta10
- New: Modified visibility on a few things to facilitate partner integrations.

## Version 1.1.0-beta08
- Fix: Publishing has been corrected by jcenter's support team.
- New: Minor improvement to initializer

## Version 1.1.0-beta05
- New: Synchronizer can now be started with just a viewing key.
- New: Initializer improvements.
- New: Added tool for loading checkpoints.
- New: Added tool for deriving keys and addresses, statically.
- New: Updated and revamped the demo apps.
- New: Added a bit more (unofficial) t-addr support.
- Fix: Broken testnet demo app.
- Fix: Publishing configuration.

## Version 1.1.0-beta04
- New: Add support for canopy on testnet.
- New: Change the default lightwalletd server.
- New: Add lightwalletd service for fetching t-addr transactions.
- New: prove the concept of local RPC via protobufs.
- New: Iterate on the demo app.
- New: Added new checkpoints.
- Fix: Minor enhancements.

## Version 1.1.0-beta03
- New: Add robust support for transaction cancellation.
- New: Update to latest version of librustzcash.
- New: Expand test support.
- New: Improve and simplify intialization.
- New: Flag when rust is running in debug mode, causing a 10X slow down.
- New: Contributing guidelines.
- Fix: Minor cleanup and improvements.
