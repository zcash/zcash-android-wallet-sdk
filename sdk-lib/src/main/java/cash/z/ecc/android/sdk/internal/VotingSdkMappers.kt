package cash.z.ecc.android.sdk.internal

import cash.z.ecc.android.sdk.internal.model.voting.JniBundleSetupResult
import cash.z.ecc.android.sdk.internal.model.voting.JniDelegationPhase
import cash.z.ecc.android.sdk.internal.model.voting.JniNoteInfo
import cash.z.ecc.android.sdk.internal.model.voting.JniRoundPhase
import cash.z.ecc.android.sdk.internal.model.voting.JniRoundState
import cash.z.ecc.android.sdk.internal.model.voting.JniRoundSummary
import cash.z.ecc.android.sdk.internal.model.voting.JniSharePayload
import cash.z.ecc.android.sdk.internal.model.voting.JniVanWitness
import cash.z.ecc.android.sdk.internal.model.voting.JniVoteCommitResult
import cash.z.ecc.android.sdk.internal.model.voting.JniVoteCommitmentResult
import cash.z.ecc.android.sdk.internal.model.voting.JniVoteRecord
import cash.z.ecc.android.sdk.internal.model.voting.JniVotingHotkey
import cash.z.ecc.android.sdk.internal.model.voting.JniWireEncryptedShare
import cash.z.ecc.android.sdk.internal.model.voting.JniWitnessData
import cash.z.ecc.android.sdk.model.voting.VotingBundleSetupResult
import cash.z.ecc.android.sdk.model.voting.VotingCommitResult
import cash.z.ecc.android.sdk.model.voting.VotingCommitmentResult
import cash.z.ecc.android.sdk.model.voting.VotingEncryptedShare
import cash.z.ecc.android.sdk.model.voting.VotingGovernancePczt
import cash.z.ecc.android.sdk.model.voting.VotingHotkey
import cash.z.ecc.android.sdk.model.voting.VotingNoteInfo
import cash.z.ecc.android.sdk.model.voting.VotingNoteScope
import cash.z.ecc.android.sdk.model.voting.VotingRoundPhase
import cash.z.ecc.android.sdk.model.voting.VotingRoundState
import cash.z.ecc.android.sdk.model.voting.VotingRoundSummary
import cash.z.ecc.android.sdk.model.voting.VotingSharePayload
import cash.z.ecc.android.sdk.model.voting.VotingVanWitness
import cash.z.ecc.android.sdk.model.voting.VotingVoteRecord
import cash.z.ecc.android.sdk.model.voting.VotingWitness

internal fun JniRoundPhase.toPublic(): VotingRoundPhase =
    when (this) {
        JniRoundPhase.INITIALIZED -> VotingRoundPhase.INITIALIZED
        JniRoundPhase.HOTKEY_GENERATED -> VotingRoundPhase.HOTKEY_GENERATED
        JniRoundPhase.DELEGATION_CONSTRUCTED -> VotingRoundPhase.DELEGATION_CONSTRUCTED
        JniRoundPhase.DELEGATION_PROVED -> VotingRoundPhase.DELEGATION_PROVED
        JniRoundPhase.VOTE_READY -> VotingRoundPhase.VOTE_READY
    }

internal fun JniNoteInfo.toPublic(): VotingNoteInfo =
    VotingNoteInfo(
        commitment = commitment,
        nullifier = nullifier,
        value = value,
        position = position,
        diversifier = diversifier,
        rho = rho,
        rseed = rseed,
        scope = if (scope == 0) VotingNoteScope.EXTERNAL else VotingNoteScope.INTERNAL,
        ufvk = ufvk
    )

// NOTE: deviates from the brief, which had no mapper for this direction. `TypesafeVotingBackend`'s
// `getWalletNotes` (pre-existing, not part of this plan) returns the already-JNI-decoupled
// internal `cash.z.ecc.android.sdk.internal.VotingNoteInfo` (see TypesafeVotingBackendImpl's
// `.toVotingNoteInfo()` mapping), not `JniNoteInfo` directly -- so mapping its results to the
// public model needs a mapper from that internal type, fully qualified per this file's existing
// naming-collision convention.
internal fun cash.z.ecc.android.sdk.internal.VotingNoteInfo.toPublic(): VotingNoteInfo =
    VotingNoteInfo(
        commitment = commitment,
        nullifier = nullifier,
        value = value,
        position = position,
        diversifier = diversifier,
        rho = rho,
        rseed = rseed,
        scope =
            if (scope == cash.z.ecc.android.sdk.internal.VotingNoteScope.EXTERNAL) {
                VotingNoteScope.EXTERNAL
            } else {
                VotingNoteScope.INTERNAL
            },
        ufvk = ufvk
    )

