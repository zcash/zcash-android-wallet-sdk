package cash.z.ecc.android.sdk.internal

import cash.z.ecc.android.sdk.internal.jni.VotingRustBackend
import cash.z.ecc.android.sdk.internal.model.voting.FfiBundleSetupResult
import cash.z.ecc.android.sdk.internal.model.voting.FfiRoundState
import cash.z.ecc.android.sdk.internal.model.voting.FfiVotingHotkey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Concrete implementation of [TypesafeVotingBackend] that delegates to [VotingRustBackend] JNI.
 *
 * All operations dispatch to [Dispatchers.IO] since they touch SQLite or perform
 * long-running ZK proof generation.
 */
class TypesafeVotingBackendImpl : TypesafeVotingBackend {

    // ─── Database lifecycle ────────────────────────────────────────────────────

    override suspend fun openVotingDb(dbPath: String): Long =
        io { VotingRustBackend.openVotingDb(dbPath) }

    override suspend fun closeVotingDb(dbHandle: Long) =
        io { VotingRustBackend.closeVotingDb(dbHandle) }

    override suspend fun setWalletId(dbHandle: Long, walletId: String) =
        io { VotingRustBackend.setWalletId(dbHandle, walletId); Unit }

    // ─── Round management ─────────────────────────────────────────────────────

    override suspend fun initRound(
        dbHandle: Long,
        roundId: String,
        snapshotHeight: Long,
        eaPK: ByteArray,
        ncRoot: ByteArray,
        nullifierIMTRoot: ByteArray,
        sessionJson: String?
    ) = io {
        check(VotingRustBackend.initRound(dbHandle, roundId, snapshotHeight, eaPK, ncRoot, nullifierIMTRoot, sessionJson)) {
            "initRound failed for roundId=$roundId"
        }
    }

    override suspend fun getRoundState(dbHandle: Long, roundId: String): FfiRoundState? =
        io { VotingRustBackend.getRoundState(dbHandle, roundId) }

    override suspend fun listRoundsJson(dbHandle: Long): String =
        io { VotingRustBackend.listRoundsJson(dbHandle) }

    override suspend fun clearRound(dbHandle: Long, roundId: String) =
        io {
            check(VotingRustBackend.clearRound(dbHandle, roundId)) {
                "clearRound failed for roundId=$roundId"
            }
        }

    // ─── Note setup ───────────────────────────────────────────────────────────

    override suspend fun setupBundles(
        dbHandle: Long,
        roundId: String,
        notesJson: String
    ): FfiBundleSetupResult =
        io {
            VotingRustBackend.setupBundles(dbHandle, roundId, notesJson)
                ?: error("setupBundles returned null for roundId=$roundId")
        }

    // ─── Hotkey ───────────────────────────────────────────────────────────────

    override suspend fun generateHotkey(
        dbHandle: Long,
        roundId: String,
        seed: ByteArray
    ): FfiVotingHotkey =
        io {
            VotingRustBackend.generateHotkey(dbHandle, roundId, seed)
                ?: error("generateHotkey returned null for roundId=$roundId")
        }

    // ─── Witnesses ────────────────────────────────────────────────────────────

    override suspend fun storeWitnesses(
        dbHandle: Long,
        roundId: String,
        bundleIndex: Int,
        witnessesJson: String
    ) = io {
        check(VotingRustBackend.storeWitnesses(dbHandle, roundId, bundleIndex, witnessesJson)) {
            "storeWitnesses failed for roundId=$roundId bundle=$bundleIndex"
        }
    }

    // ─── Governance PCZT ─────────────────────────────────────────────────────

