package cash.z.ecc.android.sdk.internal.transaction

import cash.z.ecc.android.sdk.exception.TransactionEncoderException
import cash.z.ecc.android.sdk.internal.SaplingParamFetcher
import cash.z.ecc.android.sdk.internal.TypesafeBackend
import cash.z.ecc.android.sdk.internal.repository.DerivedDataRepository
import cash.z.ecc.android.sdk.model.Account
import cash.z.ecc.android.sdk.model.AccountUuid
import cash.z.ecc.android.sdk.model.Zatoshi
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/**
 * The upstream twin of [com.zodl.slipstream.internal.spend.SlipstreamSpendServiceErrorMappingTest]:
 * both engines must report the Rust layer's insufficient-funds failure as the same typed exception,
 * and keep the per-operation wrapper for everything else.
 */
class TransactionEncoderImplErrorMappingTest {
    private val account = Account.new(AccountUuid.new(ByteArray(ACCOUNT_UUID_SIZE)))

    private val insufficientFunds = RuntimeException("Insufficient balance (have 0, need 1510000 including fee)")

    private val unrelated = RuntimeException("the database is locked")

    @Test
    fun proposeTransferMapsInsufficientBalance() =
        runBlocking {
            val backend = mock(TypesafeBackend::class.java)
            `when`(backend.proposeTransfer(account, RECIPIENT, AMOUNT.value, null)).thenThrow(insufficientFunds)

            val exception =
                assertFailsWith<TransactionEncoderException.InsufficientFundsException> {
                    encoder(backend).proposeTransfer(account, RECIPIENT, AMOUNT, null)
                }

            assertSame(insufficientFunds, exception.rootCause)
            assertSame(insufficientFunds, exception.cause)
        }

    @Test
    fun proposeTransferMapsOtherFailuresToTheParametersWrapper() =
        runBlocking {
            val backend = mock(TypesafeBackend::class.java)
            `when`(backend.proposeTransfer(account, RECIPIENT, AMOUNT.value, null)).thenThrow(unrelated)

            val exception =
                assertFailsWith<TransactionEncoderException.ProposalFromParametersException> {
                    encoder(backend).proposeTransfer(account, RECIPIENT, AMOUNT, null)
                }

            assertSame(unrelated, exception.rootCause)
        }

    @Test
    fun proposeTransferFromUriMapsInsufficientBalance() =
        runBlocking {
            val backend = mock(TypesafeBackend::class.java)
            `when`(backend.proposeTransferFromUri(account, URI)).thenThrow(insufficientFunds)

            val exception =
                assertFailsWith<TransactionEncoderException.InsufficientFundsException> {
                    encoder(backend).proposeTransferFromUri(account, URI)
                }

            assertSame(insufficientFunds, exception.rootCause)
        }

    @Test
    fun proposeTransferFromUriMapsOtherFailuresToTheUriWrapper() =
        runBlocking {
            val backend = mock(TypesafeBackend::class.java)
            `when`(backend.proposeTransferFromUri(account, URI)).thenThrow(unrelated)

            val exception =
                assertFailsWith<TransactionEncoderException.ProposalFromUriException> {
                    encoder(backend).proposeTransferFromUri(account, URI)
                }

            assertSame(unrelated, exception.rootCause)
        }

    @Test
    fun proposeShieldingMapsInsufficientBalance() =
        runBlocking {
            val backend = mock(TypesafeBackend::class.java)
            `when`(backend.proposeShielding(account, THRESHOLD.value, null, null)).thenThrow(insufficientFunds)

            val exception =
                assertFailsWith<TransactionEncoderException.InsufficientFundsException> {
                    encoder(backend).proposeShielding(account, THRESHOLD, null, null)
                }

            assertSame(insufficientFunds, exception.rootCause)
        }

    @Test
    fun proposeShieldingMapsOtherFailuresToTheShieldingWrapper() =
        runBlocking {
            val backend = mock(TypesafeBackend::class.java)
            `when`(backend.proposeShielding(account, THRESHOLD.value, null, null)).thenThrow(unrelated)

            val exception =
                assertFailsWith<TransactionEncoderException.ProposalShieldingException> {
                    encoder(backend).proposeShielding(account, THRESHOLD, null, null)
                }

            assertSame(unrelated, exception.rootCause)
        }

    @Test
    fun proposeOrchardToIronwoodMigrationMapsInsufficientBalance() =
        runBlocking {
            val backend = mock(TypesafeBackend::class.java)
            `when`(backend.proposeOrchardToIronwoodMigration(account)).thenThrow(insufficientFunds)

            val exception =
                assertFailsWith<TransactionEncoderException.InsufficientFundsException> {
                    encoder(backend).proposeOrchardToIronwoodMigration(account)
                }

            assertSame(insufficientFunds, exception.rootCause)
        }

    @Test
    fun proposeOrchardToIronwoodMigrationMapsOtherFailuresToTheParametersWrapper() =
        runBlocking {
            val backend = mock(TypesafeBackend::class.java)
            `when`(backend.proposeOrchardToIronwoodMigration(account)).thenThrow(unrelated)

            val exception =
                assertFailsWith<TransactionEncoderException.ProposalFromParametersException> {
                    encoder(backend).proposeOrchardToIronwoodMigration(account)
                }

            assertSame(unrelated, exception.rootCause)
        }

    private fun encoder(backend: TypesafeBackend): TransactionEncoderImpl =
        TransactionEncoderImpl(
            backend = backend,
            saplingParamFetcher = mock(SaplingParamFetcher::class.java),
            repository = mock(DerivedDataRepository::class.java)
        )

    private companion object {
        const val ACCOUNT_UUID_SIZE = 16
        const val RECIPIENT = "recipient"
        const val URI = "zcash:recipient?amount=1"
        val AMOUNT = Zatoshi(100_000L)
        val THRESHOLD = Zatoshi(100_000L)
    }
}
