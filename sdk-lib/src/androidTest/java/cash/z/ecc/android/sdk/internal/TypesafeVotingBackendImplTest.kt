package cash.z.ecc.android.sdk.internal

import cash.z.ecc.android.sdk.internal.jni.JNI_DELEGATION_PUBLIC_INPUT_COUNT
import cash.z.ecc.android.sdk.internal.jni.JNI_GOVERNANCE_NULLIFIER_COUNT
import cash.z.ecc.android.sdk.internal.jni.JNI_PROTOCOL_FIELD_BYTES_SIZE
import cash.z.ecc.android.sdk.internal.jni.JNI_SPEND_AUTH_SIG_BYTES_SIZE
import cash.z.ecc.android.sdk.internal.jni.JNI_VAN_WITNESS_PATH_DEPTH
import cash.z.ecc.android.sdk.internal.jni.JNI_VOTE_SHARE_COUNT
import cash.z.ecc.android.sdk.internal.jni.VotingProofProgressCallback
import cash.z.ecc.android.sdk.internal.model.voting.JniBundleSetupResult
import cash.z.ecc.android.sdk.internal.model.voting.JniCommitmentBundleRecord
import cash.z.ecc.android.sdk.internal.model.voting.JniDelegationPirPrecomputeResult
import cash.z.ecc.android.sdk.internal.model.voting.JniDelegationProofResult
import cash.z.ecc.android.sdk.internal.model.voting.JniDelegationSubmissionResult
import cash.z.ecc.android.sdk.internal.model.voting.JniGovernancePczt
import cash.z.ecc.android.sdk.internal.model.voting.JniNoteInfo
import cash.z.ecc.android.sdk.internal.model.voting.JniRoundState
import cash.z.ecc.android.sdk.internal.model.voting.JniRoundSummary
import cash.z.ecc.android.sdk.internal.model.voting.JniShareDelegationRecord
import cash.z.ecc.android.sdk.internal.model.voting.JniSharePayload
import cash.z.ecc.android.sdk.internal.model.voting.JniVanWitness
import cash.z.ecc.android.sdk.internal.model.voting.JniVoteCommitmentResult
import cash.z.ecc.android.sdk.internal.model.voting.JniVoteRecord
import cash.z.ecc.android.sdk.internal.model.voting.JniVotingHotkey
import cash.z.ecc.android.sdk.internal.model.voting.JniWireEncryptedShare
import cash.z.ecc.android.sdk.internal.model.voting.JniWitnessData
import cash.z.ecc.android.sdk.model.AccountUuid
import cash.z.ecc.android.sdk.model.BlockHeight
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Suppress("LargeClass", "LongMethod", "LongParameterList", "MagicNumber", "TooManyFunctions")
class TypesafeVotingBackendImplTest {
    @Test
    fun delegation_proof_result_checks_non_empty_proof() {
        val error =
            assertFailsWith<IllegalArgumentException> {
                jniDelegationProofResult(proof = ByteArray(0))
                    .toDelegationProofResult()
            }

        assertTrue(error.message.orEmpty().contains("proof"))
    }

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
    fun delegation_submission_result_checks_non_empty_proof() {
        val error =
            assertFailsWith<IllegalArgumentException> {
                jniDelegationSubmissionResult(proof = ByteArray(0))
                    .toDelegationSubmissionResult()
            }

        assertTrue(error.message.orEmpty().contains("proof"))
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
    fun voting_note_info_maps_to_and_from_jni_shape() {
        val jniNote = jniNoteInfo().copy(scope = 1)

        val note = jniNote.toVotingNoteInfo()

        assertEquals(VotingNoteScope.INTERNAL, note.scope)
        assertEquals(jniNote, note.toJniNoteInfo())
    }

    @Test
    fun get_wallet_notes_forwards_arguments_and_maps_results() =
        runTest {
            val accountUuid = AccountUuid.new(ByteArray(16) { it.toByte() })
            val jniNotes = arrayOf(jniNoteInfo().copy(scope = 1, ufvk = "ufvk"))
            val bridge = RecordingVotingBackendBridge(walletNotes = jniNotes)
            val backend = TypesafeVotingBackendImpl { bridge }

            val notes =
                backend.getWalletNotes(
                    walletDbPath = "/tmp/wallet.db",
                    snapshotHeight = BlockHeight.new(123_456L),
                    networkId = 1,
                    accountUuid = accountUuid
                )

            assertEquals("/tmp/wallet.db", bridge.walletDbPath)
            assertEquals(123_456L, bridge.snapshotHeight)
            assertEquals(1, bridge.networkId)
            assertContentEquals(accountUuid.value, bridge.accountUuidBytes)
            assertEquals(jniNotes.map { it.toVotingNoteInfo() }, notes)
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
                    voteRoundId = "round-submission"
                )
            val keystoneJniResult =
                jniDelegationSubmissionResult(
                    proof = ByteArray(PROOF_BYTES) { 31 },
                    voteRoundId = "round-keystone"
                )
            val generatedWitnesses = arrayOf(jniWitnessData().copy(position = 11L))
            val backend =
                RecordingVotingDbBackend(
                    proofResult = proofJniResult,
                    submissionResult = submissionJniResult,
                    keystoneSubmissionResult = keystoneJniResult,
                    generatedWitnesses = generatedWitnesses
                )
            val db = TypesafeVotingDbImpl(backend)
            val walletSeed = byteArrayOf(1, 2, 3)
            val senderSeed = byteArrayOf(4, 5, 6)
            val keystoneSig = byteArrayOf(7, 8)
            val keystoneSighash = byteArrayOf(9, 10)
            val treeStateBytes = byteArrayOf(11, 12, 13)
            val notes = listOf(votingNoteInfo())
            val jniNotes = notes.map { it.toJniNoteInfo() }
            val witnesses = listOf(jniWitnessData())
            var progressValue: Double? = null

            db.storeWitnesses("round-1", 2, notes, witnesses)
            assertEquals("round-1", backend.storeWitnessesRoundId)
            assertEquals(2, backend.storeWitnessesBundleIndex)
            assertEquals(jniNotes, backend.storeWitnessesNotes)
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
            assertEquals(jniNotes, backend.precomputeNotes)

            val proof =
                db.buildAndProveDelegation(
                    roundId = "round-3",
                    bundleIndex = 4,
                    pirServerUrl = "https://pir.example",
                    networkId = 1,
                    notes = notes,
                    hotkeySeed = walletSeed
                ) { progress ->
                    progressValue = progress
                }
            assertEquals("round-3", backend.buildAndProveRoundId)
            assertEquals(4, backend.buildAndProveBundleIndex)
            assertEquals("https://pir.example", backend.buildAndProvePirServerUrl)
            assertEquals(1, backend.buildAndProveNetworkId)
            assertEquals(jniNotes, backend.buildAndProveNotes)
            assertContentEquals(walletSeed, backend.buildAndProveHotkeySeed)
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

            db.storeTreeState("round-6", treeStateBytes)
            assertEquals("round-6", backend.storeTreeStateRoundId)
            assertContentEquals(treeStateBytes, backend.storeTreeStateBytes)

            val generated =
                db.generateNoteWitnesses(
                    roundId = "round-7",
                    bundleIndex = 10,
                    walletDbPath = "/tmp/wallet.db",
                    networkId = 1,
                    notes = notes
                )
            assertEquals("round-7", backend.generateNoteWitnessesRoundId)
            assertEquals(10, backend.generateNoteWitnessesBundleIndex)
            assertEquals("/tmp/wallet.db", backend.generateNoteWitnessesWalletDbPath)
            assertEquals(1, backend.generateNoteWitnessesNetworkId)
            assertEquals(jniNotes, backend.generateNoteWitnessesNotes)
            assertEquals(generatedWitnesses.asList(), generated)
        }

