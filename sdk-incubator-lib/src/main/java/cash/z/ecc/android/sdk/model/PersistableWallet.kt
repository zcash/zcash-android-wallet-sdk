package cash.z.ecc.android.sdk.model

import android.app.Application
import cash.z.ecc.android.bip39.Mnemonics
import cash.z.ecc.android.bip39.toEntropy
import cash.z.ecc.android.sdk.WalletInitMode
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.VisibleForTesting
import org.json.JSONObject

/**
 * Represents everything needed to save and restore a wallet.
 *
 * @param network the network in which the wallet operates
 * @param endpoint the endpoint with which the wallet communicates
 * @param birthday the birthday of the wallet
 * @param seedPhrase the seed phrase of the wallet
 * @param walletInitMode required parameter with one of [WalletInitMode] values. Use [WalletInitMode.NewWallet] when
 * starting synchronizer for a newly created wallet. Or use [WalletInitMode.RestoreWallet] when restoring an existing
 * wallet that was created at some point in the past. Or use the last [WalletInitMode.ExistingWallet] type for a
 * wallet which is already initialized and needs follow-up block synchronization. Note that this parameter is NOT
 * persisted along with the rest of persistable wallet data.
 */
data class PersistableWallet(
    val network: ZcashNetwork,
    val endpoint: LightWalletEndpoint,
    val birthday: BlockHeight?,
    val seedPhrase: SeedPhrase,
    val walletInitMode: WalletInitMode
) {
    init {
        walletInitModeHolder = walletInitMode
    }

    /**
     * @return Wallet serialized to JSON format, suitable for long-term encrypted storage.
     *
     * Note: We're using a hand-crafted serializer so that we're less likely to have accidental
     * breakage from reflection or annotation based methods, and so that we can carefully manage versioning.
     **/
    fun toJson() =
        JSONObject().apply {
            put(KEY_VERSION, VERSION_2)
            put(KEY_NETWORK_ID, network.id)
            put(KEY_ENDPOINT_HOST, endpoint.host)
            put(KEY_ENDPOINT_PORT, endpoint.port)
            put(KEY_ENDPOINT_IS_SECURE, endpoint.isSecure)
            birthday?.let {
                put(KEY_BIRTHDAY, it.value)
            }
            put(KEY_SEED_PHRASE, seedPhrase.joinToString())
        }

    // For security, intentionally override the toString method to reduce risk of accidentally logging secrets
    override fun toString() = "PersistableWallet"

    /**
     * This is a safe alternative for the regular [toString] function that prints only non-sensitive parts
     */
    fun toSafeString() =
        "PersistableWallet:" +
            " network: $network," +
            " endpoint: $endpoint," +
            " birthday: $birthday," +
            " wallet mode: $walletInitMode"

    companion object {
        internal const val VERSION_1 = 1
        internal const val VERSION_2 = 2

        internal const val KEY_VERSION = "v"
        internal const val KEY_NETWORK_ID = "network_ID"
        internal const val KEY_ENDPOINT_HOST = "endpoint_host"
        internal const val KEY_ENDPOINT_PORT = "key_endpoint_port"
        internal const val KEY_ENDPOINT_IS_SECURE = "key_endpoint_is_secure"
        internal const val KEY_BIRTHDAY = "birthday"
        internal const val KEY_SEED_PHRASE = "seed_phrase"

        // Note: [walletInitMode] is excluded from the serialization to avoid persisting the wallet initialization mode
        // with the persistable wallet.
        private var walletInitModeHolder: WalletInitMode = WalletInitMode.ExistingWallet

        fun from(jsonObject: JSONObject): PersistableWallet {
            // Common parameters
            val network = getNetwork(jsonObject)
            val birthday = getBirthday(jsonObject)
            val seedPhrase = getSeedPhrase(jsonObject)
            // From version 2
            val endpoint: LightWalletEndpoint

            when (val version = getVersion(jsonObject)) {
                VERSION_1 -> {
                    endpoint = getLightWalletEndpointForNetwork(network)
                }

                VERSION_2 -> {
                    endpoint = getEndpoint(jsonObject)
                }

                else -> {
                    throw IllegalArgumentException("Unsupported version $version")
                }
            }

            return PersistableWallet(
                network = network,
                endpoint = endpoint,
                birthday = birthday,
                seedPhrase = SeedPhrase.new(seedPhrase),
                walletInitMode = walletInitModeHolder
            )
        }

        internal fun getVersion(jsonObject: JSONObject): Int = jsonObject.getInt(KEY_VERSION)

        internal fun getSeedPhrase(jsonObject: JSONObject): String = jsonObject.getString(KEY_SEED_PHRASE)

        internal fun getNetwork(jsonObject: JSONObject): ZcashNetwork {
            val networkId = jsonObject.getInt(KEY_NETWORK_ID)
            return ZcashNetwork.from(networkId)
        }

        internal fun getBirthday(jsonObject: JSONObject): BlockHeight? =
            if (jsonObject.has(KEY_BIRTHDAY)) {
                val birthdayBlockHeightLong = jsonObject.getLong(KEY_BIRTHDAY)
                BlockHeight.new(birthdayBlockHeightLong)
            } else {
                null
            }

        internal fun getEndpoint(jsonObject: JSONObject): LightWalletEndpoint =
            jsonObject.run {
                val host = getString(KEY_ENDPOINT_HOST)
                val port = getInt(KEY_ENDPOINT_PORT)
                val isSecure = getBoolean(KEY_ENDPOINT_IS_SECURE)
                LightWalletEndpoint(host, port, isSecure)
            }

        /**
         * @return A new PersistableWallet with a random seed phrase.
         *
         * @param zcashNetwork the network in which the wallet operates
         * @param endpoint the endpoint with which the wallet communicates
         * @param walletInitMode required parameter with one of [WalletInitMode] values. Use [WalletInitMode.NewWallet]
         * when starting synchronizer for a newly created wallet. Or use [WalletInitMode.RestoreWallet] when
         * restoring an existing wallet that was created at some point in the past. Or use the last [WalletInitMode
         * .ExistingWallet] type for a wallet which is already initialized and needs follow-up block synchronization.
         * Note that this parameter is NOT persisted along with the rest of persistable wallet data.
         */
        suspend fun new(
            application: Application,
            zcashNetwork: ZcashNetwork,
            endpoint: LightWalletEndpoint,
            walletInitMode: WalletInitMode
        ): PersistableWallet {
            val birthday = BlockHeight.ofLatestCheckpoint(application, zcashNetwork)

            val seedPhrase = newSeedPhrase()

            return PersistableWallet(
                zcashNetwork,
                endpoint,
                birthday,
                seedPhrase,
                walletInitMode
            )
        }

        /**
         * Note: this function is internal and allowed only for testing purposes.
         *
         * @return Wallet serialized to JSON format, suitable for long-term encrypted storage.
         */
        @VisibleForTesting
        internal fun toCustomJson(
            version: Int,
            network: ZcashNetwork,
            endpoint: LightWalletEndpoint?,
            birthday: BlockHeight?,
            seed: SeedPhrase
        ) = JSONObject().apply {
            put(KEY_VERSION, version)
            put(KEY_NETWORK_ID, network.id)
            endpoint?.let {
                put(KEY_ENDPOINT_HOST, it.host)
                put(KEY_ENDPOINT_PORT, it.port)
                put(KEY_ENDPOINT_IS_SECURE, it.isSecure)
            }
            birthday?.let {
                put(KEY_BIRTHDAY, it.value)
            }
            put(KEY_SEED_PHRASE, seed.joinToString())
        }
    }
}

