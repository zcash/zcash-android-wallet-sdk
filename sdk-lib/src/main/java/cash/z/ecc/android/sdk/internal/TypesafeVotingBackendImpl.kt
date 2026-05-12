package cash.z.ecc.android.sdk.internal

import cash.z.ecc.android.sdk.internal.jni.JNI_DELEGATION_PUBLIC_INPUT_COUNT
import cash.z.ecc.android.sdk.internal.jni.JNI_GOVERNANCE_NULLIFIER_COUNT
import cash.z.ecc.android.sdk.internal.jni.JNI_PROTOCOL_FIELD_BYTES_SIZE
import cash.z.ecc.android.sdk.internal.jni.JNI_SPEND_AUTH_SIG_BYTES_SIZE
import cash.z.ecc.android.sdk.internal.jni.VotingProofProgressCallback
import cash.z.ecc.android.sdk.internal.jni.VotingRustBackend
import cash.z.ecc.android.sdk.internal.model.voting.JniBundleSetupResult
import cash.z.ecc.android.sdk.internal.model.voting.JniDelegationPirPrecomputeResult
import cash.z.ecc.android.sdk.internal.model.voting.JniDelegationProofResult
import cash.z.ecc.android.sdk.internal.model.voting.JniDelegationSubmissionResult
import cash.z.ecc.android.sdk.internal.model.voting.JniGovernancePczt
import cash.z.ecc.android.sdk.internal.model.voting.JniNoteInfo
import cash.z.ecc.android.sdk.internal.model.voting.JniRoundState
import cash.z.ecc.android.sdk.internal.model.voting.JniRoundSummary
import cash.z.ecc.android.sdk.internal.model.voting.JniVoteRecord
import cash.z.ecc.android.sdk.internal.model.voting.JniVotingHotkey
import cash.z.ecc.android.sdk.internal.model.voting.JniWitnessData

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
        TypesafeVotingDbImpl(
            RustVotingDbBackend(rustBackend().openVotingDb(dbPath, walletId))
        )

    override suspend fun computeBundleSetup(notes: List<JniNoteInfo>): JniBundleSetupResult =
        rustBackend().computeBundleSetup(notes)

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
internal interface VotingDbBackend {
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

    suspend fun listRounds(): Array<JniRoundSummary>

    suspend fun getBundleCount(roundId: String): Int

    suspend fun getVotes(roundId: String): Array<JniVoteRecord>

    suspend fun clearRound(roundId: String)

    suspend fun deleteSkippedBundles(
        roundId: String,
        keepCount: Int
    ): Long

    suspend fun setupBundles(
        roundId: String,
        notes: List<JniNoteInfo>
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
        notes: List<JniNoteInfo>,
        walletSeed: ByteArray,
        seedFingerprint: ByteArray,
        roundName: String,
        addressIndex: Int
    ): JniGovernancePczt

    suspend fun storeWitnesses(
        roundId: String,
        bundleIndex: Int,
        notes: List<JniNoteInfo>,
        witnesses: List<JniWitnessData>
    )

    suspend fun precomputeDelegationPir(
        roundId: String,
        bundleIndex: Int,
        pirServerUrl: String,
        networkId: Int,
        notes: List<JniNoteInfo>
    ): JniDelegationPirPrecomputeResult

    suspend fun buildAndProveDelegation(
        roundId: String,
        bundleIndex: Int,
        pirServerUrl: String,
        networkId: Int,
        notes: List<JniNoteInfo>,
        walletSeed: ByteArray,
        accountIndex: Int,
        addressIndex: Int,
        proofProgress: VotingProofProgressCallback?
    ): JniDelegationProofResult

    suspend fun getDelegationSubmission(
        roundId: String,
        bundleIndex: Int,
        senderSeed: ByteArray,
        networkId: Int,
        accountIndex: Int
    ): JniDelegationSubmissionResult

    suspend fun getDelegationSubmissionWithKeystoneSig(
        roundId: String,
        bundleIndex: Int,
        keystoneSig: ByteArray,
        keystoneSighash: ByteArray
    ): JniDelegationSubmissionResult
}

