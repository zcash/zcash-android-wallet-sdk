package cash.z.ecc.android.sdk.internal

import cash.z.ecc.android.sdk.internal.jni.JNI_DELEGATION_PUBLIC_INPUT_COUNT
import cash.z.ecc.android.sdk.internal.jni.JNI_GOVERNANCE_NULLIFIER_COUNT
import cash.z.ecc.android.sdk.internal.jni.JNI_PROTOCOL_FIELD_BYTES_SIZE
import cash.z.ecc.android.sdk.internal.jni.JNI_SPEND_AUTH_SIG_BYTES_SIZE
import cash.z.ecc.android.sdk.internal.jni.VotingProofProgressCallback
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
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@Suppress("LongMethod", "LongParameterList", "MagicNumber", "TooManyFunctions")
class TypesafeVotingBackendImplTest {
    @Test
    fun delegation_proof_result_checks_public_input_count() {
        val error =
            assertFailsWith<IllegalArgumentException> {
                jniDelegationProofResult(publicInputs = fieldElements(count = 1))
                    .toDelegationProofResult()
            }

        assertTrue(error.message.orEmpty().contains("publicInputs"))
    }

    @Test
    fun delegation_proof_result_checks_public_input_element_lengths() {
        val error =
            assertFailsWith<IllegalArgumentException> {
                jniDelegationProofResult(
                    publicInputs =
                        fieldElements(
                            count = JNI_DELEGATION_PUBLIC_INPUT_COUNT,
                            size = JNI_PROTOCOL_FIELD_BYTES_SIZE - 1
                        )
                ).toDelegationProofResult()
            }

        assertTrue(error.message.orEmpty().contains("publicInputs[0]"))
    }

    @Test
    fun delegation_submission_result_checks_gov_nullifier_count() {
        val error =
            assertFailsWith<IllegalArgumentException> {
                jniDelegationSubmissionResult(govNullifiers = fieldElements(count = 1))
                    .toDelegationSubmissionResult()
            }

        assertTrue(error.message.orEmpty().contains("govNullifiers"))
    }

    @Test
    fun delegation_submission_result_accepts_expected_shape() {
        val result =
            jniDelegationSubmissionResult(
                proof = ByteArray(PROOF_BYTES) { 3 },
                govNullifiers =
                    fieldElements(
                        count = JNI_GOVERNANCE_NULLIFIER_COUNT,
                        byteValue = 4
                    )
            ).toDelegationSubmissionResult()

        assertEquals(PROOF_BYTES, result.proof.size)
        assertEquals(JNI_SPEND_AUTH_SIG_BYTES_SIZE, result.spendAuthSig.size)
        assertEquals(JNI_GOVERNANCE_NULLIFIER_COUNT, result.govNullifiers.size)
        assertContentEquals(
            ByteArray(JNI_PROTOCOL_FIELD_BYTES_SIZE) { 4 },
            result.govNullifiers.first()
        )
    }

    @Test
    fun jni_delegation_results_compare_byte_contents() {
        val proof = jniDelegationProofResult()
        val equalProof = jniDelegationProofResult()
        val submission = jniDelegationSubmissionResult()
        val equalSubmission = jniDelegationSubmissionResult()

        assertEquals(proof, equalProof)
        assertEquals(proof.hashCode(), equalProof.hashCode())
        assertEquals(submission, equalSubmission)
        assertEquals(submission.hashCode(), equalSubmission.hashCode())
    }

