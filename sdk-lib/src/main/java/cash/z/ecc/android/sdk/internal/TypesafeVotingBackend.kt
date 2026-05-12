package cash.z.ecc.android.sdk.internal

import cash.z.ecc.android.sdk.internal.model.voting.JniRoundState
import cash.z.ecc.android.sdk.internal.model.voting.JniRoundSummary
import cash.z.ecc.android.sdk.internal.model.voting.JniVoteRecord

@Suppress("TooManyFunctions", "LongParameterList")
interface TypesafeVotingBackend {
    suspend fun openVotingDb(dbPath: String, walletId: String): TypesafeVotingDb
}

@Suppress("TooManyFunctions", "LongParameterList")
interface TypesafeVotingDb {
    suspend fun close()

    suspend fun initRound(
        roundId: String,
        snapshotHeight: Long,
        eaPK: ByteArray,
        ncRoot: ByteArray,
        nullifierIMTRoot: ByteArray,
        sessionJson: String?
    )

    suspend fun getRoundState(roundId: String): JniRoundState?

    suspend fun listRounds(): List<JniRoundSummary>

    suspend fun getVotes(roundId: String): List<JniVoteRecord>

    suspend fun clearRound(roundId: String)

    suspend fun deleteSkippedBundles(
        roundId: String,
        keepCount: Int
    ): Long
}