    @Test
    fun vote_methods_forward_arguments_and_map_results() =
        runTest {
            val witness =
                jniVanWitness(
                    position = 33,
                    anchorHeight = 44
                )
            val commitment =
                jniVoteCommitmentResult(
                    voteCommitment = field(35),
                    proposalId = 2,
                    voteRoundId = "round-vote"
                )
            val backend =
                RecordingVotingDbBackend(
                    proofResult = jniDelegationProofResult(),
                    submissionResult = jniDelegationSubmissionResult(),
                    keystoneSubmissionResult = jniDelegationSubmissionResult(),
                    witnessResult = witness,
                    commitmentResult = commitment,
                    syncHeight = 55
                )
            val db = TypesafeVotingDbImpl(backend)
            val hotkeySeed = byteArrayOf(1, 2, 3)
            var progressValue: Double? = null

            assertEquals(55, db.syncVoteTree("round-vote", "https://node.example"))
            assertEquals("round-vote", backend.syncRoundId)
            assertEquals("https://node.example", backend.syncNodeUrl)

            db.resetTreeClient("round-vote")
            assertEquals("round-vote", backend.resetRoundId)
            db.resetAllTreeClients()
            assertEquals(1, backend.resetAllCount)

            db.storeVanPosition("round-vote", 3, 77)
            assertEquals("round-vote", backend.storeVanRoundId)
            assertEquals(3, backend.storeVanBundleIndex)
            assertEquals(77, backend.storeVanPosition)

            val generatedWitness = db.generateVanWitness("round-vote", 3, 44)
            assertEquals(witness, generatedWitness)
            assertEquals("round-vote", backend.generateVanRoundId)
            assertEquals(3, backend.generateVanBundleIndex)
            assertEquals(44, backend.generateVanAnchorHeight)

            val voteCommitment =
                db.buildVoteCommitment(
                    roundId = "round-vote",
                    bundleIndex = 3,
                    hotkeySeed = hotkeySeed,
                    proposalId = 2,
                    choice = 1,
                    numOptions = 3,
                    witness = witness,
                    networkId = 0,
                    accountIndex = 8
                ) { progress ->
                    progressValue = progress
                }
            assertEquals(commitment, voteCommitment)
            assertEquals("round-vote", backend.buildVoteRoundId)
            assertEquals(3, backend.buildVoteBundleIndex)
            assertContentEquals(hotkeySeed, backend.buildVoteHotkeySeed)
            assertEquals(2, backend.buildVoteProposalId)
            assertEquals(1, backend.buildVoteChoice)
            assertEquals(3, backend.buildVoteNumOptions)
            assertEquals(witness, backend.buildVoteWitness)
            assertEquals(0, backend.buildVoteNetworkId)
            assertEquals(8, backend.buildVoteAccountIndex)
            assertEquals(false, backend.buildVoteSingleShare)
            assertNotNull(backend.buildVoteProgress).onProgress(0.5)
            assertEquals(0.5, progressValue)
        }

