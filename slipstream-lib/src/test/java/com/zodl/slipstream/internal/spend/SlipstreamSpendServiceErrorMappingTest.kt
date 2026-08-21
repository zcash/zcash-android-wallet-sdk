package com.zodl.slipstream.internal.spend

import cash.z.ecc.android.sdk.exception.PcztException
import cash.z.ecc.android.sdk.exception.TransactionEncoderException
import cash.z.ecc.android.sdk.internal.Backend
import cash.z.ecc.android.sdk.internal.model.ProposalUnsafe
import cash.z.ecc.android.sdk.model.Account
import cash.z.ecc.android.sdk.model.AccountUuid
import cash.z.ecc.android.sdk.model.FirstClassByteArray
import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.SdkFlags
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.lightwallet.client.CombinedWalletClient
import com.zodl.slipstream.internal.SlipstreamEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/**
 * Pins the propose-failure mapping of [SlipstreamSpendService]: the Rust layer's untyped
 * insufficient-funds failure becomes [TransactionEncoderException.InsufficientFundsException] no
 * matter which propose entry point produced it, everything else keeps the per-operation wrapper the
 * upstream `TransactionEncoderImpl` uses, and a cancellation still travels as itself. Also covers the
 * structural TEX/multi-step guard on the PCZT path.
 *
 * Conventions follow [SlipstreamSpendServiceOrderingTest]: plain Mockito with exact-argument stubs
 * (no matcher library is a project dependency), JUnit4 and `runBlocking`.
 */
class SlipstreamSpendServiceErrorMappingTest {
    private val account = Account.new(AccountUuid.new(ByteArray(ACCOUNT_UUID_SIZE)))

    private val insufficientFunds = RuntimeException("Insufficient balance (have 100, need 200 including fee)")

    private val unrelated = RuntimeException("the database is locked")

    @Test
    fun proposeTransferMapsInsufficientBalance() =
        runBlocking {
            val backend = mock(Backend::class.java)
            `when`(backend.proposeTransfer(account.accountUuid.value, RECIPIENT, AMOUNT.value, null))
                .thenThrow(insufficientFunds)

            val exception =
                assertFailsWith<TransactionEncoderException.InsufficientFundsException> {
                    service(backend).proposeTransfer(account, RECIPIENT, AMOUNT, "")
                }

            assertSame(insufficientFunds, exception.rootCause)
            assertSame(insufficientFunds, exception.cause)
        }

    @Test
    fun proposeTransferMapsOtherFailuresToTheParametersWrapper() =
        runBlocking {
            val backend = mock(Backend::class.java)
            `when`(backend.proposeTransfer(account.accountUuid.value, RECIPIENT, AMOUNT.value, null))
                .thenThrow(unrelated)

            val exception =
                assertFailsWith<TransactionEncoderException.ProposalFromParametersException> {
                    service(backend).proposeTransfer(account, RECIPIENT, AMOUNT, "")
                }

            assertSame(unrelated, exception.rootCause)
        }

    @Test
    fun proposeTransferPropagatesCancellation() =
        runBlocking {
            val cancellation = CancellationException("cancelled")
            val backend = mock(Backend::class.java)
            `when`(backend.proposeTransfer(account.accountUuid.value, RECIPIENT, AMOUNT.value, null))
                .thenThrow(cancellation)

            val thrown =
                assertFailsWith<CancellationException> {
                    service(backend).proposeTransfer(account, RECIPIENT, AMOUNT, "")
                }

            assertSame(cancellation, thrown)
        }

    @Test
    fun proposeFulfillingPaymentUriMapsInsufficientBalance() =
        runBlocking {
            val backend = mock(Backend::class.java)
            `when`(backend.proposeTransferFromUri(account.accountUuid.value, URI)).thenThrow(insufficientFunds)

            val exception =
                assertFailsWith<TransactionEncoderException.InsufficientFundsException> {
                    service(backend).proposeFulfillingPaymentUri(account, URI)
                }

            assertSame(insufficientFunds, exception.rootCause)
        }

    @Test
    fun proposeFulfillingPaymentUriMapsOtherFailuresToTheUriWrapper() =
        runBlocking {
            val backend = mock(Backend::class.java)
            `when`(backend.proposeTransferFromUri(account.accountUuid.value, URI)).thenThrow(unrelated)

            val exception =
                assertFailsWith<TransactionEncoderException.ProposalFromUriException> {
                    service(backend).proposeFulfillingPaymentUri(account, URI)
                }

            assertSame(unrelated, exception.rootCause)
        }