internal fun JniWitnessData.toPublic(): VotingWitness =
    VotingWitness(noteCommitment = noteCommitment, position = position, root = root, authPath = authPath)

internal fun VotingWitness.toInternal(): JniWitnessData =
    JniWitnessData(noteCommitment = noteCommitment, position = position, root = root, authPath = authPath)

internal fun JniVanWitness.toPublic(): VotingVanWitness =
    VotingVanWitness(authPath = authPath, position = position, anchorHeight = anchorHeight)

internal fun VotingVanWitness.toInternal(): JniVanWitness =
    JniVanWitness(authPath = authPath, position = position, anchorHeight = anchorHeight)

internal fun JniWireEncryptedShare.toPublic(): VotingEncryptedShare =
    VotingEncryptedShare(c1 = c1, c2 = c2, shareIndex = shareIndex)

internal fun VotingEncryptedShare.toInternal(): JniWireEncryptedShare =
    JniWireEncryptedShare(c1 = c1, c2 = c2, shareIndex = shareIndex)

internal fun JniVoteCommitmentResult.toPublic(): VotingCommitmentResult =
    VotingCommitmentResult(
        vanNullifier = vanNullifier,
        voteAuthorityNoteNew = voteAuthorityNoteNew,
        voteCommitment = voteCommitment,
        proposalId = proposalId,
        bundleIndex = bundleIndex,
        proof = proof,
        encShares = encShares.map { it.toPublic() },
        anchorHeight = anchorHeight,
        voteRoundId = voteRoundId,
        sharesHash = sharesHash,
        shareBlinds = shareBlinds,
        shareComms = shareComms,
        rVpk = rVpk,
        alphaV = alphaV
    )

internal fun VotingCommitmentResult.toInternal(): JniVoteCommitmentResult =
    JniVoteCommitmentResult(
        vanNullifier = vanNullifier,
        voteAuthorityNoteNew = voteAuthorityNoteNew,
        voteCommitment = voteCommitment,
        proposalId = proposalId,
        bundleIndex = bundleIndex,
        proof = proof,
        encShares = encShares.map { it.toInternal() },
        anchorHeight = anchorHeight,
        voteRoundId = voteRoundId,
        sharesHash = sharesHash,
        shareBlinds = shareBlinds,
        shareComms = shareComms,
        rVpk = rVpk,
        alphaV = alphaV
    )

internal fun JniVoteCommitResult.toPublic(): VotingCommitResult =
    VotingCommitResult(
        bundleIndex = bundleIndex,
        proposalId = proposalId,
        choice = choice,
        voteRoundId = voteRoundId,
        vanNullifier = vanNullifier,
        voteAuthorityNoteNew = voteAuthorityNoteNew,
        voteCommitment = voteCommitment,
        proof = proof,
        encShares = encShares.map { it.toPublic() },
        anchorHeight = anchorHeight,
        sharesHash = sharesHash,
        shareComms = shareComms,
        rVpk = rVpk,
        voteAuthSig = voteAuthSig,
        sharePayloads = sharePayloads.map { it.toPublic() }
    )

internal fun JniSharePayload.toPublic(): VotingSharePayload =
    VotingSharePayload(
        sharesHash = sharesHash,
        proposalId = proposalId,
        voteDecision = voteDecision,
        encShare = encShare.toPublic(),
        treePosition = treePosition,
        allEncShares = allEncShares.map { it.toPublic() },
        shareComms = shareComms,
        primaryBlind = primaryBlind
    )

internal fun JniVotingHotkey.toPublic(): VotingHotkey =
    VotingHotkey(storedSecret = storedSecret, rawAddress = rawAddress, address = address)

internal fun JniBundleSetupResult.toPublic(): VotingBundleSetupResult =
    VotingBundleSetupResult(bundleCount = bundleCount, eligibleWeight = eligibleWeight, bundleWeights = bundleWeights)

// NOTE: deviates from the brief, which mapped from `JniGovernancePczt`. `TypesafeVotingDb`'s
// `buildGovernancePczt`/`buildGovernancePcztFromSeed` (pre-existing, not part of this plan)
// actually return the already-JNI-decoupled internal `GovernancePcztResult` (see
// TypesafeVotingBackend.kt), not `JniGovernancePczt` directly -- the two are field-for-field
// identical (pcztBytes/rk/sighash/actionIndex), so this maps from the type that's actually on
// the wire here.
internal fun GovernancePcztResult.toPublic(): VotingGovernancePczt =
    VotingGovernancePczt(pcztBytes = pcztBytes, rk = rk, sighash = sighash, actionIndex = actionIndex)

internal fun JniRoundState.toPublic(): VotingRoundState =
    VotingRoundState(
        roundId = roundId,
        phase = roundPhase.toPublic(),
        snapshotHeight = snapshotHeight,
        hotkeyAddress = hotkeyAddress,
        delegatedWeight = delegatedWeight,
        proofGenerated = proofGenerated
    )