    @Test
    fun delegation_methods_forward_arguments_and_map_results() =
        runTest {
            val proofJniResult =
                jniDelegationProofResult(
                    proof = ByteArray(PROOF_BYTES) { 11 },
                    publicInputs = fieldElements(JNI_DELEGATION_PUBLIC_INPUT_COUNT, 12),
                    nfSigned = field(13),
                    cmxNew = field(14),
                    govNullifiers = fieldElements(JNI_GOVERNANCE_NULLIFIER_COUNT, 15),
                    vanComm = field(16),
                    rk = field(17)
                )
            val submissionJniResult =
                jniDelegationSubmissionResult(
                    proof = ByteArray(PROOF_BYTES) { 21 },
                    rk = field(22),
                    spendAuthSig = ByteArray(JNI_SPEND_AUTH_SIG_BYTES_SIZE) { 23 },
                    sighash = field(24),
                    nfSigned = field(25),
                    cmxNew = field(26),
                    govComm = field(27),
                    govNullifiers = fieldElements(JNI_GOVERNANCE_NULLIFIER_COUNT, 28),
                    alpha = field(29),
                    voteRoundId = "round-submission"
                )
            val keystoneJniResult =
                jniDelegationSubmissionResult(
                    proof = ByteArray(PROOF_BYTES) { 31 },
                    voteRoundId = "round-keystone"
                )
            val backend =
                RecordingVotingDbBackend(
                    proofResult = proofJniResult,
                    submissionResult = submissionJniResult,
                    keystoneSubmissionResult = keystoneJniResult
                )
            val db = TypesafeVotingDbImpl(backend)
            val walletSeed = byteArrayOf(1, 2, 3)
            val senderSeed = byteArrayOf(4, 5, 6)
            val keystoneSig = byteArrayOf(7, 8)
            val keystoneSighash = byteArrayOf(9, 10)
            val notes = listOf(jniNoteInfo())
            val witnesses = listOf(jniWitnessData())
            var progressValue: Double? = null

            db.storeWitnesses("round-1", 2, notes, witnesses)
            assertEquals("round-1", backend.storeWitnessesRoundId)
            assertEquals(2, backend.storeWitnessesBundleIndex)
            assertEquals(notes, backend.storeWitnessesNotes)
            assertEquals(witnesses, backend.storeWitnessesWitnesses)

            val precompute =
                db.precomputeDelegationPir(
                    roundId = "round-2",
                    bundleIndex = 3,
                    pirServerUrl = "https://pir.example",
                    networkId = 0,
                    notes = notes
                )
            assertEquals(11L, precompute.cachedCount)
            assertEquals(12L, precompute.fetchedCount)
            assertEquals("round-2", backend.precomputeRoundId)
            assertEquals(3, backend.precomputeBundleIndex)
            assertEquals("https://pir.example", backend.precomputePirServerUrl)
            assertEquals(0, backend.precomputeNetworkId)
            assertEquals(notes, backend.precomputeNotes)

            val proof =
                db.buildAndProveDelegation(
                    roundId = "round-3",
                    bundleIndex = 4,
                    pirServerUrl = "https://pir.example",
                    networkId = 1,
                    notes = notes,
                    walletSeed = walletSeed,
                    accountIndex = 5,
                    addressIndex = 6
                ) { progress ->
                    progressValue = progress
                }
            assertEquals("round-3", backend.buildAndProveRoundId)
            assertEquals(4, backend.buildAndProveBundleIndex)
            assertEquals("https://pir.example", backend.buildAndProvePirServerUrl)
            assertEquals(1, backend.buildAndProveNetworkId)
            assertEquals(notes, backend.buildAndProveNotes)
            assertContentEquals(walletSeed, backend.buildAndProveWalletSeed)
            assertEquals(5, backend.buildAndProveAccountIndex)
            assertEquals(6, backend.buildAndProveAddressIndex)
            assertNotNull(backend.buildAndProveProgress).onProgress(0.75)
            assertEquals(0.75, progressValue)
            assertContentEquals(field(13), proof.nfSigned)
            assertContentEquals(field(17), proof.rk)

            val submission =
                db.getDelegationSubmission(
                    roundId = "round-4",
                    bundleIndex = 7,
                    senderSeed = senderSeed,
                    networkId = 0,
                    accountIndex = 8
                )
            assertEquals("round-4", backend.submissionRoundId)
            assertEquals(7, backend.submissionBundleIndex)
            assertContentEquals(senderSeed, backend.submissionSenderSeed)
            assertEquals(0, backend.submissionNetworkId)
            assertEquals(8, backend.submissionAccountIndex)
            assertContentEquals(field(22), submission.rk)
            assertContentEquals(field(29), submission.alpha)
            assertEquals("round-submission", submission.voteRoundId)

            val keystoneSubmission =
                db.getDelegationSubmissionWithKeystoneSig(
                    roundId = "round-5",
                    bundleIndex = 9,
                    keystoneSig = keystoneSig,
                    keystoneSighash = keystoneSighash
                )
            assertEquals("round-5", backend.keystoneRoundId)
            assertEquals(9, backend.keystoneBundleIndex)
            assertContentEquals(keystoneSig, backend.keystoneSig)
            assertContentEquals(keystoneSighash, backend.keystoneSighash)
            assertEquals("round-keystone", keystoneSubmission.voteRoundId)
        }

