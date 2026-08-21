package cash.z.ecc.android.sdk

import androidx.test.filters.SmallTest
import cash.z.ecc.android.sdk.model.Eip681PaymentRequest
import cash.z.ecc.android.sdk.model.InvalidPaymentUriException
import cash.z.ecc.android.sdk.model.PaymentUriRequest
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class PaymentUriParserTest {
    @Test
    @SmallTest
    fun parsesSupportedPaymentRequests() =
        runTest {
            val parser = PaymentUriParser.new()

            val bitcoin =
                assertIs<PaymentUriRequest.Bitcoin>(
                    parser.parse(
                        "bitcoin:1FsSia9rv4NeEwvJ2GvXrX7LyxYspbN2mo?amount=20.3&label=Luke-Jr"
                    )
                )
            assertEquals("20.3", bitcoin.request.amount?.value)
            assertEquals("Luke-Jr", bitcoin.request.label)

            val litecoin =
                assertIs<PaymentUriRequest.Litecoin>(
                    parser.parse(
                        "litecoin:LT2KVaAy1ppRuxRgrS5RNU3vBsy7RibPeA?amount=1.25&message=Coffee"
                    )
                )
            assertEquals("1.25", litecoin.request.amount?.value)

            val solana =
                assertIs<PaymentUriRequest.SolanaTransfer>(
                    parser.parse(
                        "solana:mvines9iiHiQTysrwkJjGf2gb9Ex9jXJX8ns3qwf2kN?amount=0.01" +
                            "&spl-token=EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"
                    )
                )
            assertEquals("0.01", solana.request.amount?.value)

            val ethereum =
                assertIs<PaymentUriRequest.Ethereum>(
                    parser.parse(
                        "ethereum:0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359?value=1e18"
                    )
                )
            val native = assertIs<Eip681PaymentRequest.Native>(ethereum.request)
            assertEquals("0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359", native.recipientAddress.value)
            assertEquals("0xde0b6b3a7640000", native.valueHex)
        }

    @Test
    @SmallTest
    fun rejectsMalformedRequest() =
        runTest {
            val parser = PaymentUriParser.new()
            assertFailsWith<InvalidPaymentUriException> {
                parser.parse("bitcoin:not-an-address")
            }
        }
}
