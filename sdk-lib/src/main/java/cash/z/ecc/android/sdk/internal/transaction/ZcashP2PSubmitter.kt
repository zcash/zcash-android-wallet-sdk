package cash.z.ecc.android.sdk.internal.transaction

import cash.z.ecc.android.sdk.internal.Twig
import cash.z.ecc.android.sdk.model.CreatedTransaction
import cash.z.ecc.android.sdk.model.FirstClassByteArray
import cash.z.ecc.android.sdk.model.TransactionSubmitResult
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom

/**
 * Submits a transaction directly to a Zcash P2P full node using the native Zcash
 * wire protocol, bypassing lightwalletd/Zaino.
 *
 * This implements the "direct P2P submission" path from ZIP 327 / ZIP 328
 * (Component A): the wallet connects directly to a node, performs the version
 * handshake, and sends the raw transaction bytes as a `tx` P2P message.  The
 * receiving node enters the transaction into its Dandelion++ stem phase, preventing
 * any intermediary server from observing the IP↔transaction correlation.
 *
 * ## Wire protocol (mainnet)
 * ```
 * TCP connect  →  send version  →  recv version  →  send verack
 *              →  recv verack   →  send tx msg   →  disconnect
 * ```
 * The `tx` message is sent without a prior `inv` — this is the "unadvertised tx"
 * convention that signals to the receiving node that the transaction is a direct
 * wallet submission and should enter stem phase immediately (ZIP 327 §Stem-phase
 * forwarding).
 *
 * ## Peer discovery
 * Uses the Zcash mainnet DNS seeders (same set as Zebra defaults) to find a random
 * live full node.  Falls back across multiple DNS entries until one accepts the
 * TCP connection.
 *
 * ## Error semantics
 * Returns [TransactionSubmitResult.Failure] with [grpcError]=true if no peer could
 * be reached, or if the TCP/wire exchange failed.  Returns [TransactionSubmitResult.Success]
 * if the peer accepted the `tx` message (responded with `verack` and did not
 * immediately disconnect with a `reject` message).
 *
 * Note: P2P is fire-and-forget at the wire level — the peer does not send an
 * acknowledgement for the `tx` message itself.  We treat a clean disconnect after
 * the `tx` send as success; a `reject` message (code != 0) is a failure.
 */