    @Test
    fun recovery_methods_forward_arguments_and_map_results() =
        runTest {
            val commitment = jniVoteCommitmentResult(voteCommitment = field(31))
            val commitmentRecord =
                JniCommitmentBundleRecord(
                    commitment = commitment,
                    vcTreePosition = 99
                )
            val shareRecord =
                jniShareDelegationRecord(
                    nullifier = field(32),
                    confirmed = false
                )
            val unconfirmedRecord =
                jniShareDelegationRecord(
                    shareIndex = 2,
                    nullifier = field(33),
                    confirmed = false
                )
            val backend =
                RecordingVotingDbBackend(
                    proofResult = jniDelegationProofResult(),
                    submissionResult = jniDelegationSubmissionResult(),
                    keystoneSubmissionResult = jniDelegationSubmissionResult(),
                    delegationTxHash = "delegation-tx",
                    voteTxHash = "vote-tx",
                    commitmentRecord = commitmentRecord,
                    shareRecords = arrayOf(shareRecord),
                    unconfirmedShareRecords = arrayOf(unconfirmedRecord)
                )
            val db = TypesafeVotingDbImpl(backend)

            db.storeDelegationTxHash("round-recovery", 1, "delegation-tx")
            assertEquals("round-recovery", backend.storeDelegationTxRoundId)
            assertEquals(1, backend.storeDelegationTxBundleIndex)
            assertEquals("delegation-tx", backend.storeDelegationTxHash)

            assertEquals(
                VotingTxHashLookup.Found("delegation-tx"),
                db.getDelegationTxHash("round-recovery", 1)
            )
            assertEquals("round-recovery", backend.getDelegationTxRoundId)
            assertEquals(1, backend.getDelegationTxBundleIndex)

            db.storeVoteTxHash("round-recovery", 1, 2, "vote-tx")
            assertEquals("round-recovery", backend.storeVoteTxRoundId)
            assertEquals(1, backend.storeVoteTxBundleIndex)
            assertEquals(2, backend.storeVoteTxProposalId)
            assertEquals("vote-tx", backend.storeVoteTxHash)

            db.markVoteSubmitted("round-recovery", 1, 2)
            assertEquals("round-recovery", backend.markVoteRoundId)
            assertEquals(1, backend.markVoteBundleIndex)
            assertEquals(2, backend.markVoteProposalId)

            assertEquals(
                VotingTxHashLookup.Found("vote-tx"),
                db.getVoteTxHash("round-recovery", 1, 2)
            )
            assertEquals("round-recovery", backend.getVoteTxRoundId)
            assertEquals(1, backend.getVoteTxBundleIndex)
            assertEquals(2, backend.getVoteTxProposalId)

            db.storeCommitmentBundle("round-recovery", 1, 2, commitment, 99)
            assertEquals("round-recovery", backend.storeCommitmentRoundId)
            assertEquals(1, backend.storeCommitmentBundleIndex)
            assertEquals(2, backend.storeCommitmentProposalId)
            assertEquals(commitment, backend.storeCommitment)
            assertEquals(99, backend.storeCommitmentTreePosition)

            val recoveredCommitment = db.getCommitmentBundle("round-recovery", 1, 2)
            assertEquals(CommitmentBundleRecord(commitment, 99), recoveredCommitment)
            assertEquals("round-recovery", backend.getCommitmentRoundId)
            assertEquals(1, backend.getCommitmentBundleIndex)
            assertEquals(2, backend.getCommitmentProposalId)

            db.recordShareDelegation(
                roundId = "round-recovery",
                bundleIndex = 1,
                proposalId = 2,
                shareIndex = 3,
                sentToUrls = listOf("https://helper.example"),
                nullifier = field(34),
                submitAt = 123
            )
            assertEquals("round-recovery", backend.recordShareRoundId)
            assertEquals(1, backend.recordShareBundleIndex)
            assertEquals(2, backend.recordShareProposalId)
            assertEquals(3, backend.recordShareIndex)
            assertEquals(listOf("https://helper.example"), backend.recordShareSentToUrls)
            assertContentEquals(field(34), backend.recordShareNullifier)
            assertEquals(123, backend.recordShareSubmitAt)

            assertEquals(listOf(shareRecord.toShareDelegationRecordForTest()), db.getShareDelegations("round-recovery"))
            assertEquals("round-recovery", backend.getSharesRoundId)
            assertEquals(
                listOf(unconfirmedRecord.toShareDelegationRecordForTest()),
                db.getUnconfirmedDelegations("round-recovery")
            )
            assertEquals("round-recovery", backend.getUnconfirmedSharesRoundId)

            db.markShareConfirmed("round-recovery", 1, 2, 3)
            assertEquals("round-recovery", backend.markShareRoundId)
            assertEquals(1, backend.markShareBundleIndex)
            assertEquals(2, backend.markShareProposalId)
            assertEquals(3, backend.markShareIndex)

            db.addSentServers("round-recovery", 1, 2, 3, listOf("https://helper-2.example"))
            assertEquals("round-recovery", backend.addSentRoundId)
            assertEquals(1, backend.addSentBundleIndex)
            assertEquals(2, backend.addSentProposalId)
            assertEquals(3, backend.addSentShareIndex)
            assertEquals(listOf("https://helper-2.example"), backend.addSentNewUrls)

            db.clearRecoveryState("round-recovery")
            assertEquals("round-recovery", backend.clearRecoveryRoundId)
        }

