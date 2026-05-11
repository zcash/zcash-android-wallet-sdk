package cash.z.ecc.android.sdk.internal

import cash.z.ecc.android.sdk.ext.fromHex
import cash.z.ecc.android.sdk.internal.jni.VotingProofProgressCallback
import cash.z.ecc.android.sdk.internal.jni.VotingRustBackend
import cash.z.ecc.android.sdk.internal.model.voting.JniBundleSetupResult
import cash.z.ecc.android.sdk.internal.model.voting.JniRoundState
import cash.z.ecc.android.sdk.internal.model.voting.JniRoundSummary
import cash.z.ecc.android.sdk.internal.model.voting.JniVoteRecord
import cash.z.ecc.android.sdk.internal.model.voting.JniVotingHotkey
import org.json.JSONObject

private const val PROTOCOL_FIELD_BYTES = 32
private const val PCZT_SIGHASH_BYTES = PROTOCOL_FIELD_BYTES

@Suppress("TooManyFunctions", "LongParameterList")
internal class TypesafeVotingBackendImpl : TypesafeVotingBackend {
    private val rustBackendLazy =
        SuspendingLazy<Unit, VotingRustBackend> {
            VotingRustBackend.new()
        }

    override suspend fun computeShareNullifier(
        voteCommitment: ByteArray,
        shareIndex: Int,
        blind: ByteArray
    ): ByteArray =
        rustBackend().computeShareNullifier(voteCommitment, shareIndex, blind)

    override suspend fun openVotingDb(dbPath: String, walletId: String): TypesafeVotingDb =
        TypesafeVotingDbImpl(rustBackend().openVotingDb(dbPath, walletId))

    override suspend fun computeBundleSetup(notesJson: String): JniBundleSetupResult =
        rustBackend().computeBundleSetup(notesJson)

    override suspend fun warmProvingCaches() =
        rustBackend().warmProvingCaches()

    override suspend fun extractPcztSighash(pcztBytes: ByteArray): ByteArray =
        rustBackend().extractPcztSighash(pcztBytes)

    override suspend fun extractSpendAuthSig(
        signedPcztBytes: ByteArray,
        actionIndex: Int
    ): ByteArray =
        rustBackend().extractSpendAuthSig(signedPcztBytes, actionIndex)

    private suspend fun rustBackend() = rustBackendLazy.getInstance(Unit)
}

@Suppress("TooManyFunctions", "LongParameterList")
private class TypesafeVotingDbImpl(
    private val votingDb: VotingRustBackend.VotingDb
) : TypesafeVotingDb {
    override suspend fun close() = votingDb.close()

    override suspend fun initRound(
        roundId: String,
        snapshotHeight: Long,
        eaPK: ByteArray,
        ncRoot: ByteArray,
        nullifierIMTRoot: ByteArray,
        sessionJson: String?
    ) = votingDb.initRound(
        roundId,
        snapshotHeight,
        eaPK,
        ncRoot,
        nullifierIMTRoot,
        sessionJson
    )

    override suspend fun getRoundState(roundId: String): JniRoundState? =
        votingDb.getRoundState(roundId)

    override suspend fun listRounds(): List<JniRoundSummary> =
        votingDb.listRounds().asList()

    override suspend fun getBundleCount(roundId: String): Int =
        votingDb.getBundleCount(roundId)

    override suspend fun getVotes(roundId: String): List<JniVoteRecord> =
        votingDb.getVotes(roundId).asList()

    override suspend fun clearRound(roundId: String) =
        votingDb.clearRound(roundId)

    override suspend fun deleteSkippedBundles(
        roundId: String,
        keepCount: Int
    ): Long = votingDb.deleteSkippedBundles(roundId, keepCount)

    override suspend fun setupBundles(
        roundId: String,
        notesJson: String
    ): JniBundleSetupResult =
        votingDb.setupBundles(roundId, notesJson)

    override suspend fun generateHotkey(
        roundId: String,
        seed: ByteArray
    ): JniVotingHotkey =
        votingDb.generateHotkey(roundId, seed)

    override suspend fun buildGovernancePczt(
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
    ): GovernancePcztResult =
        JSONObject(
            votingDb.buildGovernancePcztJson(
                roundId,
                bundleIndex,
                ufvk,
                networkId,
                accountIndex,
                notesJson,
                walletSeed,
                seedFingerprint,
                roundName,
                addressIndex
            )
        ).toGovernancePcztResult()

    override suspend fun storeWitnesses(
        roundId: String,
        bundleIndex: Int,
        witnessesJson: String
    ) = votingDb.storeWitnesses(roundId, bundleIndex, witnessesJson)

    override suspend fun precomputeDelegationPir(
        roundId: String,
        bundleIndex: Int,
        pirServerUrl: String,
        networkId: Int,
        notesJson: String
    ): DelegationPirPrecomputeResult =
        JSONObject(
            votingDb.precomputeDelegationPirJson(
                roundId,
                bundleIndex,
                pirServerUrl,
                networkId,
                notesJson
            )
        ).toDelegationPirPrecomputeResult()

    override suspend fun buildAndProveDelegation(
        roundId: String,
        bundleIndex: Int,
        pirServerUrl: String,
        networkId: Int,
        notesJson: String,
        walletSeed: ByteArray,
        accountIndex: Int,
        addressIndex: Int,
        proofProgress: ((Double) -> Unit)?
    ): DelegationProofResult =
        JSONObject(
            votingDb.buildAndProveDelegationJson(
                roundId,
                bundleIndex,
                pirServerUrl,
                networkId,
                notesJson,
                walletSeed,
                accountIndex,
                addressIndex,
                proofProgress?.asVotingProgressCallback()
            )
        ).toDelegationProofResult()

}