@Suppress("TooManyFunctions", "LongParameterList")
private class RustVotingDbBackend(
    private val votingDb: VotingRustBackend.VotingDb
) : VotingDbBackend {
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

    override suspend fun listRounds(): Array<JniRoundSummary> =
        votingDb.listRounds()

    override suspend fun getBundleCount(roundId: String): Int =
        votingDb.getBundleCount(roundId)

    override suspend fun getVotes(roundId: String): Array<JniVoteRecord> =
        votingDb.getVotes(roundId)

    override suspend fun clearRound(roundId: String) =
        votingDb.clearRound(roundId)

    override suspend fun deleteSkippedBundles(
        roundId: String,
        keepCount: Int
    ): Long = votingDb.deleteSkippedBundles(roundId, keepCount)

    override suspend fun setupBundles(
        roundId: String,
        notes: List<JniNoteInfo>
    ): JniBundleSetupResult = votingDb.setupBundles(roundId, notes)

    override suspend fun generateHotkey(
        roundId: String,
        seed: ByteArray
    ): JniVotingHotkey = votingDb.generateHotkey(roundId, seed)

    override suspend fun buildGovernancePczt(
        roundId: String,
        bundleIndex: Int,
        ufvk: String,
        networkId: Int,
        accountIndex: Int,
        notes: List<JniNoteInfo>,
        walletSeed: ByteArray,
        seedFingerprint: ByteArray,
        roundName: String,
        addressIndex: Int
    ): JniGovernancePczt =
        votingDb.buildGovernancePczt(
            roundId,
            bundleIndex,
            ufvk,
            networkId,
            accountIndex,
            notes,
            walletSeed,
            seedFingerprint,
            roundName,
            addressIndex
        )

    override suspend fun storeWitnesses(
        roundId: String,
        bundleIndex: Int,
        notes: List<JniNoteInfo>,
        witnesses: List<JniWitnessData>
    ) = votingDb.storeWitnesses(roundId, bundleIndex, notes, witnesses)

    override suspend fun precomputeDelegationPir(
        roundId: String,
        bundleIndex: Int,
        pirServerUrl: String,
        networkId: Int,
        notes: List<JniNoteInfo>
    ): JniDelegationPirPrecomputeResult =
        votingDb.precomputeDelegationPir(
            roundId,
            bundleIndex,
            pirServerUrl,
            networkId,
            notes
        )

    override suspend fun buildAndProveDelegation(
        roundId: String,
        bundleIndex: Int,
        pirServerUrl: String,
        networkId: Int,
        notes: List<JniNoteInfo>,
        walletSeed: ByteArray,
        accountIndex: Int,
        addressIndex: Int,
        proofProgress: VotingProofProgressCallback?
    ): JniDelegationProofResult =
        votingDb.buildAndProveDelegation(
            roundId,
            bundleIndex,
            pirServerUrl,
            networkId,
            notes,
            walletSeed,
            accountIndex,
            addressIndex,
            proofProgress
        )

    override suspend fun getDelegationSubmission(
        roundId: String,
        bundleIndex: Int,
        senderSeed: ByteArray,
        networkId: Int,
        accountIndex: Int
    ): JniDelegationSubmissionResult =
        votingDb.getDelegationSubmission(
            roundId,
            bundleIndex,
            senderSeed,
            networkId,
            accountIndex
        )

    override suspend fun getDelegationSubmissionWithKeystoneSig(
        roundId: String,
        bundleIndex: Int,
        keystoneSig: ByteArray,
        keystoneSighash: ByteArray
    ): JniDelegationSubmissionResult =
        votingDb.getDelegationSubmissionWithKeystoneSig(
            roundId,
            bundleIndex,
            keystoneSig,
            keystoneSighash
        )
}

@Suppress("TooManyFunctions", "LongParameterList")
internal class TypesafeVotingDbImpl(
    private val votingDb: VotingDbBackend
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
        notes: List<JniNoteInfo>
    ): JniBundleSetupResult =
        votingDb.setupBundles(roundId, notes)

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
        notes: List<JniNoteInfo>,
        walletSeed: ByteArray,
        seedFingerprint: ByteArray,
        roundName: String,
        addressIndex: Int
    ): GovernancePcztResult =
        votingDb
            .buildGovernancePczt(
                roundId,
                bundleIndex,
                ufvk,
                networkId,
                accountIndex,
                notes,
                walletSeed,
                seedFingerprint,
                roundName,
                addressIndex
            ).toGovernancePcztResult()

    override suspend fun storeWitnesses(
        roundId: String,
        bundleIndex: Int,
        notes: List<JniNoteInfo>,
        witnesses: List<JniWitnessData>
    ) = votingDb.storeWitnesses(roundId, bundleIndex, notes, witnesses)

    override suspend fun precomputeDelegationPir(
        roundId: String,
        bundleIndex: Int,
        pirServerUrl: String,
        networkId: Int,
        notes: List<JniNoteInfo>
    ): DelegationPirPrecomputeResult =
        votingDb
            .precomputeDelegationPir(
                roundId,
                bundleIndex,
                pirServerUrl,
                networkId,
                notes
            ).toDelegationPirPrecomputeResult()

    override suspend fun buildAndProveDelegation(
        roundId: String,
        bundleIndex: Int,
        pirServerUrl: String,
        networkId: Int,
        notes: List<JniNoteInfo>,
        walletSeed: ByteArray,
        accountIndex: Int,
        addressIndex: Int,
        proofProgress: ((Double) -> Unit)?
    ): DelegationProofResult =
        votingDb
            .buildAndProveDelegation(
                roundId,
                bundleIndex,
                pirServerUrl,
                networkId,
                notes,
                walletSeed,
                accountIndex,
                addressIndex,
                proofProgress?.asVotingProgressCallback()
            ).toDelegationProofResult()

    override suspend fun getDelegationSubmission(
        roundId: String,
        bundleIndex: Int,
        senderSeed: ByteArray,
        networkId: Int,
        accountIndex: Int
    ): DelegationSubmissionResult =
        votingDb
            .getDelegationSubmission(
                roundId,
                bundleIndex,
                senderSeed,
                networkId,
                accountIndex
            ).toDelegationSubmissionResult()

    override suspend fun getDelegationSubmissionWithKeystoneSig(
        roundId: String,
        bundleIndex: Int,
        keystoneSig: ByteArray,
        keystoneSighash: ByteArray
    ): DelegationSubmissionResult =
        votingDb
            .getDelegationSubmissionWithKeystoneSig(
                roundId,
                bundleIndex,
                keystoneSig,
                keystoneSighash
            ).toDelegationSubmissionResult()
}