    override suspend fun buildGovernancePczt(
        dbHandle: Long,
        roundId: String,
        bundleIndex: Int,
        ufvk: String,
        networkId: Int,
        accountIndex: Int,
        notesJson: String,
        hotkeyRawSeed: ByteArray,
        seedFingerprint: ByteArray,
        roundName: String,
        addressIndex: Int
    ): GovernancePcztResult =
        io {
            val json = VotingRustBackend.buildGovernancePcztJson(
                dbHandle, roundId, bundleIndex, ufvk, networkId, accountIndex,
                notesJson, hotkeyRawSeed, seedFingerprint, roundName, addressIndex
            ) ?: error("buildGovernancePczt returned null")

            val obj = org.json.JSONObject(json)
            GovernancePcztResult(
                pcztBytes = hexDec(obj.getString("pczt_bytes")),
                rk = hexDec(obj.getString("rk")),
                sighash = hexDec(obj.getString("pczt_sighash")),
                actionIndex = obj.getInt("action_index")
            )
        }

    override suspend fun extractPcztSighash(pcztBytes: ByteArray): ByteArray =
        io {
            VotingRustBackend.extractPcztSighash(pcztBytes)
                ?: error("extractPcztSighash returned null")
        }

    override suspend fun extractSpendAuthSig(
        signedPcztBytes: ByteArray,
        actionIndex: Int
    ): ByteArray =
        io {
            VotingRustBackend.extractSpendAuthSig(signedPcztBytes, actionIndex)
                ?: error("extractSpendAuthSig returned null")
        }

    // ─── Tree state + note witnesses ─────────────────────────────────────────

    override suspend fun storeTreeState(
        dbHandle: Long,
        roundId: String,
        treeStateBytes: ByteArray
    ) = io {
        check(VotingRustBackend.storeTreeState(dbHandle, roundId, treeStateBytes)) {
            "storeTreeState failed for roundId=$roundId"
        }
    }

    override suspend fun getWalletNotes(
        walletDbPath: String,
        snapshotHeight: Long,
        networkId: Int,
        accountUuidBytes: ByteArray
    ): String =
        io {
            VotingRustBackend.getWalletNotesJson(walletDbPath, snapshotHeight, networkId, accountUuidBytes)
                ?: error("getWalletNotes returned null")
        }

    override suspend fun generateNoteWitnesses(
        dbHandle: Long,
        roundId: String,
        bundleIndex: Int,
        walletDbPath: String,
        notesJson: String
    ): String =
        io {
            VotingRustBackend.generateNoteWitnessesJson(dbHandle, roundId, bundleIndex, walletDbPath, notesJson)
                ?: error("generateNoteWitnesses returned null for bundle=$bundleIndex")
        }

    // ─── Delegation proof ─────────────────────────────────────────────────────

    override suspend fun buildAndProveDelegation(
        dbHandle: Long,
        roundId: String,
        bundleIndex: Int,
        pirServerUrl: String,
        networkId: Int,
        notesJson: String,
        hotkeyRawSeed: ByteArray
    ): DelegationProofResult =
        io {
            val json = VotingRustBackend.buildAndProveDelegationJson(
                dbHandle, roundId, bundleIndex, pirServerUrl, networkId, notesJson, hotkeyRawSeed
            ) ?: error("buildAndProveDelegation returned null")

            val obj = org.json.JSONObject(json)
            val govNullifiers = obj.getJSONArray("gov_nullifiers")
                .let { arr -> (0 until arr.length()).map { hexDec(arr.getString(it)) } }
            val publicInputs = obj.getJSONArray("public_inputs")
                .let { arr -> (0 until arr.length()).map { hexDec(arr.getString(it)) } }

            DelegationProofResult(
                proof = hexDec(obj.getString("proof")),
                publicInputs = publicInputs,
                nfSigned = hexDec(obj.getString("nf_signed")),
                cmxNew = hexDec(obj.getString("cmx_new")),
                govNullifiers = govNullifiers,
                vanComm = hexDec(obj.getString("van_comm")),
                rk = hexDec(obj.getString("rk"))
            )
        }

    // ─── Delegation submission ─────────────────────────────────────────────────

