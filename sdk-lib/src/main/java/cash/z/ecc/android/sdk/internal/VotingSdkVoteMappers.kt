package cash.z.ecc.android.sdk.internal

import cash.z.ecc.android.sdk.internal.model.voting.JniSharePayload
import cash.z.ecc.android.sdk.internal.model.voting.JniVoteCommitResult
import cash.z.ecc.android.sdk.internal.model.voting.JniVoteCommitmentResult
import cash.z.ecc.android.sdk.internal.model.voting.JniVoteRecord
import cash.z.ecc.android.sdk.model.voting.VotingCommitResult
import cash.z.ecc.android.sdk.model.voting.VotingCommitmentResult
import cash.z.ecc.android.sdk.model.voting.VotingSharePayload
import cash.z.ecc.android.sdk.model.voting.VotingVoteRecord

// Vote/commitment mappers split out of VotingSdkMappers.kt (now VotingSdkNoteMappers.kt,
// VotingSdkRoundMappers.kt, VotingSdkDelegationMappers.kt, and this file) to keep each file under
// detekt's TooManyFunctions threshold -- this is a straight file split, no behavior changes.

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

internal fun JniVoteRecord.toPublic(): VotingVoteRecord =
    VotingVoteRecord(
        proposalId = proposalId,
        bundleIndex = bundleIndex,
        choice = choice,
        submitted = submitted
    )