internal fun JniRoundSummary.toPublic(): VotingRoundSummary =
    VotingRoundSummary(roundId = roundId, phase = roundPhase.toPublic(), snapshotHeight = snapshotHeight, createdAt = createdAt)

internal fun JniVoteRecord.toPublic(): cash.z.ecc.android.sdk.model.voting.VotingVoteRecord =
    cash.z.ecc.android.sdk.model.voting.VotingVoteRecord(
        proposalId = proposalId,
        bundleIndex = bundleIndex,
        choice = choice,
        submitted = submitted
    )

internal fun JniDelegationPhase.toPublic(): cash.z.ecc.android.sdk.model.voting.VotingDelegationPhase =
    cash.z.ecc.android.sdk.model.voting.VotingDelegationPhase(bundleIndex = bundleIndex, phase = phase)

internal fun VotingNoteInfo.toInternal(): cash.z.ecc.android.sdk.internal.VotingNoteInfo =
    cash.z.ecc.android.sdk.internal.VotingNoteInfo(
        commitment = commitment,
        nullifier = nullifier,
        value = value,
        position = position,
        diversifier = diversifier,
        rho = rho,
        rseed = rseed,
        scope =
            if (scope == VotingNoteScope.EXTERNAL) {
                cash.z.ecc.android.sdk.internal.VotingNoteScope.EXTERNAL
            } else {
                cash.z.ecc.android.sdk.internal.VotingNoteScope.INTERNAL
            },
        ufvk = ufvk
    )

internal fun cash.z.ecc.android.sdk.internal.DelegationPirPrecomputeResult.toPublic():
    cash.z.ecc.android.sdk.model.voting.VotingDelegationPirPrecomputeResult =
    cash.z.ecc.android.sdk.model.voting.VotingDelegationPirPrecomputeResult(
        cachedCount = cachedCount,
        fetchedCount = fetchedCount
    )

internal fun cash.z.ecc.android.sdk.internal.DelegationProofResult.toPublic():
    cash.z.ecc.android.sdk.model.voting.VotingDelegationProofResult =
    cash.z.ecc.android.sdk.model.voting.VotingDelegationProofResult(
        proof = proof,
        publicInputs = publicInputs,
        nfSigned = nfSigned,
        cmxNew = cmxNew,
        govNullifiers = govNullifiers,
        vanComm = vanComm,
        rk = rk
    )

internal fun cash.z.ecc.android.sdk.internal.DelegationSubmissionResult.toPublic():
    cash.z.ecc.android.sdk.model.voting.VotingDelegationSubmissionResult =
    cash.z.ecc.android.sdk.model.voting.VotingDelegationSubmissionResult(
        proof = proof,
        rk = rk,
        spendAuthSig = spendAuthSig,
        sighash = sighash,
        tx1Effects = tx1Effects,
        nfSigned = nfSigned,
        cmxNew = cmxNew,
        govComm = govComm,
        govNullifiers = govNullifiers,
        voteRoundId = voteRoundId
    )

internal fun cash.z.ecc.android.sdk.internal.VotingTxHashLookup.toPublic():
    cash.z.ecc.android.sdk.model.voting.VotingTxHashLookup =
    when (this) {
        is cash.z.ecc.android.sdk.internal.VotingTxHashLookup.Missing ->
            cash.z.ecc.android.sdk.model.voting.VotingTxHashLookup.Missing
        is cash.z.ecc.android.sdk.internal.VotingTxHashLookup.Found ->
            cash.z.ecc.android.sdk.model.voting.VotingTxHashLookup.Found(txHash)
    }

internal fun cash.z.ecc.android.sdk.internal.CommitmentBundleRecord.toPublic():
    cash.z.ecc.android.sdk.model.voting.VotingCommitmentBundleRecord =
    cash.z.ecc.android.sdk.model.voting.VotingCommitmentBundleRecord(
        commitment = commitment.toPublic(),
        vcTreePosition = vcTreePosition
    )

internal fun cash.z.ecc.android.sdk.internal.CommittedVoteRecord.toPublic():
    cash.z.ecc.android.sdk.model.voting.VotingCommittedVoteRecord =
    cash.z.ecc.android.sdk.model.voting.VotingCommittedVoteRecord(
        commit = commit.toPublic(),
        vcTreePosition = vcTreePosition
    )

internal fun cash.z.ecc.android.sdk.internal.ShareDelegationRecord.toPublic():
    cash.z.ecc.android.sdk.model.voting.VotingShareDelegationRecord =
    cash.z.ecc.android.sdk.model.voting.VotingShareDelegationRecord(
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
