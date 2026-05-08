package cash.z.ecc.android.sdk.internal.jni

import androidx.annotation.Keep
import cash.z.ecc.android.sdk.internal.model.voting.FfiRoundState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Keep
@Suppress("TooManyFunctions", "LongParameterList")
class VotingRustBackend private constructor() {
    @Throws(RuntimeException::class)
    fun computeShareNullifier(
        voteCommitment: ByteArray,
        shareIndex: Int,
        blind: ByteArray
    ): ByteArray = computeShareNullifierNative(voteCommitment, shareIndex, blind)

    suspend fun openVotingDb(dbPath: String, walletId: String): VotingDb =
        withContext(Dispatchers.IO) {
            openVotingDbNative(dbPath, walletId).let { dbHandle ->
                check(dbHandle != 0L) {
                    "openVotingDb failed for dbPath=$dbPath"
                }
                VotingDb(dbHandle)
            }
        }

    @Suppress("TooManyFunctions", "LongParameterList")
    class VotingDb internal constructor(
        private var dbHandle: Long?
    ) {
        private val accessMutex = Mutex()

        suspend fun close() {
            accessMutex.withLock {
                dbHandle?.let { handle ->
                    withContext(Dispatchers.IO) {
                        closeVotingDbNative(handle)
                    }
                    dbHandle = null
                }
            }
        }

        @Throws(RuntimeException::class)
        suspend fun initRound(
            roundId: String,
            snapshotHeight: Long,
            eaPK: ByteArray,
            ncRoot: ByteArray,
            nullifierIMTRoot: ByteArray,
            sessionJson: String?
        ) = withHandle { handle ->
            initRoundNative(
                handle,
                roundId,
                snapshotHeight,
                eaPK,
                ncRoot,
                nullifierIMTRoot,
                sessionJson
            )
        }

        @Throws(RuntimeException::class)
        suspend fun getRoundState(roundId: String): FfiRoundState? =
            withHandle { handle -> getRoundStateNative(handle, roundId) }

        @Throws(RuntimeException::class)
        suspend fun listRoundsJson(): String =
            withHandle { handle -> listRoundsJsonNative(handle) }

        @Throws(RuntimeException::class)
        suspend fun getVotesJson(roundId: String): String =
            withHandle { handle -> getVotesJsonNative(handle, roundId) }

        @Throws(RuntimeException::class)
        suspend fun clearRound(roundId: String) =
            withHandle { handle -> clearRoundNative(handle, roundId) }

        @Throws(RuntimeException::class)
        suspend fun deleteSkippedBundles(
            roundId: String,
            keepCount: Int
        ): Long =
            withHandle { handle ->
                deleteSkippedBundlesNative(handle, roundId, keepCount).also { deletedRows ->
                    check(deletedRows >= 0) {
                        "deleteSkippedBundles failed for roundId=$roundId keepCount=$keepCount"
                    }
                }
            }

        private suspend fun <T> withHandle(block: (Long) -> T): T =
            accessMutex.withLock {
                val handle =
                    checkNotNull(dbHandle) {
                        "Voting DB handle is closed"
                    }
                withContext(Dispatchers.IO) {
                    block(handle)
                }
            }
    }

    companion object {
        suspend fun new(): VotingRustBackend {
            RustBackend.loadLibrary()

            return VotingRustBackend()
        }

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun computeShareNullifierNative(
            voteCommitment: ByteArray,
            shareIndex: Int,
            blind: ByteArray
        ): ByteArray

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun openVotingDbNative(dbPath: String, walletId: String): Long

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun closeVotingDbNative(dbHandle: Long)

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun initRoundNative(
            dbHandle: Long,
            roundId: String,
            snapshotHeight: Long,
            eaPK: ByteArray,
            ncRoot: ByteArray,
            nullifierIMTRoot: ByteArray,
            sessionJson: String?
        )

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun getRoundStateNative(dbHandle: Long, roundId: String): FfiRoundState?

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun listRoundsJsonNative(dbHandle: Long): String

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun getVotesJsonNative(dbHandle: Long, roundId: String): String

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun clearRoundNative(dbHandle: Long, roundId: String)

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun deleteSkippedBundlesNative(
            dbHandle: Long,
            roundId: String,
            keepCount: Int
        ): Long
    }
}
