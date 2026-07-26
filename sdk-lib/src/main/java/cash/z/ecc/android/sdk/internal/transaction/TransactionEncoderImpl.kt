package cash.z.ecc.android.sdk.internal.transaction

import cash.z.ecc.android.sdk.exception.PcztException
import cash.z.ecc.android.sdk.exception.TransactionEncoderException
import cash.z.ecc.android.sdk.ext.masked
import cash.z.ecc.android.sdk.internal.SaplingParamFetcher
import cash.z.ecc.android.sdk.internal.Twig
import cash.z.ecc.android.sdk.internal.TypesafeBackend
import cash.z.ecc.android.sdk.internal.model.EncodedTransaction
import cash.z.ecc.android.sdk.internal.repository.DerivedDataRepository
import cash.z.ecc.android.sdk.model.Account
import cash.z.ecc.android.sdk.model.AccountUuid
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.Pczt
import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.UnifiedSpendingKey
import cash.z.ecc.android.sdk.model.Zatoshi

/**
 * Class responsible for encoding a transaction in a consistent way. This bridges the gap by
 * behaving like a stateless API so that callers can request create a transaction and receive a
 * result, even though there are intermediate database interactions.
 *
 * @property backend the instance of RustBackendWelding to use for creating and validating.
 * @property repository the repository that stores information about the transactions being created
 * such as the raw bytes and raw txId.
 */