    @Test
    fun missing_tx_hashes_map_to_typed_missing_state() =
        runTest {
            val backend =
                RecordingVotingDbBackend(
                    proofResult = jniDelegationProofResult(),
                    submissionResult = jniDelegationSubmissionResult(),
                    keystoneSubmissionResult = jniDelegationSubmissionResult()
                )
            val db = TypesafeVotingDbImpl(backend)

            assertEquals(VotingTxHashLookup.Missing, db.getDelegationTxHash("round", 0))
            assertEquals(VotingTxHashLookup.Missing, db.getVoteTxHash("round", 0, 0))
        }

    @Test
    fun missing_recovery_rows_from_native_exception_map_to_expected_state() =
        runTest {
            val backend =
                RecordingVotingDbBackend(
                    proofResult = jniDelegationProofResult(),
                    submissionResult = jniDelegationSubmissionResult(),
                    keystoneSubmissionResult = jniDelegationSubmissionResult(),
                    recoveryLookupException =
                        RuntimeException(
                            "native lookup failed",
                            IllegalStateException("query returned no rows")
                        )
                )
            val db = TypesafeVotingDbImpl(backend)

            assertEquals(VotingTxHashLookup.Missing, db.getDelegationTxHash("round", 0))
            assertEquals(VotingTxHashLookup.Missing, db.getVoteTxHash("round", 0, 0))
            assertNull(db.getCommitmentBundle("round", 0, 0))
        }

    @Test
    fun unexpected_recovery_lookup_exceptions_still_fail() =
        runTest {
            val backend =
                RecordingVotingDbBackend(
                    proofResult = jniDelegationProofResult(),
                    submissionResult = jniDelegationSubmissionResult(),
                    keystoneSubmissionResult = jniDelegationSubmissionResult(),
                    recoveryLookupException = RuntimeException("database is locked")
                )
            val db = TypesafeVotingDbImpl(backend)

            val error =
                assertFailsWith<RuntimeException> {
                    db.getDelegationTxHash("round", 0)
                }

            assertEquals("database is locked", error.message)
        }

    @Test
    fun vote_commitment_wrapper_rejects_invalid_commitment_result() =
        runTest {
            val backend =
                RecordingVotingDbBackend(
                    proofResult = jniDelegationProofResult(),
                    submissionResult = jniDelegationSubmissionResult(),
                    keystoneSubmissionResult = jniDelegationSubmissionResult(),
                    commitmentResult = jniVoteCommitmentResult(encShares = emptyList())
                )
            val db = TypesafeVotingDbImpl(backend)

            val error =
                assertFailsWith<IllegalArgumentException> {
                    db.buildVoteCommitment(
                        roundId = "round-vote",
                        bundleIndex = 3,
                        hotkeySeed = byteArrayOf(1, 2, 3),
                        proposalId = 2,
                        choice = 1,
                        numOptions = 3,
                        witness = jniVanWitness(),
                        networkId = 0,
                        accountIndex = 0
                    )
                }

            assertTrue(error.message.orEmpty().contains("encShares"))
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
        voteRoundId = voteRoundId
    )

