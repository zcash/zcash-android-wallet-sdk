package cash.z.ecc.android.sdk.internal

import cash.z.ecc.android.sdk.internal.model.voting.JniBundleSetupResult
import cash.z.ecc.android.sdk.internal.model.voting.JniRoundState
import cash.z.ecc.android.sdk.internal.model.voting.JniRoundSummary
import cash.z.ecc.android.sdk.internal.model.voting.JniVoteRecord
import cash.z.ecc.android.sdk.internal.model.voting.JniVotingHotkey

@Suppress("TooManyFunctions", "LongParameterList")
internal interface TypesafeVotingBackend {
    suspend fun openVotingDb(dbPath: String, walletId: String): TypesafeVotingDb

    suspend fun computeShareNullifier(
        voteCommitment: ByteArray,
        shareIndex: Int,
        blind: ByteArray
    ): ByteArray

    suspend fun computeBundleSetup(notesJson: String): JniBundleSetupResult

    suspend fun warmProvingCaches()

    suspend fun extractPcztSighash(pcztBytes: ByteArray): ByteArray

    suspend fun extractSpendAuthSig(
        signedPcztBytes: ByteArray,
        actionIndex: Int
    ): ByteArray
}

@Suppress("TooManyFunctions", "LongParameterList")
internal interface TypesafeVotingDb {
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

    suspend fun getBundleCount(roundId: String): Int

    suspend fun getVotes(roundId: String): List<JniVoteRecord>

    suspend fun clearRound(roundId: String)

    suspend fun deleteSkippedBundles(
        roundId: String,
        keepCount: Int
    ): Long

    suspend fun setupBundles(
        roundId: String,
        notesJson: String
    ): JniBundleSetupResult

    suspend fun generateHotkey(
        roundId: String,
        seed: ByteArray
    ): JniVotingHotkey

    suspend fun buildGovernancePczt(
        roundId: String,
        bundleIndex: Int,
        ufvk: String,
        networkId: Int,
        accountIndex: Int,
        notesJson: String,
        walletSeed: ByteArray,
        seedFingerprint: ByteArray,
        roundName: String,
        addressIndex: Int
    ): GovernancePcztResult
}

internal data class GovernancePcztResult(
    val pcztBytes: ByteArray,
    val rk: ByteArray,
    val sighash: ByteArray,
    val actionIndex: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GovernancePcztResult) return false
        return pcztBytes.contentEquals(other.pcztBytes) &&
            rk.contentEquals(other.rk) &&
            sighash.contentEquals(other.sighash) &&
            actionIndex == other.actionIndex
    }

    override fun hashCode(): Int {
        var result = pcztBytes.contentHashCode()
        result = 31 * result + rk.contentHashCode()
        result = 31 * result + sighash.contentHashCode()
        result = 31 * result + actionIndex
        return result
    }
}
