package cash.z.ecc.android.sdk

import androidx.test.filters.SmallTest
import cash.z.ecc.android.sdk.model.Eip681PaymentRequest
import cash.z.ecc.android.sdk.model.InvalidPaymentUriException
import cash.z.ecc.android.sdk.model.PaymentUriNetwork
import cash.z.ecc.android.sdk.model.PaymentUriRequest
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

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
            assertEquals("1FsSia9rv4NeEwvJ2GvXrX7LyxYspbN2mo", bitcoin.request.address.value)
            assertEquals(PaymentUriNetwork.Mainnet, bitcoin.request.network)
            assertEquals("20.3", bitcoin.request.amount?.value)
            assertEquals("Luke-Jr", bitcoin.request.label)

            val litecoin =
                assertIs<PaymentUriRequest.Litecoin>(
                    parser.parse(
                        "litecoin:LT2KVaAy1ppRuxRgrS5RNU3vBsy7RibPeA?amount=1.25&message=Coffee"
                    )
                )
            assertEquals("LT2KVaAy1ppRuxRgrS5RNU3vBsy7RibPeA", litecoin.request.address.value)
            assertEquals(PaymentUriNetwork.Mainnet, litecoin.request.network)
            assertEquals("1.25", litecoin.request.amount?.value)
            assertEquals("Coffee", litecoin.request.message)

            val solana =
                assertIs<PaymentUriRequest.SolanaTransfer>(
                    parser.parse(
                        "solana:mvines9iiHiQTysrwkJjGf2gb9Ex9jXJX8ns3qwf2kN?amount=0.01" +
                            "&spl-token=EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"
                    )
                )
            assertEquals("mvines9iiHiQTysrwkJjGf2gb9Ex9jXJX8ns3qwf2kN", solana.request.recipient.value)
            assertEquals("0.01", solana.request.amount?.value)
            assertEquals("EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v", solana.request.splToken?.value)

            val ethereum =
                assertIs<PaymentUriRequest.Ethereum>(
                    parser.parse(
                        "ethereum:0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359@42161?value=2.014e18" +
                            "&gasLimit=21000&gasPrice=50"
                    )
                )
            val native = assertIs<Eip681PaymentRequest.Native>(ethereum.request)
            assertEquals("0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359", native.recipientAddress.value)
            assertEquals("0x1bf32a5451a30000", native.valueHex)
            assertEquals("42161", native.chainId)
            assertEquals("0x5208", native.gasLimitHex)
            assertEquals("0x32", native.gasPriceHex)
        }

    @Test
    @SmallTest
    fun parsesEthereumErc20Request() =
        runTest {
            val parser = PaymentUriParser.new()
            val contract = "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48"
            val recipient = "0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359"

            val ethereum =
                assertIs<PaymentUriRequest.Ethereum>(
                    parser.parse("ethereum:$contract/transfer?address=$recipient&uint256=1000000")
                )
            val erc20 = assertIs<Eip681PaymentRequest.Erc20>(ethereum.request)
            assertEquals(contract, erc20.tokenContractAddress.value)
            assertEquals(recipient, erc20.recipientAddress.value)
            assertEquals("0xf4240", erc20.valueHex)
            assertNull(erc20.chainId)
        }

    @Test
    @SmallTest
    fun parsesUnrecognisedEip681Request() =
        runTest {
            val parser = PaymentUriParser.new()
            val contract = "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48"
            val recipient = "0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359"

            val ethereum =
                assertIs<PaymentUriRequest.Ethereum>(
                    parser.parse("ethereum:$contract/approve?address=$recipient")
                )
            assertIs<Eip681PaymentRequest.Unrecognised>(ethereum.request)
        }

    @Test
    @SmallTest
    fun parsesTestnetAndRegtestNetworks() =
        runTest {
            val parser = PaymentUriParser.new()

            val testnet =
                assertIs<PaymentUriRequest.Bitcoin>(
                    parser.parse("bitcoin:tb1qw508d6qejxtdg4y5r3zarvary0c5xw7kxpjzsx")
                )
            assertEquals(PaymentUriNetwork.Testnet, testnet.request.network)

            val regtest =
                assertIs<PaymentUriRequest.Bitcoin>(
                    parser.parse("bitcoin:bcrt1qw508d6qejxtdg4y5r3zarvary0c5xw7kygt080")
                )
            assertEquals(PaymentUriNetwork.Regtest, regtest.request.network)
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

    @Test
    @SmallTest
    fun parsesSolanaTransactionLink() =
        runTest {
            val parser = PaymentUriParser.new()
            val transaction =
                assertIs<PaymentUriRequest.SolanaTransaction>(
                    parser.parse("solana:https%3A%2F%2Fexample.com%2Fsolana-pay%3Forder%3D12345")
                )
            assertEquals("https://example.com/solana-pay?order=12345", transaction.link.value)
        }

    @Test
    @SmallTest
    fun rejectsUnencodedSolanaTransactionLink() =
        runTest {
            val parser = PaymentUriParser.new()
            assertFailsWith<InvalidPaymentUriException> {
                parser.parse("solana:https://example.com/solana-pay?order=12345")
            }
        }
}