// Using IO context because of https://github.com/zcash/kotlin-bip39/issues/13
private suspend fun newMnemonic() =
    withContext(Dispatchers.IO) {
        Mnemonics
            .MnemonicCode(
                cash.z.ecc.android.bip39.Mnemonics.WordCount.COUNT_24
                    .toEntropy()
            ).words
    }

private suspend fun newSeedPhrase() = SeedPhrase(newMnemonic().map { it.concatToString() })

/*
 * The following functions and variables are package private only and preserved to support backward compatibility for
 * [PersistableWallet] and testing purposes.
 */
internal fun getLightWalletEndpointForNetwork(zcashNetwork: ZcashNetwork): LightWalletEndpoint =
    when (zcashNetwork.id) {
        ZcashNetwork.Mainnet.id -> LightWalletEndpoint.Mainnet
        ZcashNetwork.Testnet.id -> LightWalletEndpoint.Testnet
        else -> error("Unknown network id: ${zcashNetwork.id}")
    }

private const val DEFAULT_PORT = 9067

internal val LightWalletEndpoint.Companion.Mainnet
    get() =
        LightWalletEndpoint(
            "mainnet.lightwalletd.com",
            DEFAULT_PORT,
            isSecure = true
        )

internal val LightWalletEndpoint.Companion.Testnet
    get() =
        LightWalletEndpoint(
            "lightwalletd.testnet.electriccoin.co",
            DEFAULT_PORT,
            isSecure = true
        )