internal class ZcashP2PSubmitter(
    private val network: ZcashP2PNetwork = ZcashP2PNetwork.MAINNET
) : TransactionSubmitter {

    override suspend fun submit(
        transaction: CreatedTransaction,
        endpoint: LightWalletEndpoint
    ): TransactionSubmitResult =
        withContext(Dispatchers.IO) {
            submitOnIo(transaction)
        }

    private fun submitOnIo(transaction: CreatedTransaction): TransactionSubmitResult {
        val peers = resolvePeers()
        if (peers.isEmpty()) {
            Twig.error { "Dandelion P2P: could not resolve any peers from DNS seeders" }
            return TransactionSubmitResult.Failure(
                txId = transaction.txId,
                grpcError = true,
                code = -1,
                description = "No Zcash P2P peers available from DNS seeders"
            )
        }

        // Shuffle and try until one succeeds.
        for (peer in peers.shuffled()) {
            Twig.info { "Dandelion P2P: trying peer ${peer.hostAddress}:${network.port}" }
            val result = trySubmitToPeer(peer, transaction)
            if (result != null) return result
        }

        return TransactionSubmitResult.Failure(
            txId = transaction.txId,
            grpcError = true,
            code = -1,
            description = "All P2P peers failed; tx not submitted"
        )
    }

    /**
     * Returns null if this peer should be skipped (connection/protocol failure),
     * or a [TransactionSubmitResult] if the exchange completed.
     */
    private fun trySubmitToPeer(
        peer: InetAddress,
        transaction: CreatedTransaction
    ): TransactionSubmitResult? {
        return try {
            Socket().use { socket ->
                socket.connect(java.net.InetSocketAddress(peer, network.port), CONNECT_TIMEOUT_MS)
                socket.soTimeout = IO_TIMEOUT_MS

                val out = socket.getOutputStream()
                val ins = DataInputStream(socket.getInputStream())

                // 1. Send our version message.
                sendVersionMessage(out, peer)

                // 2. Read version + verack from the remote node.
                if (!receiveVersionAndVerack(ins)) {
                    Twig.warn { "Dandelion P2P: peer ${peer.hostAddress} failed version handshake" }
                    return@use null
                }

                // 3. Send verack.
                sendVerack(out)

                // 4. Send the tx message (unadvertised — no prior inv).
                sendTxMessage(out, transaction.raw)
                out.flush()

                Twig.info { "Dandelion P2P: tx sent to ${peer.hostAddress}" }

                // 5. Give the peer a moment to respond with reject if it rejects.
                //    A clean EOF or timeout here is success.
                val rejected = checkForReject(ins)
                if (rejected != null) {
                    Twig.warn { "Dandelion P2P: peer ${peer.hostAddress} rejected tx: $rejected" }
                    TransactionSubmitResult.Failure(
                        txId = transaction.txId,
                        grpcError = false,
                        code = 1,
                        description = rejected
                    )
                } else {
                    TransactionSubmitResult.Success(transaction.txId)
                }
            }
        } catch (e: IOException) {
            Twig.warn(e) { "Dandelion P2P: IO error with peer ${peer.hostAddress}" }
            null // try next peer
        } catch (e: Exception) {
            Twig.warn(e) { "Dandelion P2P: unexpected error with peer ${peer.hostAddress}" }
            null
        }
    }

    private fun resolvePeers(): List<InetAddress> {
        val peers = mutableListOf<InetAddress>()
        for (seeder in network.dnsSeeds) {
            try {
                peers += InetAddress.getAllByName(seeder).toList()
            } catch (e: IOException) {
                Twig.warn(e) { "Dandelion P2P: DNS lookup failed for $seeder" }
            }
        }
        return peers
    }

    // ── Wire message builders ────────────────────────────────────────────────

    /**
     * Builds a Zcash P2P `version` message.
     *
     * https://developer.bitcoin.org/reference/p2p_networking.html#version
     * (Zcash uses identical framing with magic bytes 0x24e92764 for mainnet.)
     */
    private fun sendVersionMessage(out: OutputStream, peer: InetAddress) {
        val nonce = SecureRandom().nextLong()
        val userAgent = USER_AGENT.encodeToByteArray()
        val userAgentLen = encodeVarInt(userAgent.size.toLong())
        val startHeight = 0

        // Payload: version(4) + services(8) + timestamp(8) + addr_recv(26) + addr_from(26)
        //          + nonce(8) + user_agent_len(varint) + user_agent + start_height(4)
        val payload = ByteBuffer.allocate(
            4 + 8 + 8 + 26 + 26 + 8 + userAgentLen.size + userAgent.size + 4
        ).apply {
            order(ByteOrder.LITTLE_ENDIAN)
            putInt(PROTOCOL_VERSION)
            putLong(NODE_NETWORK)
            putLong(System.currentTimeMillis() / 1000)
            put(encodeNetAddr(peer)) // addr_recv (26 bytes)
            put(encodeNetAddr(null)) // addr_from (26 bytes, zeroed)
            putLong(nonce)
            put(userAgentLen)
            put(userAgent)
            putInt(startHeight)
        }.array()

        out.write(buildMessage(network.magic, "version", payload))
    }

    private fun sendVerack(out: OutputStream) {
        out.write(buildMessage(network.magic, "verack", byteArrayOf()))
    }

    private fun sendTxMessage(out: OutputStream, rawTx: FirstClassByteArray) {
        out.write(buildMessage(network.magic, "tx", rawTx.byteArray))
    }

    /**
     * Reads messages until we see both `version` and `verack` from the remote.
     * Returns true if handshake succeeded within timeout, false otherwise.
     */
    private fun receiveVersionAndVerack(ins: DataInputStream): Boolean {
        var sawVersion = false
        var sawVerack = false
        repeat(MAX_HANDSHAKE_MSGS) {
            val msg = readMessage(ins) ?: return false
            when (msg.command) {
                "version" -> sawVersion = true
                "verack" -> sawVerack = true
            }
            if (sawVersion && sawVerack) return true
        }
        return sawVersion // verack may come later; version is required
    }

    /**
     * Reads one more message after sending `tx`.  If it is `reject`, returns the
     * reject reason string.  Timeout or EOF = success.
     */
    private fun checkForReject(ins: DataInputStream): String? {
        return try {
            val msg = readMessage(ins) ?: return null
            if (msg.command == "reject") {
                parseRejectReason(msg.payload)
            } else {
                null
            }
        } catch (_: IOException) {
            null // EOF / timeout = no reject = success
        }
    }

    // ── Low-level framing ────────────────────────────────────────────────────

    private data class P2PMessage(val command: String, val payload: ByteArray)

    private fun buildMessage(magic: ByteArray, command: String, payload: ByteArray): ByteArray {
        val cmdBytes = ByteArray(12).also { buf ->
            val enc = command.encodeToByteArray()
            enc.copyInto(buf, 0, 0, minOf(enc.size, 12))
        }
        val checksum = doubleShaSha256(payload).copyOf(4)
        val header = ByteBuffer.allocate(24).apply {
            order(ByteOrder.LITTLE_ENDIAN)
            put(magic)
            put(cmdBytes)
            putInt(payload.size)
            put(checksum)
        }.array()
        return header + payload
    }

    private fun readMessage(ins: DataInputStream): P2PMessage? {
        // Read 24-byte header: magic(4) + command(12) + length(4) + checksum(4)
        val header = ByteArray(24)
        ins.readFully(header)
        val length = ByteBuffer.wrap(header, 16, 4).order(ByteOrder.LITTLE_ENDIAN).int
        if (length < 0 || length > MAX_MESSAGE_BYTES) return null
        val payload = ByteArray(length)
        if (length > 0) ins.readFully(payload)
        val command = header.slice(4..15).toByteArray()
            .decodeToString().trimEnd(' ')
        return P2PMessage(command, payload)
    }

    private fun parseRejectReason(payload: ByteArray): String {
        // reject: message(varstr) + code(1) + reason(varstr)
        return try {
            val buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
            val msgLen = readVarInt(buf).toInt()
            repeat(msgLen) { buf.get() }
            buf.get() // code
            val reasonLen = readVarInt(buf).toInt()
            val reason = ByteArray(reasonLen).also { buf.get(it) }
            reason.decodeToString()
        } catch (_: Exception) {
            "unknown reject reason"
        }
    }

    private fun encodeNetAddr(addr: InetAddress?): ByteArray =
        ByteBuffer.allocate(26).apply {
            order(ByteOrder.LITTLE_ENDIAN)
            putLong(NODE_NETWORK) // services
            put(ByteArray(10) { 0 })
            put(byteArrayOf(0xff.toByte(), 0xff.toByte())) // IPv4-mapped IPv6 prefix
            put(addr?.address ?: ByteArray(4) { 0 })
            order(ByteOrder.BIG_ENDIAN)
            putShort(network.port.toShort())
        }.array()

    private fun encodeVarInt(value: Long): ByteArray = when {
        value < 0xfd -> byteArrayOf(value.toByte())
        value <= 0xffff -> ByteArray(3).also {
            it[0] = 0xfd.toByte()
            ByteBuffer.wrap(it, 1, 2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort())
        }
        else -> ByteArray(5).also {
            it[0] = 0xfe.toByte()
            ByteBuffer.wrap(it, 1, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(value.toInt())
        }
    }

    private fun readVarInt(buf: ByteBuffer): Long {
        return when (val b = buf.get().toInt() and 0xff) {
            0xfd -> buf.short.toLong() and 0xffff
            0xfe -> buf.int.toLong() and 0xffffffff
            else -> b.toLong()
        }
    }

    private fun doubleShaSha256(data: ByteArray): ByteArray {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        return md.digest(md.digest(data))
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val IO_TIMEOUT_MS = 8_000
        // Must be >= the node's minimum accepted protocol version or the peer
        // rejects the handshake and disconnects.  Zebra's floor is the NU6.2
        // version (170_150) on mainnet, testnet, and regtest
        // (INITIAL_MIN_NETWORK_PROTOCOL_VERSION → min_specified_for_upgrade(Nu6_2)).
        // Keep this at or above the current mainnet activation version.
        private const val PROTOCOL_VERSION = 170_150
        private const val NODE_NETWORK = 1L
        private const val MAX_HANDSHAKE_MSGS = 10
        private const val MAX_MESSAGE_BYTES = 4_000_000
        private const val USER_AGENT = "/zodl-wallet:1.0/"
    }
}

/**
 * Zcash network configuration for P2P connections.
 */
enum class ZcashP2PNetwork(
    val magic: ByteArray,
    val port: Int,
    val dnsSeeds: List<String>
) {
    MAINNET(
        magic = byteArrayOf(0x24.toByte(), 0xe9.toByte(), 0x27.toByte(), 0x64.toByte()),
        port = 8233,
        dnsSeeds = listOf(
            "dnsseed.z.cash",
            "dnsseed.str4d.xyz",
            "mainnet.seeder.zfnd.org",
            "mainnet.seeder.shieldedinfra.net"
        )
    ),
    TESTNET(
        magic = byteArrayOf(0xfa.toByte(), 0x1a.toByte(), 0xf9.toByte(), 0xbf.toByte()),
        port = 18233,
        dnsSeeds = listOf(
            "dnsseed.testnet.z.cash",
            "testnet.seeder.zfnd.org"
        )
    )
}