internal fun JniGovernancePczt.toGovernancePcztResult(): GovernancePcztResult {
    rk.requireByteArraySize("rk", JNI_PROTOCOL_FIELD_BYTES_SIZE)
    sighash.requireByteArraySize("sighash", JNI_PROTOCOL_FIELD_BYTES_SIZE)

    return GovernancePcztResult(
        pcztBytes = pcztBytes,
        rk = rk,
        sighash = sighash,
        actionIndex = actionIndex
    )
}

internal fun JniDelegationPirPrecomputeResult.toDelegationPirPrecomputeResult() =
    DelegationPirPrecomputeResult(
        cachedCount = cachedCount,
        fetchedCount = fetchedCount
    )

internal fun JniDelegationProofResult.toDelegationProofResult(): DelegationProofResult {
    proof.requireByteArrayNotEmpty("proof")
    publicInputs.requireByteArrayCount(
        "publicInputs",
        JNI_DELEGATION_PUBLIC_INPUT_COUNT
    )
    publicInputs.requireEachByteArraySize("publicInputs", JNI_PROTOCOL_FIELD_BYTES_SIZE)
    nfSigned.requireByteArraySize("nfSigned", JNI_PROTOCOL_FIELD_BYTES_SIZE)
    cmxNew.requireByteArraySize("cmxNew", JNI_PROTOCOL_FIELD_BYTES_SIZE)
    govNullifiers.requireByteArrayCount(
        "govNullifiers",
        JNI_GOVERNANCE_NULLIFIER_COUNT
    )
    govNullifiers.requireEachByteArraySize("govNullifiers", JNI_PROTOCOL_FIELD_BYTES_SIZE)
    vanComm.requireByteArraySize("vanComm", JNI_PROTOCOL_FIELD_BYTES_SIZE)
    rk.requireByteArraySize("rk", JNI_PROTOCOL_FIELD_BYTES_SIZE)

    return DelegationProofResult(
        proof = proof,
        publicInputs = publicInputs,
        nfSigned = nfSigned,
        cmxNew = cmxNew,
        govNullifiers = govNullifiers,
        vanComm = vanComm,
        rk = rk
    )
}

internal fun JniDelegationSubmissionResult.toDelegationSubmissionResult(): DelegationSubmissionResult {
    proof.requireByteArrayNotEmpty("proof")
    rk.requireByteArraySize("rk", JNI_PROTOCOL_FIELD_BYTES_SIZE)
    spendAuthSig.requireByteArraySize("spendAuthSig", JNI_SPEND_AUTH_SIG_BYTES_SIZE)
    sighash.requireByteArraySize("sighash", JNI_PROTOCOL_FIELD_BYTES_SIZE)
    nfSigned.requireByteArraySize("nfSigned", JNI_PROTOCOL_FIELD_BYTES_SIZE)
    cmxNew.requireByteArraySize("cmxNew", JNI_PROTOCOL_FIELD_BYTES_SIZE)
    govComm.requireByteArraySize("govComm", JNI_PROTOCOL_FIELD_BYTES_SIZE)
    govNullifiers.requireByteArrayCount(
        "govNullifiers",
        JNI_GOVERNANCE_NULLIFIER_COUNT
    )
    govNullifiers.requireEachByteArraySize("govNullifiers", JNI_PROTOCOL_FIELD_BYTES_SIZE)
    alpha.requireByteArraySize("alpha", JNI_PROTOCOL_FIELD_BYTES_SIZE)

    return DelegationSubmissionResult(
        proof = proof,
        rk = rk,
        spendAuthSig = spendAuthSig,
        sighash = sighash,
        nfSigned = nfSigned,
        cmxNew = cmxNew,
        govComm = govComm,
        govNullifiers = govNullifiers,
        alpha = alpha,
        voteRoundId = voteRoundId
    )
}

private fun ((Double) -> Unit).asVotingProgressCallback() =
    VotingProofProgressCallback { progress -> invoke(progress) }

private fun ByteArray.requireByteArraySize(name: String, expectedSize: Int) =
    require(size == expectedSize) {
        "$name must be $expectedSize bytes, got $size"
    }

private fun ByteArray.requireByteArrayNotEmpty(name: String) =
    require(isNotEmpty()) {
        "$name must not be empty"
    }

private fun List<ByteArray>.requireByteArrayCount(name: String, expectedCount: Int) =
    require(size == expectedCount) {
        "$name must contain $expectedCount entries, got $size"
    }

private fun List<ByteArray>.requireEachByteArraySize(name: String, expectedSize: Int) =
    forEachIndexed { index, bytes ->
        bytes.requireByteArraySize("$name[$index]", expectedSize)
    }
