package cash.z.ecc.android.sdk.internal

import cash.z.ecc.android.sdk.internal.model.voting.JniDelegationPhase
import cash.z.ecc.android.sdk.model.voting.VotingCommitmentBundleRecord
import cash.z.ecc.android.sdk.model.voting.VotingCommittedVoteRecord
import cash.z.ecc.android.sdk.model.voting.VotingDelegationPhase
import cash.z.ecc.android.sdk.model.voting.VotingDelegationPirPrecomputeResult
import cash.z.ecc.android.sdk.model.voting.VotingDelegationProofResult
import cash.z.ecc.android.sdk.model.voting.VotingDelegationSubmissionResult
import cash.z.ecc.android.sdk.model.voting.VotingShareDelegationRecord
import cash.z.ecc.android.sdk.model.voting.VotingTxHashLookup as PublicVotingTxHashLookup

// Delegation/recovery mappers split out of VotingSdkMappers.kt (now VotingSdkNoteMappers.kt,
// VotingSdkRoundMappers.kt, VotingSdkVoteMappers.kt, and this file) to keep each file under
// detekt's TooManyFunctions threshold -- this is a straight file split, no behavior changes.
//
// Unlike the original single-file version, these mappers use plain imports (with an `as`-alias
// for the one genuine name collision, `VotingTxHashLookup`) instead of inline fully-qualified
// names -- the internal `cash.z.ecc.android.sdk.internal.*` receiver types here are same-package
// and never needed qualifying, and only `VotingTxHashLookup` collides with its public-model
// counterpart of the same simple name.

internal fun JniDelegationPhase.toPublic(): VotingDelegationPhase =
    VotingDelegationPhase(bundleIndex = bundleIndex, phase = phase)

internal fun DelegationPirPrecomputeResult.toPublic(): VotingDelegationPirPrecomputeResult =
    VotingDelegationPirPrecomputeResult(
        cachedCount = cachedCount,
        fetchedCount = fetchedCount
    )

internal fun DelegationProofResult.toPublic(): VotingDelegationProofResult =
    VotingDelegationProofResult(
        proof = proof,
        publicInputs = publicInputs,
        nfSigned = nfSigned,
        cmxNew = cmxNew,
        govNullifiers = govNullifiers,
        vanComm = vanComm,
        rk = rk
    )

internal fun DelegationSubmissionResult.toPublic(): VotingDelegationSubmissionResult =
    VotingDelegationSubmissionResult(
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

internal fun VotingTxHashLookup.toPublic(): PublicVotingTxHashLookup =
    when (this) {
        is VotingTxHashLookup.Missing -> PublicVotingTxHashLookup.Missing
        is VotingTxHashLookup.Found -> PublicVotingTxHashLookup.Found(txHash)
    }

internal fun CommitmentBundleRecord.toPublic(): VotingCommitmentBundleRecord =
    VotingCommitmentBundleRecord(
        commitment = commitment.toPublic(),
        vcTreePosition = vcTreePosition
    )

internal fun CommittedVoteRecord.toPublic(): VotingCommittedVoteRecord =
    VotingCommittedVoteRecord(
        commit = commit.toPublic(),
        vcTreePosition = vcTreePosition
    )

internal fun ShareDelegationRecord.toPublic(): VotingShareDelegationRecord =
    VotingShareDelegationRecord(
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