    override suspend fun getDelegationSubmission(
        dbHandle: Long,
        roundId: String,
        bundleIndex: Int,
        senderSeed: ByteArray,
        networkId: Int,
        accountIndex: Int
    ): DelegationSubmissionResult =
        io {
            val json = VotingRustBackend.getDelegationSubmissionJson(
                dbHandle, roundId, bundleIndex, senderSeed, networkId, accountIndex
            ) ?: error("getDelegationSubmission returned null")
            parseDelegationSubmission(json)
        }

    override suspend fun getDelegationSubmissionWithKeystoneSig(
        dbHandle: Long,
        roundId: String,
        bundleIndex: Int,
        keystoneSig: ByteArray,
        keystoneSighash: ByteArray
    ): DelegationSubmissionResult =
        io {
            val json = VotingRustBackend.getDelegationSubmissionWithKeystoneSigJson(
                dbHandle, roundId, bundleIndex, keystoneSig, keystoneSighash
            ) ?: error("getDelegationSubmissionWithKeystoneSig returned null")
            parseDelegationSubmission(json)
        }

    // ─── Recovery state ─────────────────────────────────────────────────────

    override suspend fun storeDelegationTxHash(
        dbHandle: Long,
        roundId: String,
        bundleIndex: Int,
        txHash: String
    ) = io {
        check(VotingRustBackend.storeDelegationTxHash(dbHandle, roundId, bundleIndex, txHash)) {
            "storeDelegationTxHash failed"
        }
    }

    override suspend fun getDelegationTxHash(
        dbHandle: Long,
        roundId: String,
        bundleIndex: Int
    ): String? = io {
        VotingRustBackend.getDelegationTxHash(dbHandle, roundId, bundleIndex)
    }

    override suspend fun storeVoteTxHash(
        dbHandle: Long,
        roundId: String,
        bundleIndex: Int,
        proposalId: Int,
        txHash: String
    ) = io {
        check(VotingRustBackend.storeVoteTxHash(dbHandle, roundId, bundleIndex, proposalId, txHash)) {
            "storeVoteTxHash failed"
        }
    }

    override suspend fun getVoteTxHash(
        dbHandle: Long,
        roundId: String,
        bundleIndex: Int,
        proposalId: Int
    ): String? = io {
        VotingRustBackend.getVoteTxHash(dbHandle, roundId, bundleIndex, proposalId)
    }

    override suspend fun storeCommitmentBundle(
        dbHandle: Long,
        roundId: String,
        bundleIndex: Int,
        proposalId: Int,
        bundleJson: String,
        vcTreePosition: Long
    ) = io {
        check(
            VotingRustBackend.storeCommitmentBundle(
                dbHandle,
                roundId,
                bundleIndex,
                proposalId,
                bundleJson,
                vcTreePosition
            )
        ) {
            "storeCommitmentBundle failed"
        }
    }

    override suspend fun getCommitmentBundle(
        dbHandle: Long,
        roundId: String,
        bundleIndex: Int,
        proposalId: Int
    ): CommitmentBundleRecord? = io {
        VotingRustBackend.getCommitmentBundleJson(
            dbHandle,
            roundId,
            bundleIndex,
            proposalId
        )?.let { json ->
            val obj = org.json.JSONObject(json)
            CommitmentBundleRecord(
                bundleJson = obj.getString("bundle_json"),
                vcTreePosition = obj.getLong("vc_tree_position")
            )
        }
    }

    override suspend fun clearRecoveryState(
        dbHandle: Long,
        roundId: String
    ) = io {
        check(VotingRustBackend.clearRecoveryState(dbHandle, roundId)) {
            "clearRecoveryState failed"
        }
    }

    override suspend fun recordShareDelegation(
        dbHandle: Long,
        roundId: String,
        bundleIndex: Int,
        proposalId: Int,
        shareIndex: Int,
        sentToUrls: List<String>,
        nullifier: ByteArray,
        submitAt: Long
    ) = io {
        val sentToUrlsJson = org.json.JSONArray(sentToUrls).toString()
        check(
            VotingRustBackend.recordShareDelegation(
                dbHandle,
                roundId,
                bundleIndex,
                proposalId,
                shareIndex,
                sentToUrlsJson,
                nullifier,
                submitAt
            )
        ) {
            "recordShareDelegation failed"
        }
    }