    @Test
    fun proposeShieldingMapsInsufficientBalance() =
        runBlocking {
            val backend = mock(Backend::class.java)
            `when`(backend.proposeShielding(account.accountUuid.value, THRESHOLD.value, null, null))
                .thenThrow(insufficientFunds)

            val exception =
                assertFailsWith<TransactionEncoderException.InsufficientFundsException> {
                    service(backend).proposeShielding(account, THRESHOLD, "", null)
                }

            assertSame(insufficientFunds, exception.rootCause)
        }

    @Test
    fun proposeShieldingMapsOtherFailuresToTheShieldingWrapper() =
        runBlocking {
            val backend = mock(Backend::class.java)
            `when`(backend.proposeShielding(account.accountUuid.value, THRESHOLD.value, null, null))
                .thenThrow(unrelated)

            val exception =
                assertFailsWith<TransactionEncoderException.ProposalShieldingException> {
                    service(backend).proposeShielding(account, THRESHOLD, "", null)
                }

            assertSame(unrelated, exception.rootCause)
        }

    @Test
    fun proposeOrchardToIronwoodMigrationMapsInsufficientBalance() =
        runBlocking {
            val backend = mock(Backend::class.java)
            `when`(backend.proposeOrchardToIronwoodMigration(account.accountUuid.value)).thenThrow(insufficientFunds)

            val exception =
                assertFailsWith<TransactionEncoderException.InsufficientFundsException> {
                    service(backend).proposeOrchardToIronwoodMigration(account)
                }

            assertSame(insufficientFunds, exception.rootCause)
        }

    @Test
    fun proposeOrchardToIronwoodMigrationMapsOtherFailuresToTheParametersWrapper() =
        runBlocking {
            val backend = mock(Backend::class.java)
            `when`(backend.proposeOrchardToIronwoodMigration(account.accountUuid.value)).thenThrow(unrelated)

            val exception =
                assertFailsWith<TransactionEncoderException.ProposalFromParametersException> {
                    service(backend).proposeOrchardToIronwoodMigration(account)
                }

            assertSame(unrelated, exception.rootCause)
        }

    @Test
    fun createPcztFromProposalRejectsMultiStepProposalsBeforeTouchingTheBackend() =
        runBlocking {
            val backend = mock(Backend::class.java)

            assertFailsWith<PcztException.MultiStepProposalUnsupportedException> {
                service(backend).createPcztFromProposal(account.accountUuid, proposal(transactionCount = 2))
            }

            verifyNoInteractions(backend)
        }

    @Test
    fun createPcztFromProposalAcceptsSingleStepProposals() =
        runBlocking {
            val backend = mock(Backend::class.java)
            val proposal = proposal(transactionCount = 1)
            val pcztBytes = byteArrayOf(7, 7, 7)
            `when`(backend.createPcztFromProposal(account.accountUuid.value, proposal.toUnsafe()))
                .thenReturn(pcztBytes)

            val pczt = service(backend).createPcztFromProposal(account.accountUuid, proposal)

            assertSame(pcztBytes, pczt.toByteArray())
        }

    private fun proposal(transactionCount: Int): Proposal {
        val proposalUnsafe = mock(ProposalUnsafe::class.java)
        `when`(proposalUnsafe.totalFeeRequired()).thenReturn(FEE)
        `when`(proposalUnsafe.transactionCount()).thenReturn(transactionCount)
        return Proposal.fromUnsafe(proposalUnsafe)
    }

    private fun service(backend: Backend): SlipstreamSpendService =
        SlipstreamSpendService(
            backend = backend,
            walletClient = mock(CombinedWalletClient::class.java),
            engine = mock(SlipstreamEngine::class.java),
            sdkFlags = SdkFlags(isTorEnabled = false, isExchangeRateEnabled = false),
            ensureSaplingParams = { },
            readRawTransaction = { FirstClassByteArray(byteArrayOf()) }
        )

    private companion object {
        const val ACCOUNT_UUID_SIZE = 16
        const val RECIPIENT = "recipient"
        const val URI = "zcash:recipient?amount=1"
        const val FEE = 1_000L
        val AMOUNT = Zatoshi(100_000L)
        val THRESHOLD = Zatoshi(100_000L)
    }
}
