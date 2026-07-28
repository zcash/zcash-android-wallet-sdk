package cash.z.ecc.android.sdk.internal

import cash.z.ecc.android.sdk.internal.jni.JNI_DELEGATION_PUBLIC_INPUT_COUNT
import cash.z.ecc.android.sdk.internal.jni.JNI_GOVERNANCE_NULLIFIER_COUNT
import cash.z.ecc.android.sdk.internal.jni.JNI_HOTKEY_STORED_SECRET_BYTES_SIZE
import cash.z.ecc.android.sdk.internal.jni.JNI_ORCHARD_RAW_ADDRESS_BYTES_SIZE
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
import cash.z.ecc.android.sdk.internal.model.voting.JniVoteCommitResult
import cash.z.ecc.android.sdk.internal.model.voting.JniVoteRecord
import cash.z.ecc.android.sdk.internal.model.voting.JniVoteSubmission
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
    fun governance_pczt_methods_forward_arguments_and_map_results() =
        runTest {
            val jniResult =
                jniGovernancePczt(
                    pcztBytes = field(GOVERNANCE_PCZT_BYTES_FIXTURE),
                    rk = field(GOVERNANCE_PCZT_RK_FIXTURE),
                    sighash = field(GOVERNANCE_PCZT_SIGHASH_FIXTURE),
                    actionIndex = GOVERNANCE_PCZT_ACTION_INDEX
                )
            val backend =
                RecordingVotingDbBackend(
                    proofResult = jniDelegationProofResult(),
                    submissionResult = jniDelegationSubmissionResult(),
                    governancePcztResult = jniResult
                )
            val db = TypesafeVotingDbImpl(backend)
            val fvkBytes = field(1)
            val hotkeyStoredSecret = hotkeyStoredSecret(GOVERNANCE_PCZT_HOTKEY_SECRET_FIXTURE)
            val seedFingerprint = field(GOVERNANCE_PCZT_SEED_FINGERPRINT_FIXTURE)
            val walletSeed = field(GOVERNANCE_PCZT_WALLET_SEED_FIXTURE)
            val notes = listOf(votingNoteInfo())
            val jniNotes = notes.map { it.toJniNoteInfo() }

            val explicit =
                db.buildGovernancePczt(
                    roundId = "round-explicit",
                    bundleIndex = GOVERNANCE_PCZT_EXPLICIT_BUNDLE_INDEX,
                    fvkBytes = fvkBytes,
                    hotkeyStoredSecret = hotkeyStoredSecret,
                    networkId = 1,
                    accountIndex = GOVERNANCE_PCZT_EXPLICIT_ACCOUNT_INDEX,
                    notes = notes,
                    seedFingerprint = seedFingerprint,
                    roundName = "Round Explicit"
                )
            assertEquals(jniResult.toGovernancePcztResult(), explicit)
            assertEquals("round-explicit", backend.governancePcztRoundId)
            assertEquals(GOVERNANCE_PCZT_EXPLICIT_BUNDLE_INDEX, backend.governancePcztBundleIndex)
            assertContentEquals(fvkBytes, backend.governancePcztFvkBytes)
            assertContentEquals(hotkeyStoredSecret, backend.governancePcztHotkeyStoredSecret)
            assertEquals(1, backend.governancePcztNetworkId)
            assertEquals(GOVERNANCE_PCZT_EXPLICIT_ACCOUNT_INDEX, backend.governancePcztAccountIndex)
            assertEquals(jniNotes, backend.governancePcztNotes)
            assertContentEquals(seedFingerprint, backend.governancePcztSeedFingerprint)
            assertEquals("Round Explicit", backend.governancePcztRoundName)

            val seed =
                db.buildGovernancePcztFromSeed(
                    roundId = "round-seed",
                    bundleIndex = GOVERNANCE_PCZT_FROM_SEED_BUNDLE_INDEX,
                    ufvk = "uview-test",
                    networkId = 0,
                    accountIndex = GOVERNANCE_PCZT_FROM_SEED_ACCOUNT_INDEX,
                    notes = notes,
                    walletSeed = walletSeed,
                    hotkeyStoredSecret = hotkeyStoredSecret,
                    seedFingerprint = seedFingerprint,
                    roundName = "Round Seed"
                )
            assertEquals(jniResult.toGovernancePcztResult(), seed)
            assertEquals("round-seed", backend.governancePcztFromSeedRoundId)
            assertEquals(
                GOVERNANCE_PCZT_FROM_SEED_BUNDLE_INDEX,
                backend.governancePcztFromSeedBundleIndex
            )
            assertEquals("uview-test", backend.governancePcztFromSeedUfvk)
            assertEquals(0, backend.governancePcztFromSeedNetworkId)
            assertEquals(
                GOVERNANCE_PCZT_FROM_SEED_ACCOUNT_INDEX,
                backend.governancePcztFromSeedAccountIndex
            )
            assertEquals(jniNotes, backend.governancePcztFromSeedNotes)
            assertContentEquals(walletSeed, backend.governancePcztFromSeedWalletSeed)
            assertContentEquals(
                hotkeyStoredSecret,
                backend.governancePcztFromSeedHotkeyStoredSecret
            )
            assertContentEquals(seedFingerprint, backend.governancePcztFromSeedSeedFingerprint)
            assertEquals("Round Seed", backend.governancePcztFromSeedRoundName)
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
            val generatedWitnesses = arrayOf(jniWitnessData().copy(position = 11L))
            val backend =
                RecordingVotingDbBackend(
                    proofResult = proofJniResult,
                    submissionResult = submissionJniResult,
                    generatedWitnesses = generatedWitnesses
                )
            val db = TypesafeVotingDbImpl(backend)
            val fvkBytes = field(41)
            val hotkeyStoredSecret = hotkeyStoredSecret(42)
            val seedFingerprint = field(43)
            val spendAuthSig = ByteArray(JNI_SPEND_AUTH_SIG_BYTES_SIZE) { 44 }
            val sighash = field(45)
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
                    fvkBytes = fvkBytes,
                    hotkeyStoredSecret = hotkeyStoredSecret,
                    seedFingerprint = seedFingerprint,
                    accountIndex = 6,
                    roundName = "Round Three"
                ) { progress ->
                    progressValue = progress
                }
            assertEquals("round-3", backend.buildAndProveRoundId)
            assertEquals(4, backend.buildAndProveBundleIndex)
            assertEquals("https://pir.example", backend.buildAndProvePirServerUrl)
            assertEquals(1, backend.buildAndProveNetworkId)
            assertEquals(jniNotes, backend.buildAndProveNotes)
            assertContentEquals(fvkBytes, backend.buildAndProveFvkBytes)
            assertContentEquals(hotkeyStoredSecret, backend.buildAndProveHotkeyStoredSecret)
            assertContentEquals(seedFingerprint, backend.buildAndProveSeedFingerprint)
            assertEquals(6, backend.buildAndProveAccountIndex)
            assertEquals("Round Three", backend.buildAndProveRoundName)
            assertNotNull(backend.buildAndProveProgress).onProgress(0.75)
            assertEquals(0.75, progressValue)
            assertContentEquals(field(13), proof.nfSigned)
            assertContentEquals(field(17), proof.rk)

            // Software and hardware signing converged on a single caller-supplied SpendAuth
            // signature, so there is one submission entry point rather than two.
            val submission =
                db.getDelegationSubmission(
                    roundId = "round-4",
                    bundleIndex = 7,
                    spendAuthSig = spendAuthSig,
                    sighash = sighash
                )
            assertEquals("round-4", backend.submissionRoundId)
            assertEquals(7, backend.submissionBundleIndex)
            assertContentEquals(spendAuthSig, backend.submissionSpendAuthSig)
            assertContentEquals(sighash, backend.submissionSighash)
            assertContentEquals(field(22), submission.rk)
            assertEquals("round-submission", submission.voteRoundId)

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
            // commitVote checks that the commitment it gets back names the bundle that was asked
            // for, so this canned result has to carry the same bundle index the call below
            // passes. The check itself is covered by
            // commit_vote_rejects_a_result_for_a_different_bundle.
            val commitment =
                jniVoteCommitResult(
                    voteCommitment = field(35),
                    proposalId = 2,
                    bundleIndex = 3
                )
            val backend =
                RecordingVotingDbBackend(
                    proofResult = jniDelegationProofResult(),
                    submissionResult = jniDelegationSubmissionResult(),
                    witnessResult = witness,
                    commitmentResult = commitment,
                    syncHeight = 55
                )
            val db = TypesafeVotingDbImpl(backend)
            val hotkeyStoredSecret = hotkeyStoredSecret(1)
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
                db.commitVote(
                    roundId = "round-vote",
                    bundleIndex = 3,
                    hotkeyStoredSecret = hotkeyStoredSecret,
                    networkId = 0,
                    proposalId = 2,
                    choice = 1,
                    numOptions = 3,
                    vcTreePosition = 66,
                    witness = witness
                ) { progress ->
                    progressValue = progress
                }
            assertEquals(commitment, voteCommitment)
            assertEquals("round-vote", backend.commitVoteRoundId)
            assertEquals(3, backend.commitVoteBundleIndex)
            assertContentEquals(hotkeyStoredSecret, backend.commitVoteHotkeyStoredSecret)
            assertEquals(0, backend.commitVoteNetworkId)
            assertEquals(2, backend.commitVoteProposalId)
            assertEquals(1, backend.commitVoteChoice)
            assertEquals(3, backend.commitVoteNumOptions)
            assertEquals(66, backend.commitVoteVcTreePosition)
            assertEquals(witness, backend.commitVoteWitness)
            assertEquals(false, backend.commitVoteSingleShare)
            assertNotNull(backend.commitVoteProgress).onProgress(0.5)
            assertEquals(0.5, progressValue)
        }

    @Test
    fun vote_submission_forwards_arguments_and_maps_results() =
        runTest {
            val submission = jniVoteSubmission(voteRoundId = "round-resend", proposalId = 4)
            val backend =
                RecordingVotingDbBackend(
                    proofResult = jniDelegationProofResult(),
                    submissionResult = jniDelegationSubmissionResult(),
                    submissionVoteResult = submission
                )
            val db = TypesafeVotingDbImpl(backend)

            val result = db.voteSubmission("round-resend", 3, 4)

            assertEquals(submission, result)
            assertEquals("round-resend", backend.voteSubmissionRoundId)
            assertEquals(3, backend.voteSubmissionBundleIndex)
            assertEquals(4, backend.voteSubmissionProposalId)
        }

    @Test
    fun vote_submission_wrapper_rejects_a_malformed_result() =
        runTest {
            val backend =
                RecordingVotingDbBackend(
                    proofResult = jniDelegationProofResult(),
                    submissionResult = jniDelegationSubmissionResult(),
                    submissionVoteResult = jniVoteSubmission(proof = ByteArray(0))
                )
            val db = TypesafeVotingDbImpl(backend)

            val error =
                assertFailsWith<IllegalArgumentException> {
                    db.voteSubmission("round-resend", 1, 1)
                }

            assertTrue(error.message.orEmpty().contains("proof"))
        }

    @Test
    fun recovery_methods_forward_arguments_and_map_results() =
        runTest {
            val commitment = jniVoteCommitResult(voteCommitment = field(31))
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

            // storeVoteTxHash and markVoteSubmitted collapsed into a single conflict-checked
            // call; the transaction hash is what makes a vote submitted.
            db.markVoteSubmitted("round-recovery", 1, 2, "vote-tx")
            assertEquals("round-recovery", backend.markVoteRoundId)
            assertEquals(1, backend.markVoteBundleIndex)
            assertEquals(2, backend.markVoteProposalId)
            assertEquals("vote-tx", backend.markVoteTxHash)

            assertEquals(
                VotingTxHashLookup.Found("vote-tx"),
                db.getVoteTxHash("round-recovery", 1, 2)
            )
            assertEquals("round-recovery", backend.getVoteTxRoundId)
            assertEquals(1, backend.getVoteTxBundleIndex)
            assertEquals(2, backend.getVoteTxProposalId)

            db.recordVcPosition("round-recovery", 1, 2, 99)
            assertEquals("round-recovery", backend.recordVcPositionRoundId)
            assertEquals(1, backend.recordVcPositionBundleIndex)
            assertEquals(2, backend.recordVcPositionProposalId)
            assertEquals(99, backend.recordVcPositionValue)

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
                submitAt = 123
            )
            assertEquals("round-recovery", backend.recordShareRoundId)
            assertEquals(1, backend.recordShareBundleIndex)
            assertEquals(2, backend.recordShareProposalId)
            assertEquals(3, backend.recordShareIndex)
            assertEquals(listOf("https://helper.example"), backend.recordShareSentToUrls)
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

    // Replaces store_commitment_bundle_rejects_mismatched_bundle_index. There is no
    // storeCommitmentBundle any more — the recovery bundle is library-owned and has no public
    // writer — so the bundle-index agreement check moved onto the commitVote result, which is
    // where a mismatch could now originate.
    @Test
    fun commit_vote_rejects_a_result_for_a_different_bundle() =
        runTest {
            val backend =
                RecordingVotingDbBackend(
                    proofResult = jniDelegationProofResult(),
                    submissionResult = jniDelegationSubmissionResult(),
                    commitmentResult = jniVoteCommitResult(bundleIndex = 1)
                )
            val db = TypesafeVotingDbImpl(backend)

            val error =
                assertFailsWith<IllegalArgumentException> {
                    db.commitVote(
                        roundId = "round-vote",
                        bundleIndex = 2,
                        hotkeyStoredSecret = hotkeyStoredSecret(1),
                        networkId = 0,
                        proposalId = 1,
                        choice = 1,
                        numOptions = 3,
                        vcTreePosition = 99,
                        witness = jniVanWitness()
                    )
                }

            assertTrue(error.message.orEmpty().contains("bundleIndex"))
        }

    @Test
    fun missing_tx_hashes_map_to_typed_missing_state() =
        runTest {
            val backend =
                RecordingVotingDbBackend(
                    proofResult = jniDelegationProofResult(),
                    submissionResult = jniDelegationSubmissionResult()
                )
            val db = TypesafeVotingDbImpl(backend)

            assertEquals(VotingTxHashLookup.Missing, db.getDelegationTxHash("round", 0))
            assertEquals(VotingTxHashLookup.Missing, db.getVoteTxHash("round", 0, 0))
        }

    @Test
    fun unexpected_recovery_lookup_exceptions_still_fail() =
        runTest {
            val backend =
                RecordingVotingDbBackend(
                    proofResult = jniDelegationProofResult(),
                    submissionResult = jniDelegationSubmissionResult(),
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
    fun commit_vote_wrapper_rejects_invalid_commitment_result() =
        runTest {
            val backend =
                RecordingVotingDbBackend(
                    proofResult = jniDelegationProofResult(),
                    submissionResult = jniDelegationSubmissionResult(),
                    commitmentResult = jniVoteCommitResult(encShares = emptyList())
                )
            val db = TypesafeVotingDbImpl(backend)

            val error =
                assertFailsWith<IllegalArgumentException> {
                    db.commitVote(
                        roundId = "round-vote",
                        bundleIndex = 3,
                        hotkeyStoredSecret = hotkeyStoredSecret(1),
                        networkId = 0,
                        proposalId = 2,
                        choice = 1,
                        numOptions = 3,
                        vcTreePosition = 99,
                        witness = jniVanWitness()
                    )
                }

            assertTrue(error.message.orEmpty().contains("encShares"))
        }

    @Test
    fun generate_hotkey_wrapper_rejects_malformed_hotkey_material() =
        runTest {
            val backend =
                RecordingVotingDbBackend(
                    proofResult = jniDelegationProofResult(),
                    submissionResult = jniDelegationSubmissionResult(),
                    hotkeyResult =
                        JniVotingHotkey(
                            storedSecret = byteArrayOf(1, 2, 3),
                            rawOrchardAddress = ByteArray(JNI_ORCHARD_RAW_ADDRESS_BYTES_SIZE),
                            addressIndex = 0
                        )
                )
            val db = TypesafeVotingDbImpl(backend)

            val error =
                assertFailsWith<IllegalArgumentException> {
                    db.generateHotkey("round-vote", 0)
                }

            assertTrue(error.message.orEmpty().contains("storedSecret"))
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

    private fun jniGovernancePczt(
        pcztBytes: ByteArray =
            ByteArray(PROOF_BYTES) { DEFAULT_GOVERNANCE_PCZT_BYTES_FIXTURE.toByte() },
        rk: ByteArray = field(DEFAULT_GOVERNANCE_PCZT_RK_FIXTURE),
        sighash: ByteArray = field(DEFAULT_GOVERNANCE_PCZT_SIGHASH_FIXTURE),
        actionIndex: Int = 1
    ) = JniGovernancePczt(
        pcztBytes = pcztBytes,
        rk = rk,
        sighash = sighash,
        actionIndex = actionIndex
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

    private fun jniVoteCommitResult(
        vanNullifier: ByteArray = field(10),
        voteAuthorityNoteNew: ByteArray = field(11),
        voteCommitment: ByteArray = field(12),
        proposalId: Int = 1,
        proof: ByteArray = ByteArray(PROOF_BYTES) { 13 },
        encShares: List<JniWireEncryptedShare> = wireShares(),
        bundleIndex: Int = 1,
        anchorHeight: Long = 2,
        rVpk: ByteArray = field(17),
        voteAuthSig: ByteArray = ByteArray(JNI_SPEND_AUTH_SIG_BYTES_SIZE) { 18 },
        sharePayloads: List<JniSharePayload> = sharePayloads()
    ) = JniVoteCommitResult(
        vanNullifier = vanNullifier,
        voteAuthorityNoteNew = voteAuthorityNoteNew,
        voteCommitment = voteCommitment,
        proposalId = proposalId,
        bundleIndex = bundleIndex,
        proof = proof,
        anchorHeight = anchorHeight,
        rVpk = rVpk,
        voteAuthSig = voteAuthSig,
        encShares = encShares,
        sharePayloads = sharePayloads
    )

    private fun jniVoteSubmission(
        voteRoundId: String = "round-vote",
        proposalId: Int = 1,
        bundleIndex: Int = 1,
        proof: ByteArray = ByteArray(PROOF_BYTES) { 13 },
        anchorHeight: Long = 2
    ) = JniVoteSubmission(
        voteRoundId = voteRoundId,
        proposalId = proposalId,
        bundleIndex = bundleIndex,
        vanNullifier = field(10),
        voteAuthorityNoteNew = field(11),
        voteCommitment = field(12),
        proof = proof,
        rVpk = field(17),
        voteAuthSig = ByteArray(JNI_SPEND_AUTH_SIG_BYTES_SIZE) { 18 },
        anchorHeight = anchorHeight
    )

    private fun sharePayloads(count: Int = JNI_VOTE_SHARE_COUNT) =
        List(count) { index ->
            JniSharePayload(
                sharesHash = field(14),
                proposalId = 1,
                voteDecision = 1,
                encShare = wireShares()[index],
                treePosition = 66,
                allEncShares = wireShares(),
                shareComms = fieldElements(JNI_VOTE_SHARE_COUNT, 16),
                primaryBlind = field(15)
            )
        }

    private fun hotkeyStoredSecret(byteValue: Int) =
        ByteArray(JNI_HOTKEY_STORED_SECRET_BYTES_SIZE) { byteValue.toByte() }

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

        override suspend fun extractOrchardFvkFromUfvk(
            ufvk: String,
            networkId: Int
        ): ByteArray = unused()

        override suspend fun deriveHotkeyRawAddress(
            hotkeyStoredSecret: ByteArray,
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
        private val generatedWitnesses: Array<JniWitnessData> = emptyArray(),
        private val witnessResult: JniVanWitness =
            JniVanWitness(
                authPath = List(JNI_VAN_WITNESS_PATH_DEPTH) { ByteArray(JNI_PROTOCOL_FIELD_BYTES_SIZE) },
                position = 1,
                anchorHeight = 2
            ),
        private val commitmentResult: JniVoteCommitResult = defaultCommitResult(),
        private val submissionVoteResult: JniVoteSubmission = defaultVoteSubmission(),
        private val hotkeyResult: JniVotingHotkey =
            JniVotingHotkey(
                storedSecret = ByteArray(JNI_HOTKEY_STORED_SECRET_BYTES_SIZE),
                rawOrchardAddress = ByteArray(JNI_ORCHARD_RAW_ADDRESS_BYTES_SIZE),
                addressIndex = 0
            ),
        private val syncHeight: Long = 1,
        private val delegationTxHash: String? = null,
        private val voteTxHash: String? = null,
        private val commitmentRecord: JniCommitmentBundleRecord? = null,
        private val shareRecords: Array<JniShareDelegationRecord> = emptyArray(),
        private val unconfirmedShareRecords: Array<JniShareDelegationRecord> = emptyArray(),
        private val governancePcztResult: JniGovernancePczt =
            JniGovernancePczt(
                pcztBytes = ByteArray(PROOF_BYTES),
                rk = ByteArray(JNI_PROTOCOL_FIELD_BYTES_SIZE),
                sighash = ByteArray(JNI_PROTOCOL_FIELD_BYTES_SIZE),
                actionIndex = 0
            ),
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
        var governancePcztRoundId: String? = null
        var governancePcztBundleIndex: Int? = null
        var governancePcztFvkBytes: ByteArray = ByteArray(0)
        var governancePcztHotkeyStoredSecret: ByteArray = ByteArray(0)
        var governancePcztNetworkId: Int? = null
        var governancePcztAccountIndex: Int? = null
        var governancePcztNotes: List<JniNoteInfo>? = null
        var governancePcztSeedFingerprint: ByteArray = ByteArray(0)
        var governancePcztRoundName: String? = null
        var governancePcztFromSeedRoundId: String? = null
        var governancePcztFromSeedBundleIndex: Int? = null
        var governancePcztFromSeedUfvk: String? = null
        var governancePcztFromSeedNetworkId: Int? = null
        var governancePcztFromSeedAccountIndex: Int? = null
        var governancePcztFromSeedNotes: List<JniNoteInfo>? = null
        var governancePcztFromSeedWalletSeed: ByteArray = ByteArray(0)
        var governancePcztFromSeedHotkeyStoredSecret: ByteArray = ByteArray(0)
        var governancePcztFromSeedSeedFingerprint: ByteArray = ByteArray(0)
        var governancePcztFromSeedRoundName: String? = null
        var buildAndProveRoundId: String? = null
        var buildAndProveBundleIndex: Int? = null
        var buildAndProvePirServerUrl: String? = null
        var buildAndProveNetworkId: Int? = null
        var buildAndProveNotes: List<JniNoteInfo>? = null
        var buildAndProveFvkBytes: ByteArray = ByteArray(0)
        var buildAndProveHotkeyStoredSecret: ByteArray = ByteArray(0)
        var buildAndProveSeedFingerprint: ByteArray = ByteArray(0)
        var buildAndProveAccountIndex: Int? = null
        var buildAndProveRoundName: String? = null
        var buildAndProveProgress: VotingProofProgressCallback? = null
        var submissionRoundId: String? = null
        var submissionBundleIndex: Int? = null
        var submissionSpendAuthSig: ByteArray = ByteArray(0)
        var submissionSighash: ByteArray = ByteArray(0)
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
        var commitVoteRoundId: String? = null
        var commitVoteBundleIndex: Int? = null
        var commitVoteHotkeyStoredSecret: ByteArray = ByteArray(0)
        var commitVoteNetworkId: Int? = null
        var commitVoteProposalId: Int? = null
        var commitVoteChoice: Int? = null
        var commitVoteNumOptions: Int? = null
        var commitVoteVcTreePosition: Long? = null
        var commitVoteWitness: JniVanWitness? = null
        var commitVoteSingleShare: Boolean? = null
        var commitVoteProgress: VotingProofProgressCallback? = null
        var voteSubmissionRoundId: String? = null
        var voteSubmissionBundleIndex: Int? = null
        var voteSubmissionProposalId: Int? = null
        var recordVcPositionRoundId: String? = null
        var recordVcPositionBundleIndex: Int? = null
        var recordVcPositionProposalId: Int? = null
        var recordVcPositionValue: Long? = null
        var storeDelegationTxRoundId: String? = null
        var storeDelegationTxBundleIndex: Int? = null
        var storeDelegationTxHash: String? = null
        var getDelegationTxRoundId: String? = null
        var getDelegationTxBundleIndex: Int? = null
        var markVoteRoundId: String? = null
        var markVoteBundleIndex: Int? = null
        var markVoteProposalId: Int? = null
        var markVoteTxHash: String? = null
        var getVoteTxRoundId: String? = null
        var getVoteTxBundleIndex: Int? = null
        var getVoteTxProposalId: Int? = null
        var getCommitmentRoundId: String? = null
        var getCommitmentBundleIndex: Int? = null
        var getCommitmentProposalId: Int? = null
        var clearRecoveryRoundId: String? = null
        var recordShareRoundId: String? = null
        var recordShareBundleIndex: Int? = null
        var recordShareProposalId: Int? = null
        var recordShareIndex: Int? = null
        var recordShareSentToUrls: List<String>? = null
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
            networkId: Int,
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
            networkId: Int
        ): JniVotingHotkey = hotkeyResult

        override suspend fun buildGovernancePczt(
            roundId: String,
            bundleIndex: Int,
            fvkBytes: ByteArray,
            hotkeyStoredSecret: ByteArray,
            networkId: Int,
            accountIndex: Int,
            notes: List<JniNoteInfo>,
            seedFingerprint: ByteArray,
            roundName: String
        ): JniGovernancePczt {
            governancePcztRoundId = roundId
            governancePcztBundleIndex = bundleIndex
            governancePcztFvkBytes = fvkBytes
            governancePcztHotkeyStoredSecret = hotkeyStoredSecret
            governancePcztNetworkId = networkId
            governancePcztAccountIndex = accountIndex
            governancePcztNotes = notes
            governancePcztSeedFingerprint = seedFingerprint
            governancePcztRoundName = roundName
            return governancePcztResult
        }

        override suspend fun buildGovernancePcztFromSeed(
            roundId: String,
            bundleIndex: Int,
            ufvk: String,
            networkId: Int,
            accountIndex: Int,
            notes: List<JniNoteInfo>,
            walletSeed: ByteArray,
            hotkeyStoredSecret: ByteArray,
            seedFingerprint: ByteArray,
            roundName: String
        ): JniGovernancePczt {
            governancePcztFromSeedRoundId = roundId
            governancePcztFromSeedBundleIndex = bundleIndex
            governancePcztFromSeedUfvk = ufvk
            governancePcztFromSeedNetworkId = networkId
            governancePcztFromSeedAccountIndex = accountIndex
            governancePcztFromSeedNotes = notes
            governancePcztFromSeedWalletSeed = walletSeed
            governancePcztFromSeedHotkeyStoredSecret = hotkeyStoredSecret
            governancePcztFromSeedSeedFingerprint = seedFingerprint
            governancePcztFromSeedRoundName = roundName
            return governancePcztResult
        }

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
            fvkBytes: ByteArray,
            hotkeyStoredSecret: ByteArray,
            seedFingerprint: ByteArray,
            accountIndex: Int,
            roundName: String,
            proofProgress: VotingProofProgressCallback?
        ): JniDelegationProofResult {
            buildAndProveRoundId = roundId
            buildAndProveBundleIndex = bundleIndex
            buildAndProvePirServerUrl = pirServerUrl
            buildAndProveNetworkId = networkId
            buildAndProveNotes = notes
            buildAndProveFvkBytes = fvkBytes
            buildAndProveHotkeyStoredSecret = hotkeyStoredSecret
            buildAndProveSeedFingerprint = seedFingerprint
            buildAndProveAccountIndex = accountIndex
            buildAndProveRoundName = roundName
            buildAndProveProgress = proofProgress
            return proofResult
        }

        override suspend fun getDelegationSubmission(
            roundId: String,
            bundleIndex: Int,
            spendAuthSig: ByteArray,
            sighash: ByteArray
        ): JniDelegationSubmissionResult {
            submissionRoundId = roundId
            submissionBundleIndex = bundleIndex
            submissionSpendAuthSig = spendAuthSig
            submissionSighash = sighash
            return submissionResult
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

        override suspend fun commitVote(
            roundId: String,
            bundleIndex: Int,
            hotkeyStoredSecret: ByteArray,
            networkId: Int,
            proposalId: Int,
            choice: Int,
            numOptions: Int,
            vcTreePosition: Long,
            witness: JniVanWitness,
            singleShare: Boolean,
            proofProgress: VotingProofProgressCallback?
        ): JniVoteCommitResult {
            commitVoteRoundId = roundId
            commitVoteBundleIndex = bundleIndex
            commitVoteHotkeyStoredSecret = hotkeyStoredSecret
            commitVoteNetworkId = networkId
            commitVoteProposalId = proposalId
            commitVoteChoice = choice
            commitVoteNumOptions = numOptions
            commitVoteVcTreePosition = vcTreePosition
            commitVoteWitness = witness
            commitVoteSingleShare = singleShare
            commitVoteProgress = proofProgress
            return commitmentResult
        }

        override suspend fun voteSubmission(
            roundId: String,
            bundleIndex: Int,
            proposalId: Int
        ): JniVoteSubmission {
            voteSubmissionRoundId = roundId
            voteSubmissionBundleIndex = bundleIndex
            voteSubmissionProposalId = proposalId
            return submissionVoteResult
        }

        override suspend fun recordVcPosition(
            roundId: String,
            bundleIndex: Int,
            proposalId: Int,
            vcTreePosition: Long
        ) {
            recordVcPositionRoundId = roundId
            recordVcPositionBundleIndex = bundleIndex
            recordVcPositionProposalId = proposalId
            recordVcPositionValue = vcTreePosition
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

        override suspend fun markVoteSubmitted(
            roundId: String,
            bundleIndex: Int,
            proposalId: Int,
            txHash: String
        ) {
            markVoteRoundId = roundId
            markVoteBundleIndex = bundleIndex
            markVoteProposalId = proposalId
            markVoteTxHash = txHash
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
            submitAt: Long
        ) {
            recordShareRoundId = roundId
            recordShareBundleIndex = bundleIndex
            recordShareProposalId = proposalId
            recordShareIndex = shareIndex
            recordShareSentToUrls = sentToUrls
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
        private const val GOVERNANCE_PCZT_BYTES_FIXTURE = 41
        private const val GOVERNANCE_PCZT_RK_FIXTURE = 43
        private const val GOVERNANCE_PCZT_SIGHASH_FIXTURE = 44
        private const val GOVERNANCE_PCZT_ACTION_INDEX = 2
        private const val GOVERNANCE_PCZT_HOTKEY_SECRET_FIXTURE = 4
        private const val GOVERNANCE_PCZT_SEED_FINGERPRINT_FIXTURE = 7
        private const val GOVERNANCE_PCZT_WALLET_SEED_FIXTURE = 10
        private const val GOVERNANCE_PCZT_EXPLICIT_BUNDLE_INDEX = 2
        private const val GOVERNANCE_PCZT_EXPLICIT_ACCOUNT_INDEX = 3
        private const val GOVERNANCE_PCZT_FROM_SEED_BUNDLE_INDEX = 4
        private const val GOVERNANCE_PCZT_FROM_SEED_ACCOUNT_INDEX = 5
        private const val DEFAULT_GOVERNANCE_PCZT_BYTES_FIXTURE = 20
        private const val DEFAULT_GOVERNANCE_PCZT_RK_FIXTURE = 21
        private const val DEFAULT_GOVERNANCE_PCZT_SIGHASH_FIXTURE = 22

        private fun zeroedWireShares() =
            List(JNI_VOTE_SHARE_COUNT) { index ->
                JniWireEncryptedShare(
                    c1 = ByteArray(JNI_PROTOCOL_FIELD_BYTES_SIZE),
                    c2 = ByteArray(JNI_PROTOCOL_FIELD_BYTES_SIZE),
                    shareIndex = index
                )
            }

        private fun defaultCommitResult() =
            JniVoteCommitResult(
                vanNullifier = ByteArray(JNI_PROTOCOL_FIELD_BYTES_SIZE),
                voteAuthorityNoteNew = ByteArray(JNI_PROTOCOL_FIELD_BYTES_SIZE),
                voteCommitment = ByteArray(JNI_PROTOCOL_FIELD_BYTES_SIZE),
                proposalId = 1,
                bundleIndex = 1,
                proof = ByteArray(PROOF_BYTES),
                anchorHeight = 2,
                rVpk = ByteArray(JNI_PROTOCOL_FIELD_BYTES_SIZE),
                voteAuthSig = ByteArray(JNI_SPEND_AUTH_SIG_BYTES_SIZE),
                encShares = zeroedWireShares(),
                sharePayloads =
                    List(JNI_VOTE_SHARE_COUNT) {
                        JniSharePayload(
                            sharesHash = ByteArray(JNI_PROTOCOL_FIELD_BYTES_SIZE),
                            proposalId = 1,
                            voteDecision = 1,
                            encShare = zeroedWireShares().first(),
                            treePosition = 0,
                            allEncShares = zeroedWireShares(),
                            shareComms =
                                List(JNI_VOTE_SHARE_COUNT) {
                                    ByteArray(JNI_PROTOCOL_FIELD_BYTES_SIZE)
                                },
                            primaryBlind = ByteArray(JNI_PROTOCOL_FIELD_BYTES_SIZE)
                        )
                    }
            )

        private fun defaultVoteSubmission() =
            JniVoteSubmission(
                voteRoundId = "round-vote",
                proposalId = 1,
                bundleIndex = 1,
                vanNullifier = ByteArray(JNI_PROTOCOL_FIELD_BYTES_SIZE),
                voteAuthorityNoteNew = ByteArray(JNI_PROTOCOL_FIELD_BYTES_SIZE),
                voteCommitment = ByteArray(JNI_PROTOCOL_FIELD_BYTES_SIZE),
                proof = ByteArray(PROOF_BYTES),
                rVpk = ByteArray(JNI_PROTOCOL_FIELD_BYTES_SIZE),
                voteAuthSig = ByteArray(JNI_SPEND_AUTH_SIG_BYTES_SIZE),
                anchorHeight = 2
            )
    }
}