private fun JSONObject.getCheckedInt(name: String): Int =
    Math.toIntExact(getLong(name))

internal fun JSONObject.toGovernancePcztResult() =
    GovernancePcztResult(
        pcztBytes = getHexBytes("pczt_bytes"),
        rk = getHexBytes("rk", PROTOCOL_FIELD_BYTES),
        sighash = getHexBytes("pczt_sighash", PCZT_SIGHASH_BYTES),
        actionIndex = getCheckedInt("action_index")
    )

internal fun JSONObject.toDelegationPirPrecomputeResult() =
    DelegationPirPrecomputeResult(
        cachedCount = getLong("cached_count"),
        fetchedCount = getLong("fetched_count")
    )

internal fun JSONObject.toDelegationProofResult() =
    DelegationProofResult(
        proof = getHexBytes("proof"),
        publicInputs =
            getJSONArray("public_inputs").toHexByteArrayList(
                "public_inputs",
                PROTOCOL_FIELD_BYTES
            ),
        nfSigned = getHexBytes("nf_signed", PROTOCOL_FIELD_BYTES),
        cmxNew = getHexBytes("cmx_new", PROTOCOL_FIELD_BYTES),
        govNullifiers =
            getJSONArray("gov_nullifiers").toHexByteArrayList(
                "gov_nullifiers",
                PROTOCOL_FIELD_BYTES
            ),
        vanComm = getHexBytes("van_comm", PROTOCOL_FIELD_BYTES),
        rk = getHexBytes("rk", PROTOCOL_FIELD_BYTES)
    )

private fun JSONArray.toHexByteArrayList(
    name: String,
    expectedElementSize: Int
): List<ByteArray> =
    (0 until length()).map { index ->
        getString(index).fromHex().also { bytes ->
            require(bytes.size == expectedElementSize) {
                "$name[$index] must be $expectedElementSize bytes, got ${bytes.size}"
            }
        }
    }

private fun ((Double) -> Unit).asVotingProgressCallback() =
    VotingProofProgressCallback { progress -> invoke(progress) }

private fun JSONObject.getHexBytes(
    name: String,
    expectedSize: Int? = null
): ByteArray {
    val bytes = getString(name).fromHex()

    require(expectedSize == null || bytes.size == expectedSize) {
        "$name must be $expectedSize bytes, got ${bytes.size}"
    }

    return bytes
}
