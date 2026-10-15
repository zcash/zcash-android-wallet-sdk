package cash.z.ecc.android.sdk.internal

import cash.z.ecc.android.sdk.internal.model.voting.JniNoteInfo
import cash.z.ecc.android.sdk.internal.model.voting.JniVanWitness
import cash.z.ecc.android.sdk.internal.model.voting.JniWireEncryptedShare
import cash.z.ecc.android.sdk.internal.model.voting.JniWitnessData
import cash.z.ecc.android.sdk.model.voting.VotingEncryptedShare
import cash.z.ecc.android.sdk.model.voting.VotingNoteInfo
import cash.z.ecc.android.sdk.model.voting.VotingNoteScope
import cash.z.ecc.android.sdk.model.voting.VotingVanWitness
import cash.z.ecc.android.sdk.model.voting.VotingWitness

// Note/witness/share mappers split out of VotingSdkMappers.kt (now VotingSdkRoundMappers.kt,
// VotingSdkVoteMappers.kt, VotingSdkDelegationMappers.kt, and this file) to keep each file under
// detekt's TooManyFunctions threshold -- this is a straight file split, no behavior changes.

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
