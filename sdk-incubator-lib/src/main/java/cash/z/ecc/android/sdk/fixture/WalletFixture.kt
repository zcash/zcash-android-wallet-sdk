package cash.z.ecc.android.sdk.fixture

import cash.z.ecc.android.sdk.model.Account
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.ZcashNetwork
import java.util.UUID

/**
 * Provides two default wallets, making it easy to test sending funds back and forth between them.
 */
sealed class WalletFixture {
    abstract val seedPhrase: String

    abstract val accounts: List<Account>

    abstract fun getBirthday(zcashNetwork: ZcashNetwork): BlockHeight

    abstract fun getAddresses(zcashNetwork: ZcashNetwork): Addresses

    @Suppress("MaxLineLength")
    data object Ben : WalletFixture() {
        override val accounts: List<Account>
            get() = listOf(AccountFixture.new(accountUuid = UUID.fromString("52175368-821a-4664-8a7c-6a75d850f71c")))

        override val seedPhrase: String
            get() =
                "kitchen renew wide common vague fold vacuum tilt amazing pear square gossip jewel month tree" +
                    " shock scan alpha just spot fluid toilet view dinner"

        override fun getBirthday(zcashNetwork: ZcashNetwork) = lastKnownBirthday(zcashNetwork)

        @Suppress("ktlint:standard:max-line-length")
        override fun getAddresses(zcashNetwork: ZcashNetwork) =
            when (zcashNetwork.id) {
                ZcashNetwork.ID_TESTNET -> {
                    Addresses(
                        unified =
                            "utest1vergg5jkp4xy8sqfasw6s5zkdpnxvfxlxh35uuc3me7dp596y2r05t6dv9htwe3pf8ksrfr8ksca2lskzjanqtl8uqp5vln3zyy246ejtx86vqftp73j7jg9099jxafyjhfm6u956j3",
                        sapling =
                            "ztestsapling17mg40levjezevuhdp5pqrd52zere7r7vrjgdwn5sj4xsqtm20euwahv9anxmwr3y3kmwu2syhnf",
                        transparent = "tmP3uLtGx5GPddkq8a6ddmXhqJJ3vy6tpTE"
                    )
                }

                ZcashNetwork.ID_MAINNET -> {
                    Addresses(
                        unified =
                            "u1lmy8anuylj33arxh3sx7ysq54tuw7zehsv6pdeeaqlrhkjhm3uvl9egqxqfd7hcsp3mszp6jxxx0gsw0ldp5wyu95r4mfzlueh8h5xhrjqgz7xtxp3hvw45dn4gfrz5j54ryg6reyf0",
                        sapling =
                            "zs1t06xldkqkayhp0lj98kunuq6gz3md0lw3r7q2x82rc94dy8z3hsjhuh6smpnlg9c2za3sq34w5m",
                        transparent = "t1JP7PHu72xHztsZiwH6cye4yvC9Prb3EvQ"
                    )
                }

                else -> {
                    error("Unknown network $zcashNetwork")
                }
            }
    }

    @Suppress("MaxLineLength")
    data object Alice : WalletFixture() {
        override val accounts: List<Account>
            get() = listOf(AccountFixture.new(accountUuid = UUID.fromString("8a204240-73a5-4e7a-93c2-a6e05711a000")))

        override val seedPhrase: String
            get() =
                "wish puppy smile loan doll curve hole maze file ginger hair nose key relax knife witness cannon" +
                    " grab despair throw review deal slush frame"

        override fun getBirthday(zcashNetwork: ZcashNetwork) = lastKnownBirthday(zcashNetwork)

        @Suppress("ktlint:standard:max-line-length")
        override fun getAddresses(zcashNetwork: ZcashNetwork) =
            when (zcashNetwork.id) {
                ZcashNetwork.ID_TESTNET -> {
                    Addresses(
                        unified =
                            "utest16zd8zfx6n6few7mjsjpn6qtn8tlg6law7qnq33257855mdqekk7vru8lettx3vud4mh99elglddltmfjkduar69h7vy08h3xdq6zuls9pqq7quyuehjqwtthc3hfd8gshhw42dfr96e",
                        sapling =
                            "ztestsapling1zhqvuq8zdwa8nsnde7074kcfsat0w25n08jzuvz5skzcs6h9raxu898l48xwr8fmkny3zqqrgd9",
                        transparent = "tmCxJG72RWN66xwPtNgu4iKHpyysGrc7rEg"
                    )
                }

                ZcashNetwork.ID_MAINNET -> {
                    Addresses(
                        unified =
                            "u1czzc8jcl50svfezmfc9xsxnh63p374nptqplt0yw2uekr7v9wprp84y6esys6derp6uvdcq6x6ykjrkpdyhjzneq5ud78h6j68n63hewg7xp9fpneuh64wgzt3d7mh6zh3qpqapzlc4",
                        sapling =
                            "zs15tzaulx5weua5c7l47l4pku2pw9fzwvvnsp4y80jdpul0y3nwn5zp7tmkcclqaca3mdjqjkl7hx",
                        transparent = "t1duiEGg7b39nfQee3XaTY4f5McqfyJKhBi"
                    )
                }

                else -> {
                    error("Unknown network $zcashNetwork")
                }
            }
    }

    companion object {
        // These birthdays were the latest checkpoint at the time this was implemented
        // Moving these forward will improve testing time, while leaving old transactions behind
        @Suppress("MagicNumber")
        fun lastKnownBirthday(zcashNetwork: ZcashNetwork) =
            when (zcashNetwork.id) {
                ZcashNetwork.ID_TESTNET -> {
                    BlockHeight.new(2170000L)
                }

                ZcashNetwork.ID_MAINNET -> {
                    BlockHeight.new(1935000L)
                }

                else -> {
                    error("Unknown network $zcashNetwork")
                }
            }
    }
}

data class Addresses(
    val unified: String,
    val sapling: String,
    val transparent: String
)
