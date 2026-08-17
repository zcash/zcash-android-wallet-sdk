package com.zodl.slipstream.internal.spend

import cash.z.ecc.android.sdk.internal.Backend
import cash.z.ecc.android.sdk.internal.model.ProposalUnsafe
import cash.z.ecc.android.sdk.model.FirstClassByteArray
import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.SdkFlags
import cash.z.ecc.android.sdk.model.UnifiedSpendingKey
import co.electriccoin.lightwallet.client.CombinedWalletClient
import co.electriccoin.lightwallet.client.ServiceMode
import co.electriccoin.lightwallet.client.model.Response
import co.electriccoin.lightwallet.client.model.SendResponseUnsafe
import com.zodl.slipstream.internal.SlipstreamEngine
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

/**
 * `SDK_ADAPTER_PLAN.md` T8's "failing test first": pins the store-first law - `createProposedTransactions`
 * pokes [SlipstreamEngine.notifyTxChange] BEFORE the first broadcast (the store already happened
 * inside `create`), and again after. Uses plain Mockito (`mockito-junit-jupiter`'s bundled
 * `mockito-core`, inline mock maker by default since 5.0 - mocks final Kotlin classes and classes
 * with private constructors, e.g. [SlipstreamEngine]/[UnifiedSpendingKey], without any extra
 * dependency). Every stub/verification uses an EXACT argument value (no matcher library like
 * `mockito-kotlin` is a project dependency), so every mocked call is set up with the identical
 * object/array instance the code path will actually pass through.
 */
class SlipstreamSpendServiceOrderingTest {
    @Test
    fun poke_fires_before_the_first_submit_and_again_after() =
        runBlocking {
            val backend = mock(Backend::class.java)
            val walletClient = mock(CombinedWalletClient::class.java)
            val engine = mock(SlipstreamEngine::class.java)

            val proposalUnsafe = mock(ProposalUnsafe::class.java)
            `when`(proposalUnsafe.totalFeeRequired()).thenReturn(1_000L)
            val proposal = Proposal.fromUnsafe(proposalUnsafe)

            val usk = mock(UnifiedSpendingKey::class.java)
            val uskBytes = ByteArray(0)
            `when`(usk.copyBytes()).thenReturn(uskBytes)

            val txId = byteArrayOf(1, 2, 3)
            `when`(backend.createProposedTransactions(proposalUnsafe, uskBytes)).thenReturn(listOf(txId))

            val rawBytes = byteArrayOf(9, 9, 9)
            val rawTransaction = FirstClassByteArray(rawBytes)
            `when`(walletClient.submitTransaction(rawBytes, ServiceMode.Direct))
                .thenReturn(Response.Success(SendResponseUnsafe(code = 0, message = "ok")))

            var ensureCalled = false
            val service =
                SlipstreamSpendService(
                    backend = backend,
                    walletClient = walletClient,
                    engine = engine,
                    sdkFlags = SdkFlags(isTorEnabled = false, isExchangeRateEnabled = false),
                    ensureSaplingParams = { ensureCalled = true },
                    readRawTransaction = { rawTransaction }
                )

            val results = service.createProposedTransactions(proposal, usk).toList()

            kotlin.test.assertEquals(1, results.size)
            kotlin.test.assertTrue(ensureCalled)

            val order = inOrder(engine, walletClient)
            order.verify(engine).notifyTxChange()
            order.verify(walletClient).submitTransaction(rawBytes, ServiceMode.Direct)
            order.verify(engine).notifyTxChange()
        }
}
