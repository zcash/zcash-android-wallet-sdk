package cash.z.ecc.android.sdk.type

import cash.z.ecc.android.sdk.ext.ConsensusBranchId

/**
 * Helper class that provides consensus branch information for this SDK and the server to which it
 * is connected and whether they are aligned. Essentially a wrapper for both branch ids with helper
 * functions for communicating detailed error information to the end-user. Used in conjunction with
 * [cash.z.ecc.android.sdk.Synchronizer.validateConsensusBranch].
 */
class ConsensusMatchType(
    val sdkBranch: ConsensusBranchId?,
    val serverBranch: ConsensusBranchId?
) {
    val hasServerBranch = serverBranch != null
    val hasSdkBranch = sdkBranch != null
    val isValid = hasServerBranch && sdkBranch == serverBranch
    val hasBoth = hasServerBranch && hasSdkBranch
    val hasNeither = !hasServerBranch && !hasSdkBranch
    val isServerNewer = hasBoth && serverBranch!!.ordinal > sdkBranch!!.ordinal
    val isSdkNewer = hasBoth && sdkBranch!!.ordinal > serverBranch!!.ordinal

    val errorMessage
        get() =
            when {
                isValid -> {
                    null
                }

                hasNeither -> {
                    "Our branch is unknown and the server branch is unknown. Verify" +
                        " that they are both using the latest consensus branch ID."
                }

                hasServerBranch -> {
                    "The server is on $serverBranch but our branch is unknown." +
                        " Verify that we are fully synced."
                }

                hasSdkBranch -> {
                    "We are on $sdkBranch but the server branch is unknown. Verify" +
                        " the network connection."
                }

                else -> {
                    val newerBranch = if (isServerNewer) serverBranch else sdkBranch
                    val olderBranch = if (isSdkNewer) serverBranch else sdkBranch
                    val newerDevice = if (isServerNewer) "the server has" else "we have"
                    val olderDevice = if (isSdkNewer) "the server has" else "we have"
                    "Incompatible consensus: $newerDevice upgraded to $newerBranch but" +
                        " $olderDevice $olderBranch."
                }
            }
}