    private fun jniVanWitness(
        authPath: List<ByteArray> = fieldElements(JNI_VAN_WITNESS_PATH_DEPTH),
        position: Long = 1,
        anchorHeight: Long = 2
    ) = JniVanWitness(
        authPath = authPath,
        position = position,
        anchorHeight = anchorHeight
    )

    private fun jniVoteCommitmentResult(
        vanNullifier: ByteArray = field(10),
        voteAuthorityNoteNew: ByteArray = field(11),
        voteCommitment: ByteArray = field(12),
        proposalId: Int = 1,
        proof: ByteArray = ByteArray(PROOF_BYTES) { 13 },
        encShares: List<JniWireEncryptedShare> = wireShares(),
        anchorHeight: Long = 2,
        voteRoundId: String = "round-vote",
        sharesHash: ByteArray = field(14),
        shareBlinds: List<ByteArray> = fieldElements(JNI_VOTE_SHARE_COUNT, 15),
        shareComms: List<ByteArray> = fieldElements(JNI_VOTE_SHARE_COUNT, 16),
        rVpk: ByteArray = field(17),
        alphaV: ByteArray = field(18)
    ) = JniVoteCommitmentResult(
        vanNullifier = vanNullifier,
        voteAuthorityNoteNew = voteAuthorityNoteNew,
        voteCommitment = voteCommitment,
        proposalId = proposalId,
        proof = proof,
        encShares = encShares,
        anchorHeight = anchorHeight,
        voteRoundId = voteRoundId,
        sharesHash = sharesHash,
        shareBlinds = shareBlinds,
        shareComms = shareComms,
        rVpk = rVpk,
        alphaV = alphaV
    )

    private fun jniShareDelegationRecord(
        roundId: String = "round-recovery",
        bundleIndex: Int = 1,
        proposalId: Int = 2,
        shareIndex: Int = 3,
        sentToUrls: List<String> = listOf("https://helper.example"),
        nullifier: ByteArray = field(19),
        confirmed: Boolean = false,
        submitAt: Long = 123,
        createdAt: Long = 456
    ) = JniShareDelegationRecord(
        roundId = roundId,
        bundleIndex = bundleIndex,
        proposalId = proposalId,
        shareIndex = shareIndex,
        sentToUrls = sentToUrls,
        nullifier = nullifier,
        confirmed = confirmed,
        submitAt = submitAt,
        createdAt = createdAt
    )

    private fun JniShareDelegationRecord.toShareDelegationRecordForTest() =
        ShareDelegationRecord(
            roundId = roundId,
            bundleIndex = bundleIndex,
            proposalId = proposalId,
            shareIndex = shareIndex,
            sentToUrls = sentToUrls,
            nullifier = nullifier,
            confirmed = confirmed,
            submitAt = submitAt,
            createdAt = createdAt
        )

    private fun wireShares(
        count: Int = JNI_VOTE_SHARE_COUNT,
        fieldSize: Int = JNI_PROTOCOL_FIELD_BYTES_SIZE
    ) = List(count) { index ->
        JniWireEncryptedShare(
            c1 = ByteArray(fieldSize) { (index + 1).toByte() },
            c2 = ByteArray(fieldSize) { (index + 2).toByte() },
            shareIndex = index
        )
    }

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

    private fun votingNoteInfo() = jniNoteInfo().toVotingNoteInfo()

    private fun jniWitnessData() =
        JniWitnessData(
            noteCommitment = field(1),
            position = 0L,
            root = field(5),
            authPath = fieldElements(32)
        )

    @Suppress("TooManyFunctions")
    private class RecordingVotingBackendBridge(
        private val walletNotes: Array<JniNoteInfo>
    ) : VotingBackendBridge {
        var walletDbPath: String? = null
        var snapshotHeight: Long? = null
        var networkId: Int? = null
        var accountUuidBytes: ByteArray = ByteArray(0)

        override suspend fun computeShareNullifier(
            voteCommitment: ByteArray,
            shareIndex: Int,
            blind: ByteArray
        ): ByteArray = unused()

        override suspend fun openVotingDb(dbPath: String, walletId: String): VotingDbBackend =
            unused()

        override suspend fun computeBundleSetup(notes: List<JniNoteInfo>): JniBundleSetupResult =
            unused()

        override suspend fun warmProvingCaches() = unused()

        override suspend fun decomposeWeight(weight: Long): LongArray = unused()

        override suspend fun buildSharePayloads(
            commitment: JniVoteCommitmentResult,
            voteDecision: Int,
            numOptions: Int,
            vcTreePosition: Long,
            singleShareMode: Boolean
        ): Array<JniSharePayload> = unused()

        override suspend fun signCastVote(
            hotkeySeed: ByteArray,
            networkId: Int,
            accountIndex: Int,
            commitment: JniVoteCommitmentResult
        ): ByteArray = unused()

        override suspend fun extractOrchardFvkFromUfvk(
            ufvk: String,
            networkId: Int
        ): ByteArray = unused()

        override suspend fun extractNcRoot(treeStateBytes: ByteArray): ByteArray = unused()

        override suspend fun verifyWitness(witness: JniWitnessData): Boolean = unused()

        override suspend fun getWalletNotes(
            walletDbPath: String,
            snapshotHeight: Long,
            networkId: Int,
            accountUuidBytes: ByteArray
        ): Array<JniNoteInfo> {
            this.walletDbPath = walletDbPath
            this.snapshotHeight = snapshotHeight
            this.networkId = networkId
            this.accountUuidBytes = accountUuidBytes
            return walletNotes
        }

        override suspend fun extractPcztSighash(pcztBytes: ByteArray): ByteArray = unused()

        override suspend fun extractSpendAuthSig(
            signedPcztBytes: ByteArray,
            actionIndex: Int
        ): ByteArray = unused()

        private fun unused(): Nothing = error("unused")
    }

