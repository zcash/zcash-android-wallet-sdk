package cash.z.ecc.android.sdk.internal

import cash.z.ecc.android.sdk.internal.model.voting.JniBundleSetupResult
import cash.z.ecc.android.sdk.internal.model.voting.JniRoundPhase
import cash.z.ecc.android.sdk.internal.model.voting.JniRoundState
import cash.z.ecc.android.sdk.internal.model.voting.JniRoundSummary
import cash.z.ecc.android.sdk.internal.model.voting.JniVotingHotkey
import cash.z.ecc.android.sdk.model.voting.VotingBundleSetupResult
import cash.z.ecc.android.sdk.model.voting.VotingGovernancePczt
import cash.z.ecc.android.sdk.model.voting.VotingHotkey
import cash.z.ecc.android.sdk.model.voting.VotingRoundPhase
import cash.z.ecc.android.sdk.model.voting.VotingRoundState
import cash.z.ecc.android.sdk.model.voting.VotingRoundSummary

// Round/session mappers split out of VotingSdkMappers.kt (now VotingSdkNoteMappers.kt,
// VotingSdkVoteMappers.kt, VotingSdkDelegationMappers.kt, and this file) to keep each file under
// detekt's TooManyFunctions threshold -- this is a straight file split, no behavior changes.

internal fun JniRoundPhase.toPublic(): VotingRoundPhase =
    when (this) {
        JniRoundPhase.INITIALIZED -> VotingRoundPhase.INITIALIZED
        JniRoundPhase.HOTKEY_GENERATED -> VotingRoundPhase.HOTKEY_GENERATED
        JniRoundPhase.DELEGATION_CONSTRUCTED -> VotingRoundPhase.DELEGATION_CONSTRUCTED
        JniRoundPhase.DELEGATION_PROVED -> VotingRoundPhase.DELEGATION_PROVED
        JniRoundPhase.VOTE_READY -> VotingRoundPhase.VOTE_READY
    }

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
    VotingRoundSummary(
        roundId = roundId,
        phase = roundPhase.toPublic(),
        snapshotHeight = snapshotHeight,
        createdAt = createdAt
    )
