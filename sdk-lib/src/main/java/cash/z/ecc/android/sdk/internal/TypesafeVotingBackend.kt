package cash.z.ecc.android.sdk.internal

import cash.z.ecc.android.sdk.internal.model.voting.FfiRoundState
import cash.z.ecc.android.sdk.internal.model.voting.FfiRoundSummary

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

    suspend fun getRoundState(roundId: String): FfiRoundState?

    suspend fun listRounds(): List<FfiRoundSummary>

    suspend fun getVotes(roundId: String): List<VoteRecord>

    suspend fun clearRound(roundId: String)

    suspend fun deleteSkippedBundles(
        roundId: String,
        keepCount: Int
    ): Long
}

data class VoteRecord(
    val proposalId: Int,
    val bundleIndex: Int,
    val choice: Int,
    val submitted: Boolean
)