@Suppress("TooManyFunctions")
internal class TransactionEncoderImpl(
    private val backend: TypesafeBackend,
    private val saplingParamFetcher: SaplingParamFetcher,
    private val repository: DerivedDataRepository
) : TransactionEncoder {
    /**
     * Creates a proposal for transferring from a valid ZIP-321 Payment URI string
     *
     * @param account the account from which to transfer funds.
     * @param uri a valid ZIP-321 Payment URI string
     *
     * @return the proposal or an exception
     *
     * @throws TransactionEncoderException.ProposalFromUriException
     */
    @Throws(TransactionEncoderException.ProposalFromUriException::class)
    override suspend fun proposeTransferFromUri(
        account: Account,
        uri: String
    ): Proposal {
        Twig.debug {
            "creating proposal from URI: $uri"
        }

        return runCatching {
            backend.proposeTransferFromUri(
                account,
                uri
            )
        }.onSuccess {
            Twig.info { "Result of proposeTransferFromUri: ${it.toPrettyString()}" }
        }.onFailure {
            Twig.error(it) { "Caught exception while creating proposal from URI String." }
        }.getOrElse {
            throw TransactionEncoderException.ProposalFromUriException(it)
        }
    }

    @Throws(TransactionEncoderException.ProposalFromParametersException::class)
    override suspend fun proposeOrchardToIronwoodMigration(account: Account): Proposal {
        Twig.debug { "creating proposal to migrate the Orchard balance into Ironwood" }

        return runCatching {
            backend.proposeOrchardToIronwoodMigration(account)
        }.onSuccess {
            Twig.info { "Result of proposeOrchardToIronwoodMigration: ${it.toPrettyString()}" }
        }.onFailure {
            Twig.error(it) { "Caught exception while creating the migration proposal." }
        }.getOrElse {
            throw TransactionEncoderException.ProposalFromParametersException(it)
        }
    }

    @Throws(TransactionEncoderException.ProposalFromParametersException::class)
    override suspend fun proposeTransfer(
        account: Account,
        recipient: String,
        amount: Zatoshi,
        memo: ByteArray?
    ): Proposal {
        Twig.debug {
            "creating proposal to spend $amount zatoshi to" +
                " ${recipient.masked()} with memo: ${memo?.decodeToString()}"
        }

        return runCatching {
            backend.proposeTransfer(
                account,
                recipient,
                amount.value,
                memo
            )
        }.onSuccess {
            Twig.info { "Result of proposeTransfer: ${it.toPrettyString()}" }
        }.onFailure {
            Twig.error(it) { "Caught exception while creating proposal." }
        }.getOrElse {
            throw TransactionEncoderException.ProposalFromParametersException(it)
        }
    }

    @Throws(TransactionEncoderException.ProposalShieldingException::class)
    override suspend fun proposeShielding(
        account: Account,
        shieldingThreshold: Zatoshi,
        memo: ByteArray?,
        transparentReceiver: String?
    ): Proposal? =
        runCatching {
            backend.proposeShielding(account, shieldingThreshold.value, memo, transparentReceiver)
        }.onFailure {
            // TODO [#680]: if this error matches: Insufficient balance (have 0, need 1000 including fee)
            //  then consider custom error that says no UTXOs existed to shield
            // TODO [#680]: https://github.com/zcash/zcash-android-wallet-sdk/issues/680
            Twig.error(it) { "proposeShielding failed" }
        }.onSuccess { result ->
            Twig.info { "Result of proposeShielding: ${result?.toPrettyString()}" }
        }.getOrElse {
            throw TransactionEncoderException.ProposalShieldingException(it)
        }

    @Throws(
        TransactionEncoderException.TransactionNotCreatedException::class,
        TransactionEncoderException.TransactionNotFoundException::class,
    )
    override suspend fun createProposedTransactions(
        proposal: Proposal,
        usk: UnifiedSpendingKey
    ): List<EncodedTransaction> {
        Twig.debug {
            "creating transactions for proposal"
        }

        val transactionIds =
            runCatching {
                saplingParamFetcher.forceDownload()
                Twig.debug { "params exist! attempting to send..." }
                backend.createProposedTransactions(proposal, usk)
            }.onFailure {
                Twig.error(it) { "Caught exception while creating transaction." }
            }.onSuccess { result ->
                Twig.info { "Result of createProposedTransactions: $result" }
            }.getOrElse {
                throw TransactionEncoderException.TransactionNotCreatedException(it)
            }

        val txs =
            transactionIds.map { transactionId ->
                repository.findEncodedTransactionByTxId(transactionId)
                    ?: throw TransactionEncoderException.TransactionNotFoundException(transactionId)
            }

        return txs
    }

    override suspend fun createPcztFromProposal(
        accountUuid: AccountUuid,
        proposal: Proposal
    ): Pczt =
        runCatching {
            backend.createPcztFromProposal(
                account = Account.new(accountUuid),
                proposal = proposal
            )
        }.onSuccess {
            Twig.debug { "Result of createPcztFromProposal: $it" }
        }.onFailure {
            Twig.error(it) { "Caught exception while creating PCZT." }
        }.getOrElse {
            throw PcztException.CreatePcztFromProposalException(it.message, it.cause)
        }

    override suspend fun redactPcztForSigner(pczt: Pczt): Pczt =
        runCatching {
            backend.redactPcztForSigner(pczt = pczt)
        }.onSuccess {
            Twig.debug { "Result of redactPcztForSigner: $it" }
        }.onFailure {
            Twig.error(it) { "Caught exception while redacting PCZT for Signer." }
        }.getOrElse {
            throw PcztException.RedactPcztForSignerException(it.message, it.cause)
        }

    override suspend fun pcztRequiresSaplingProofs(pczt: Pczt): Boolean =
        runCatching {
            backend.pcztRequiresSaplingProofs(pczt = pczt)
        }.onSuccess {
            Twig.debug { "Result of pcztRequiresSaplingProofs: $it" }
        }.onFailure {
            Twig.error(it) { "Caught exception while checking PCZT Sapling presence." }
        }.getOrElse {
            throw PcztException.PcztRequiresSaplingProofsException(it.message, it.cause)
        }

    override suspend fun addProofsToPczt(pczt: Pczt): Pczt =
        runCatching {
            // TODO [#1724]: zcash_client_backend: Make Sapling parameters optional for extract_and_store_transaction
            // TODO [#1724]: https://github.com/zcash/librustzcash/issues/1724
            // if (backend.pcztRequiresSaplingProofs(pczt)) {
            //     saplingParamTool.ensureParams(saplingParamTool.properties.paramsDirectory)
            // }
            saplingParamFetcher.forceDownload()
            Twig.debug { "params exist! attempting to send..." }
            backend.addProofsToPczt(pczt = pczt)
        }.onSuccess {
            Twig.debug { "Result of addProofsToPczt: $it" }
        }.onFailure {
            Twig.error(it) { "Caught exception while adding proofs to PCZT." }
        }.getOrElse {
            throw PcztException.AddProofsToPcztException(it.message, it.cause)
        }

    override suspend fun extractAndStoreTxFromPczt(
        pcztWithProofs: Pczt,
        pcztWithSignatures: Pczt
    ): EncodedTransaction {
        val txId =
            runCatching {
                backend.extractAndStoreTxFromPczt(
                    pcztWithProofs = pcztWithProofs,
                    pcztWithSignatures = pcztWithSignatures
                )
            }.onSuccess {
                Twig.debug { "Result of extractAndStoreTxFromPczt: $it" }
            }.onFailure {
                Twig.error(it) { "Caught exception while extracting and storing transaction from PCZT." }
            }.getOrElse {
                throw PcztException.ExtractAndStoreTxFromPcztException(it.message, it.cause)
            }

        return repository.findEncodedTransactionByTxId(txId)
            ?: throw TransactionEncoderException.TransactionNotFoundException(txId)
    }

    /**
     * Utility function to help with validation.
     *
     * @param address the address to validate
     *
     * @return true when the given address is a valid z-addr
     */
    override suspend fun isValidShieldedAddress(address: String): Boolean = backend.isValidSaplingAddr(address)

    /**
     * Utility function to help with validation.
     *
     * @param address the address to validate
     *
     * @return true when the given address is a valid t-addr
     */
    override suspend fun isValidTransparentAddress(address: String): Boolean = backend.isValidTransparentAddr(address)

    /**
     * Utility function to help with validation.
     *
     * @param address the address to validate
     *
     * @return true when the given address is a valid ZIP 316 Unified Address
     */
    override suspend fun isValidUnifiedAddress(address: String): Boolean = backend.isValidUnifiedAddr(address)

    /**
     * Utility function to help with validation. This is not called during [createTransaction]
     * because this class asserts that all validation is done externally by the UI, for now.
     *
     * @param address the address to validate
     *
     * @return true when the given address is a valid ZIP 320 TEX address
     */
    override suspend fun isValidTexAddress(address: String): Boolean = backend.isValidTexAddr(address)

    /**
     * Return the consensus branch that the encoder is using when making transactions.
     *
     * @param height the height at which we want to get the consensus branch
     *
     * @return id of consensus branch
     *
     * @throws TransactionEncoderException.IncompleteScanException if the [height] is less than activation height
     */
    override suspend fun getConsensusBranchId(height: BlockHeight): Long {
        if (height < backend.network.saplingActivationHeight) {
            throw TransactionEncoderException.IncompleteScanException(height)
        }
        return backend.getBranchIdForHeight(height)
    }
}