    override suspend fun getShareDelegations(
        dbHandle: Long,
        roundId: String
    ): List<ShareDelegationRecord> = io {
        val json = VotingRustBackend.getShareDelegationsJson(dbHandle, roundId)
        val arr = org.json.JSONArray(json)
        (0 until arr.length()).map { index ->
            val obj = arr.getJSONObject(index)
            val sentToUrlsArray = obj.getJSONArray("sent_to_urls")
            ShareDelegationRecord(
                roundId = obj.getString("round_id"),
                bundleIndex = obj.getInt("bundle_index"),
                proposalId = obj.getInt("proposal_id"),
                shareIndex = obj.getInt("share_index"),
                sentToUrls = (0 until sentToUrlsArray.length()).map(sentToUrlsArray::getString),
                nullifier = hexDec(obj.getString("nullifier")),
                confirmed = obj.getBoolean("confirmed"),
                submitAt = obj.getLong("submit_at"),
                createdAt = obj.getLong("created_at")
            )
        }
    }

    override suspend fun markShareConfirmed(
        dbHandle: Long,
        roundId: String,
        bundleIndex: Int,
        proposalId: Int,
        shareIndex: Int
    ) = io {
        check(VotingRustBackend.markShareConfirmed(dbHandle, roundId, bundleIndex, proposalId, shareIndex)) {
            "markShareConfirmed failed"
        }
    }

    override suspend fun addSentServers(
        dbHandle: Long,
        roundId: String,
        bundleIndex: Int,
        proposalId: Int,
        shareIndex: Int,
        newUrls: List<String>
    ) = io {
        val newUrlsJson = org.json.JSONArray(newUrls).toString()
        check(
            VotingRustBackend.addSentServers(
                dbHandle,
                roundId,
                bundleIndex,
                proposalId,
                shareIndex,
                newUrlsJson
            )
        ) {
            "addSentServers failed"
        }
    }

    override suspend fun computeShareNullifier(
        voteCommitment: ByteArray,
        shareIndex: Int,
        blind: ByteArray
    ): ByteArray = io {
        VotingRustBackend.computeShareNullifier(voteCommitment, shareIndex, blind)
            ?: error("computeShareNullifier returned null")
    }

    // ─── Vote commitment tree ─────────────────────────────────────────────────

    override suspend fun syncVoteTree(
        dbHandle: Long,
        roundId: String,
        nodeUrl: String
    ): Long = io { VotingRustBackend.syncVoteTree(dbHandle, roundId, nodeUrl) }

    override suspend fun storeVanPosition(
        dbHandle: Long,
        roundId: String,
        bundleIndex: Int,
        position: Int
    ) = io {
        check(VotingRustBackend.storeVanPosition(dbHandle, roundId, bundleIndex, position)) {
            "storeVanPosition failed"
        }
    }

    override suspend fun generateVanWitnessJson(
        dbHandle: Long,
        roundId: String,
        bundleIndex: Int,
        anchorHeight: Int
    ): String =
        io {
            VotingRustBackend.generateVanWitnessJson(dbHandle, roundId, bundleIndex, anchorHeight)
                ?: error("generateVanWitnessJson returned null")
        }

    // ─── Vote commitment ──────────────────────────────────────────────────────

