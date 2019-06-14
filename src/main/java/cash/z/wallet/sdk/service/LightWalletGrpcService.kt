package cash.z.wallet.sdk.service

import cash.z.wallet.sdk.entity.CompactBlock
import cash.z.wallet.sdk.ext.toBlockHeight
import cash.z.wallet.sdk.rpc.CompactFormats
import cash.z.wallet.sdk.rpc.CompactTxStreamerGrpc
import cash.z.wallet.sdk.rpc.Service
import com.google.protobuf.ByteString
import io.grpc.Channel
import io.grpc.ManagedChannelBuilder
import java.util.concurrent.TimeUnit

class LightWalletGrpcService(private val channel: Channel) : LightWalletService {

    constructor(host: String, port: Int = 9067) : this(ManagedChannelBuilder.forAddress(host, port).usePlaintext().build())

    /* LightWalletService implementation */

    override fun getBlockRange(heightRange: IntRange): List<CompactBlock> {
        return channel.createStub(90L).getBlockRange(heightRange.toBlockRange()).toList()
    }

    override fun getLatestBlockHeight(): Int {
        return channel.createStub(10L).getLatestBlock(Service.ChainSpec.newBuilder().build()).height.toInt()
    }

    override fun submitTransaction(raw: ByteArray): Service.SendResponse {
        val request = Service.RawTransaction.newBuilder().setData(ByteString.copyFrom(raw)).build()
        return channel.createStub().sendTransaction(request)
    }


    //
    // Utilities
    //

    private fun Channel.createStub(timeoutSec: Long = 60L): CompactTxStreamerGrpc.CompactTxStreamerBlockingStub =
        CompactTxStreamerGrpc
            .newBlockingStub(this)
            .withDeadlineAfter(timeoutSec, TimeUnit.SECONDS)

    private fun IntRange.toBlockRange(): Service.BlockRange =
        Service.BlockRange.newBuilder()
            .setStart(this.first.toBlockHeight())
            .setEnd(this.last.toBlockHeight())
            .build()

    private fun Iterator<CompactFormats.CompactBlock>.toList(): List<CompactBlock> =
        mutableListOf<CompactBlock>().apply {
            while (hasNext()) {
                val compactBlock = next()
                this@apply += CompactBlock(compactBlock.height.toInt(), compactBlock.toByteArray())
            }
        }
}

