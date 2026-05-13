package cash.z.ecc.android.sdk.internal

import cash.z.ecc.android.sdk.internal.model.voting.JniNoteInfo

internal fun JniNoteInfo.toVotingNoteInfo() =
    VotingNoteInfo(
        commitment = commitment,
        nullifier = nullifier,
        value = value,
        position = position,
        diversifier = diversifier,
        rho = rho,
        rseed = rseed,
        scope = VotingNoteScope.fromJniValue(scope),
        ufvk = ufvk
    )

internal fun VotingNoteInfo.toJniNoteInfo() =
    JniNoteInfo(
        commitment = commitment,
        nullifier = nullifier,
        value = value,
        position = position,
        diversifier = diversifier,
        rho = rho,
        rseed = rseed,
        scope = scope.jniValue,
        ufvk = ufvk
    )

internal fun List<VotingNoteInfo>.toJniNoteInfos() = map { it.toJniNoteInfo() }