    override suspend fun buildVoteCommitment(
        dbHandle: Long,
        roundId: String,
        bundleIndex: Int,
        hotkeySeed: ByteArray,
        proposalId: Int,
        choice: Int,
        numOptions: Int,
        witnessJson: String,
        vanPosition: Int,
        anchorHeight: Int,
        networkId: Int,
        singleShare: Boolean
    ): VoteCommitmentResult =
        io {
            val json = VotingRustBackend.buildVoteCommitmentJson(
                dbHandle, roundId, bundleIndex, hotkeySeed, proposalId, choice, numOptions,
                witnessJson, vanPosition, anchorHeight, networkId, singleShare
            ) ?: error("buildVoteCommitment returned null")

            val obj = org.json.JSONObject(json)
            val encSharesArray = obj.getJSONArray("enc_shares")
            val encSharesJson = encSharesArray.toString()

            VoteCommitmentResult(
                vanNullifier = hexDec(obj.getString("van_nullifier")),
                voteAuthorityNoteNew = hexDec(obj.getString("vote_authority_note_new")),
                voteCommitment = hexDec(obj.getString("vote_commitment")),
                rVpk = hexDec(obj.getString("r_vpk_bytes")),
                alphaV = hexDec(obj.getString("alpha_v")),
                anchorHeight = obj.getInt("anchor_height"),
                encSharesJson = encSharesJson,
                rawBundleJson = json
            )
        }

    // ─── Share payloads ───────────────────────────────────────────────────────

    override suspend fun buildSharePayloadsJson(
        encSharesJson: String,
        commitmentJson: String,
        voteDecision: Int,
        numOptions: Int,
        vcTreePosition: Long,
        singleShareMode: Boolean
    ): String =
        io {
            VotingRustBackend.buildSharePayloadsJson(
                encSharesJson, commitmentJson, voteDecision, numOptions, vcTreePosition, singleShareMode
            ) ?: error("buildSharePayloads returned null")
        }

    // ─── Cast vote ────────────────────────────────────────────────────────────

    override suspend fun signCastVote(
        hotkeySeed: ByteArray,
        networkId: Int,
        roundId: String,
        rVpk: ByteArray,
        vanNullifier: ByteArray,
        vanNew: ByteArray,
        voteCommitment: ByteArray,
        proposalId: Int,
        anchorHeight: Int,
        alphaV: ByteArray
    ): ByteArray =
        io {
            VotingRustBackend.signCastVote(
                hotkeySeed, networkId, roundId, rVpk, vanNullifier, vanNew,
                voteCommitment, proposalId, anchorHeight, alphaV
            ) ?: error("signCastVote returned null")
        }

    // ─── Utilities ────────────────────────────────────────────────────────────

    override suspend fun decomposeWeight(weight: Long): List<Long> =
        io {
            val json = VotingRustBackend.decomposeWeightJson(weight)
            val arr = org.json.JSONArray(json)
            (0 until arr.length()).map { arr.getLong(it) }
        }

    override suspend fun extractOrchardFvkFromUfvk(ufvk: String, networkId: Int): ByteArray =
        io {
            VotingRustBackend.extractOrchardFvkFromUfvk(ufvk, networkId)
                ?: error("extractOrchardFvkFromUfvk returned null")
        }

    // ─── Private helpers ─────────────────────────────────────────────────────

    private fun hexDec(hex: String): ByteArray =
        hex.chunked(2).map { chunk -> chunk.toInt(16).toByte() }.toByteArray()

    private fun parseDelegationSubmission(json: String): DelegationSubmissionResult {
        val obj = org.json.JSONObject(json)
        val govNullifiers = obj.getJSONArray("gov_nullifiers")
            .let { arr -> (0 until arr.length()).map { hexDec(arr.getString(it)) } }
        return DelegationSubmissionResult(
            proof = hexDec(obj.getString("proof")),
            rk = hexDec(obj.getString("rk")),
            spendAuthSig = hexDec(obj.getString("spend_auth_sig")),
            sighash = hexDec(obj.getString("sighash")),
            nfSigned = hexDec(obj.getString("nf_signed")),
            cmxNew = hexDec(obj.getString("cmx_new")),
            govComm = hexDec(obj.getString("gov_comm")),
            govNullifiers = govNullifiers,
            alpha = hexDec(obj.getString("alpha")),
            voteRoundId = obj.getString("vote_round_id")
        )
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }
}