    private class RecordingVotingDbBackend(
        private val proofResult: JniDelegationProofResult,
        private val submissionResult: JniDelegationSubmissionResult,
        private val keystoneSubmissionResult: JniDelegationSubmissionResult,
        private val generatedWitnesses: Array<JniWitnessData> = emptyArray(),
        private val witnessResult: JniVanWitness =
            JniVanWitness(
                authPath = List(JNI_VAN_WITNESS_PATH_DEPTH) { ByteArray(JNI_PROTOCOL_FIELD_BYTES_SIZE) },
                position = 1,
                anchorHeight = 2
            ),
        private val commitmentResult: JniVoteCommitmentResult =
            JniVoteCommitmentResult(
                vanNullifier = ByteArray(JNI_PROTOCOL_FIELD_BYTES_SIZE),
                voteAuthorityNoteNew = ByteArray(JNI_PROTOCOL_FIELD_BYTES_SIZE),
                voteCommitment = ByteArray(JNI_PROTOCOL_FIELD_BYTES_SIZE),
                proposalId = 1,
                proof = ByteArray(PROOF_BYTES),
                encShares =
                    List(JNI_VOTE_SHARE_COUNT) { index ->
                        JniWireEncryptedShare(
                            c1 = ByteArray(JNI_PROTOCOL_FIELD_BYTES_SIZE),
                            c2 = ByteArray(JNI_PROTOCOL_FIELD_BYTES_SIZE),
                            shareIndex = index
                        )
                    },
                anchorHeight = 2,
                voteRoundId = "round-vote",
                sharesHash = ByteArray(JNI_PROTOCOL_FIELD_BYTES_SIZE),
                shareBlinds = List(JNI_VOTE_SHARE_COUNT) { ByteArray(JNI_PROTOCOL_FIELD_BYTES_SIZE) },
                shareComms = List(JNI_VOTE_SHARE_COUNT) { ByteArray(JNI_PROTOCOL_FIELD_BYTES_SIZE) },
                rVpk = ByteArray(JNI_PROTOCOL_FIELD_BYTES_SIZE),
                alphaV = ByteArray(JNI_PROTOCOL_FIELD_BYTES_SIZE)
            ),
        private val syncHeight: Long = 1,
        private val delegationTxHash: String? = null,
        private val voteTxHash: String? = null,
        private val commitmentRecord: JniCommitmentBundleRecord? = null,
        private val shareRecords: Array<JniShareDelegationRecord> = emptyArray(),
        private val unconfirmedShareRecords: Array<JniShareDelegationRecord> = emptyArray(),
        private val recoveryLookupException: RuntimeException? = null
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
        var buildAndProveHotkeySeed: ByteArray = ByteArray(0)
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
        var storeTreeStateRoundId: String? = null
        var storeTreeStateBytes: ByteArray = ByteArray(0)
        var generateNoteWitnessesRoundId: String? = null
        var generateNoteWitnessesBundleIndex: Int? = null
        var generateNoteWitnessesWalletDbPath: String? = null
        var generateNoteWitnessesNetworkId: Int? = null
        var generateNoteWitnessesNotes: List<JniNoteInfo>? = null
        var syncRoundId: String? = null
        var syncNodeUrl: String? = null
        var resetRoundId: String? = null
        var resetAllCount = 0
        var storeVanRoundId: String? = null
        var storeVanBundleIndex: Int? = null
        var storeVanPosition: Long? = null
        var generateVanRoundId: String? = null
        var generateVanBundleIndex: Int? = null
        var generateVanAnchorHeight: Long? = null
        var buildVoteRoundId: String? = null
        var buildVoteBundleIndex: Int? = null
        var buildVoteHotkeySeed: ByteArray = ByteArray(0)
        var buildVoteProposalId: Int? = null
        var buildVoteChoice: Int? = null
        var buildVoteNumOptions: Int? = null
        var buildVoteWitness: JniVanWitness? = null
        var buildVoteNetworkId: Int? = null
        var buildVoteAccountIndex: Int? = null
        var buildVoteSingleShare: Boolean? = null
        var buildVoteProgress: VotingProofProgressCallback? = null
        var storeDelegationTxRoundId: String? = null
        var storeDelegationTxBundleIndex: Int? = null
        var storeDelegationTxHash: String? = null
        var getDelegationTxRoundId: String? = null
        var getDelegationTxBundleIndex: Int? = null
        var storeVoteTxRoundId: String? = null
        var storeVoteTxBundleIndex: Int? = null
        var storeVoteTxProposalId: Int? = null
        var storeVoteTxHash: String? = null
        var markVoteRoundId: String? = null
        var markVoteBundleIndex: Int? = null
        var markVoteProposalId: Int? = null
        var getVoteTxRoundId: String? = null
        var getVoteTxBundleIndex: Int? = null
        var getVoteTxProposalId: Int? = null
        var storeCommitmentRoundId: String? = null
        var storeCommitmentBundleIndex: Int? = null
        var storeCommitmentProposalId: Int? = null
        var storeCommitment: JniVoteCommitmentResult? = null
        var storeCommitmentTreePosition: Long? = null
        var getCommitmentRoundId: String? = null
        var getCommitmentBundleIndex: Int? = null
        var getCommitmentProposalId: Int? = null
        var clearRecoveryRoundId: String? = null
        var recordShareRoundId: String? = null
        var recordShareBundleIndex: Int? = null
        var recordShareProposalId: Int? = null
        var recordShareIndex: Int? = null
        var recordShareSentToUrls: List<String>? = null
        var recordShareNullifier: ByteArray = ByteArray(0)
        var recordShareSubmitAt: Long? = null
        var getSharesRoundId: String? = null
        var getUnconfirmedSharesRoundId: String? = null
        var markShareRoundId: String? = null
        var markShareBundleIndex: Int? = null
        var markShareProposalId: Int? = null
        var markShareIndex: Int? = null
        var addSentRoundId: String? = null
        var addSentBundleIndex: Int? = null
        var addSentProposalId: Int? = null
        var addSentShareIndex: Int? = null
        var addSentNewUrls: List<String>? = null

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
            hotkeySeed: ByteArray,
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
            hotkeySeed: ByteArray,
            proofProgress: VotingProofProgressCallback?
        ): JniDelegationProofResult {
            buildAndProveRoundId = roundId
            buildAndProveBundleIndex = bundleIndex
            buildAndProvePirServerUrl = pirServerUrl
            buildAndProveNetworkId = networkId
            buildAndProveNotes = notes
            buildAndProveHotkeySeed = hotkeySeed
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

        override suspend fun storeTreeState(roundId: String, treeStateBytes: ByteArray) {
            storeTreeStateRoundId = roundId
            storeTreeStateBytes = treeStateBytes
        }

        override suspend fun generateNoteWitnesses(
            roundId: String,
            bundleIndex: Int,
            walletDbPath: String,
            networkId: Int,
            notes: List<JniNoteInfo>
        ): Array<JniWitnessData> {
            generateNoteWitnessesRoundId = roundId
            generateNoteWitnessesBundleIndex = bundleIndex
            generateNoteWitnessesWalletDbPath = walletDbPath
            generateNoteWitnessesNetworkId = networkId
            generateNoteWitnessesNotes = notes
            return generatedWitnesses
        }

        override suspend fun syncVoteTree(roundId: String, nodeUrl: String): Long {
            syncRoundId = roundId
            syncNodeUrl = nodeUrl
            return syncHeight
        }

        override suspend fun resetTreeClient(roundId: String) {
            resetRoundId = roundId
        }

        override suspend fun resetAllTreeClients() {
            resetAllCount += 1
        }

        override suspend fun storeVanPosition(
            roundId: String,
            bundleIndex: Int,
            position: Long
        ) {
            storeVanRoundId = roundId
            storeVanBundleIndex = bundleIndex
            storeVanPosition = position
        }

        override suspend fun generateVanWitness(
            roundId: String,
            bundleIndex: Int,
            anchorHeight: Long
        ): JniVanWitness {
            generateVanRoundId = roundId
            generateVanBundleIndex = bundleIndex
            generateVanAnchorHeight = anchorHeight
            return witnessResult
        }

        override suspend fun buildVoteCommitment(
            roundId: String,
            bundleIndex: Int,
            hotkeySeed: ByteArray,
            proposalId: Int,
            choice: Int,
            numOptions: Int,
            witness: JniVanWitness,
            networkId: Int,
            accountIndex: Int,
            singleShare: Boolean,
            proofProgress: VotingProofProgressCallback?
        ): JniVoteCommitmentResult {
            buildVoteRoundId = roundId
            buildVoteBundleIndex = bundleIndex
            buildVoteHotkeySeed = hotkeySeed
            buildVoteProposalId = proposalId
            buildVoteChoice = choice
            buildVoteNumOptions = numOptions
            buildVoteWitness = witness
            buildVoteNetworkId = networkId
            buildVoteAccountIndex = accountIndex
            buildVoteSingleShare = singleShare
            buildVoteProgress = proofProgress
            return commitmentResult
        }

        override suspend fun storeDelegationTxHash(
            roundId: String,
            bundleIndex: Int,
            txHash: String
        ) {
            storeDelegationTxRoundId = roundId
            storeDelegationTxBundleIndex = bundleIndex
            storeDelegationTxHash = txHash
        }

        override suspend fun getDelegationTxHash(roundId: String, bundleIndex: Int): String? {
            getDelegationTxRoundId = roundId
            getDelegationTxBundleIndex = bundleIndex
            recoveryLookupException?.let { throw it }
            return delegationTxHash
        }

        override suspend fun storeVoteTxHash(
            roundId: String,
            bundleIndex: Int,
            proposalId: Int,
            txHash: String
        ) {
            storeVoteTxRoundId = roundId
            storeVoteTxBundleIndex = bundleIndex
            storeVoteTxProposalId = proposalId
            storeVoteTxHash = txHash
        }

        override suspend fun markVoteSubmitted(roundId: String, bundleIndex: Int, proposalId: Int) {
            markVoteRoundId = roundId
            markVoteBundleIndex = bundleIndex
            markVoteProposalId = proposalId
        }

        override suspend fun getVoteTxHash(
            roundId: String,
            bundleIndex: Int,
            proposalId: Int
        ): String? {
            getVoteTxRoundId = roundId
            getVoteTxBundleIndex = bundleIndex
            getVoteTxProposalId = proposalId
            recoveryLookupException?.let { throw it }
            return voteTxHash
        }

        override suspend fun storeCommitmentBundle(
            roundId: String,
            bundleIndex: Int,
            proposalId: Int,
            commitment: JniVoteCommitmentResult,
            vcTreePosition: Long
        ) {
            storeCommitmentRoundId = roundId
            storeCommitmentBundleIndex = bundleIndex
            storeCommitmentProposalId = proposalId
            storeCommitment = commitment
            storeCommitmentTreePosition = vcTreePosition
        }

        override suspend fun getCommitmentBundle(
            roundId: String,
            bundleIndex: Int,
            proposalId: Int
        ): JniCommitmentBundleRecord? {
            getCommitmentRoundId = roundId
            getCommitmentBundleIndex = bundleIndex
            getCommitmentProposalId = proposalId
            recoveryLookupException?.let { throw it }
            return commitmentRecord
        }

        override suspend fun clearRecoveryState(roundId: String) {
            clearRecoveryRoundId = roundId
        }

        override suspend fun recordShareDelegation(
            roundId: String,
            bundleIndex: Int,
            proposalId: Int,
            shareIndex: Int,
            sentToUrls: List<String>,
            nullifier: ByteArray,
            submitAt: Long
        ) {
            recordShareRoundId = roundId
            recordShareBundleIndex = bundleIndex
            recordShareProposalId = proposalId
            recordShareIndex = shareIndex
            recordShareSentToUrls = sentToUrls
            recordShareNullifier = nullifier
            recordShareSubmitAt = submitAt
        }

        override suspend fun getShareDelegations(roundId: String): Array<JniShareDelegationRecord> {
            getSharesRoundId = roundId
            return shareRecords
        }

        override suspend fun getUnconfirmedDelegations(
            roundId: String
        ): Array<JniShareDelegationRecord> {
            getUnconfirmedSharesRoundId = roundId
            return unconfirmedShareRecords
        }

        override suspend fun markShareConfirmed(
            roundId: String,
            bundleIndex: Int,
            proposalId: Int,
            shareIndex: Int
        ) {
            markShareRoundId = roundId
            markShareBundleIndex = bundleIndex
            markShareProposalId = proposalId
            markShareIndex = shareIndex
        }

        override suspend fun addSentServers(
            roundId: String,
            bundleIndex: Int,
            proposalId: Int,
            shareIndex: Int,
            newUrls: List<String>
        ) {
            addSentRoundId = roundId
            addSentBundleIndex = bundleIndex
            addSentProposalId = proposalId
            addSentShareIndex = shareIndex
            addSentNewUrls = newUrls
        }

        private fun unused(): Nothing = error("unused")
    }

    private companion object {
        private const val PROOF_BYTES = 3
    }
}