    private fun jniDelegationProofResult(
        proof: ByteArray = ByteArray(PROOF_BYTES) { 3 },
        publicInputs: List<ByteArray> =
            fieldElements(
                count = JNI_DELEGATION_PUBLIC_INPUT_COUNT,
                byteValue = 1
            ),
        govNullifiers: List<ByteArray> =
            fieldElements(
                count = JNI_GOVERNANCE_NULLIFIER_COUNT,
                byteValue = 2
            ),
        nfSigned: ByteArray = field(4),
        cmxNew: ByteArray = field(5),
        vanComm: ByteArray = field(6),
        rk: ByteArray = field(7)
    ) = JniDelegationProofResult(
        proof = proof,
        publicInputs = publicInputs,
        nfSigned = nfSigned,
        cmxNew = cmxNew,
        govNullifiers = govNullifiers,
        vanComm = vanComm,
        rk = rk
    )

    private fun jniDelegationSubmissionResult(
        proof: ByteArray = ByteArray(PROOF_BYTES) { 3 },
        rk: ByteArray = field(7),
        spendAuthSig: ByteArray = ByteArray(JNI_SPEND_AUTH_SIG_BYTES_SIZE) { 8 },
        sighash: ByteArray = field(9),
        nfSigned: ByteArray = field(4),
        cmxNew: ByteArray = field(5),
        govComm: ByteArray = field(6),
        govNullifiers: List<ByteArray> =
            fieldElements(
                count = JNI_GOVERNANCE_NULLIFIER_COUNT,
                byteValue = 2
            ),
        alpha: ByteArray = field(10),
        voteRoundId: String = "round-1"
    ) = JniDelegationSubmissionResult(
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

    private fun field(byteValue: Int) =
        ByteArray(JNI_PROTOCOL_FIELD_BYTES_SIZE) { byteValue.toByte() }

    private fun fieldElements(
        count: Int,
        byteValue: Int = 1,
        size: Int = JNI_PROTOCOL_FIELD_BYTES_SIZE
    ) = List(count) { ByteArray(size) { byteValue.toByte() } }

    private fun jniNoteInfo() =
        JniNoteInfo(
            commitment = field(1),
            nullifier = field(2),
            value = 10L,
            position = 0L,
            diversifier = ByteArray(11),
            rho = field(3),
            rseed = field(4),
            scope = 0,
            ufvk = "ufvk"
        )

    private fun jniWitnessData() =
        JniWitnessData(
            noteCommitment = field(1),
            position = 0L,
            root = field(5),
            authPath = fieldElements(32)
        )

    private class RecordingVotingDbBackend(
        private val proofResult: JniDelegationProofResult,
        private val submissionResult: JniDelegationSubmissionResult,
        private val keystoneSubmissionResult: JniDelegationSubmissionResult
    ) : VotingDbBackend {
        var storeWitnessesRoundId: String? = null
        var storeWitnessesBundleIndex: Int? = null
        var storeWitnessesNotes: List<JniNoteInfo>? = null
        var storeWitnessesWitnesses: List<JniWitnessData>? = null
        var precomputeRoundId: String? = null
        var precomputeBundleIndex: Int? = null
        var precomputePirServerUrl: String? = null
        var precomputeNetworkId: Int? = null
        var precomputeNotes: List<JniNoteInfo>? = null
        var buildAndProveRoundId: String? = null
        var buildAndProveBundleIndex: Int? = null
        var buildAndProvePirServerUrl: String? = null
        var buildAndProveNetworkId: Int? = null
        var buildAndProveNotes: List<JniNoteInfo>? = null
        var buildAndProveWalletSeed: ByteArray = ByteArray(0)
        var buildAndProveAccountIndex: Int? = null
        var buildAndProveAddressIndex: Int? = null
        var buildAndProveProgress: VotingProofProgressCallback? = null
        var submissionRoundId: String? = null
        var submissionBundleIndex: Int? = null
        var submissionSenderSeed: ByteArray = ByteArray(0)
        var submissionNetworkId: Int? = null
        var submissionAccountIndex: Int? = null
        var keystoneRoundId: String? = null
        var keystoneBundleIndex: Int? = null
        var keystoneSig: ByteArray = ByteArray(0)
        var keystoneSighash: ByteArray = ByteArray(0)

        override suspend fun close() = unused()

        override suspend fun initRound(
            roundId: String,
            snapshotHeight: Long,
            eaPK: ByteArray,
            ncRoot: ByteArray,
            nullifierIMTRoot: ByteArray,
            sessionJson: String?
        ) = unused()

        override suspend fun getRoundState(roundId: String): JniRoundState? = unused()

        override suspend fun listRounds(): Array<JniRoundSummary> = unused()

        override suspend fun getBundleCount(roundId: String): Int = unused()

        override suspend fun getVotes(roundId: String): Array<JniVoteRecord> = unused()

        override suspend fun clearRound(roundId: String) = unused()

        override suspend fun deleteSkippedBundles(
            roundId: String,
            keepCount: Int
        ): Long = unused()

        override suspend fun setupBundles(
            roundId: String,
            notes: List<JniNoteInfo>
        ): JniBundleSetupResult = unused()

        override suspend fun generateHotkey(
            roundId: String,
            seed: ByteArray
        ): JniVotingHotkey = unused()

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
        ): JniGovernancePczt = unused()

        override suspend fun storeWitnesses(
            roundId: String,
            bundleIndex: Int,
            notes: List<JniNoteInfo>,
            witnesses: List<JniWitnessData>
        ) {
            storeWitnessesRoundId = roundId
            storeWitnessesBundleIndex = bundleIndex
            storeWitnessesNotes = notes
            storeWitnessesWitnesses = witnesses
        }

        override suspend fun precomputeDelegationPir(
            roundId: String,
            bundleIndex: Int,
            pirServerUrl: String,
            networkId: Int,
            notes: List<JniNoteInfo>
        ): JniDelegationPirPrecomputeResult {
            precomputeRoundId = roundId
            precomputeBundleIndex = bundleIndex
            precomputePirServerUrl = pirServerUrl
            precomputeNetworkId = networkId
            precomputeNotes = notes
            return JniDelegationPirPrecomputeResult(cachedCount = 11, fetchedCount = 12)
        }

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
        ): JniDelegationProofResult {
            buildAndProveRoundId = roundId
            buildAndProveBundleIndex = bundleIndex
            buildAndProvePirServerUrl = pirServerUrl
            buildAndProveNetworkId = networkId
            buildAndProveNotes = notes
            buildAndProveWalletSeed = walletSeed
            buildAndProveAccountIndex = accountIndex
            buildAndProveAddressIndex = addressIndex
            buildAndProveProgress = proofProgress
            return proofResult
        }

        override suspend fun getDelegationSubmission(
            roundId: String,
            bundleIndex: Int,
            senderSeed: ByteArray,
            networkId: Int,
            accountIndex: Int
        ): JniDelegationSubmissionResult {
            submissionRoundId = roundId
            submissionBundleIndex = bundleIndex
            submissionSenderSeed = senderSeed
            submissionNetworkId = networkId
            submissionAccountIndex = accountIndex
            return submissionResult
        }

        override suspend fun getDelegationSubmissionWithKeystoneSig(
            roundId: String,
            bundleIndex: Int,
            keystoneSig: ByteArray,
            keystoneSighash: ByteArray
        ): JniDelegationSubmissionResult {
            keystoneRoundId = roundId
            keystoneBundleIndex = bundleIndex
            keystoneSig.also { this.keystoneSig = it }
            keystoneSighash.also { this.keystoneSighash = it }
            return keystoneSubmissionResult
        }

        private fun unused(): Nothing = error("unused")
    }

    private companion object {
        private const val PROOF_BYTES = 3
    }
}
