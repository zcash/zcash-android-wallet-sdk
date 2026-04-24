package cash.z.ecc.android.sdk.internal.model.voting

import cash.z.ecc.android.sdk.internal.jni.JNI_HOTKEY_PUBLIC_KEY_BYTES_SIZE
import cash.z.ecc.android.sdk.internal.jni.JNI_HOTKEY_SECRET_KEY_BYTES_SIZE

/**
 * FFI-level data models returned by [VotingRustBackend].
 *
 * These mirror the #[repr(C)] structs in voting.rs and the FfiVotingHotkey /
 * FfiRoundState / FfiBundleSetupResult types in the iOS VotingRustBackend.swift.
 *
 * Complex types (GovernancePczt, DelegationProofResult, VoteCommitmentBundle,
 * SharePayload, WitnessData) are serialised as JSON strings by the Rust layer and
 * decoded in the TypesafeVotingBackend wrapper.
 */

/**
 * 32-byte Orchard spending key derived from the hotkey seed.
 * Sensitive: [toString] does NOT log the key bytes.
 */
@ConsistentCopyVisibility
data class HotkeySecretKey internal constructor(val value: ByteArray) {
    init {
        require(value.size == JNI_HOTKEY_SECRET_KEY_BYTES_SIZE) {
            "HotkeySecretKey must be $JNI_HOTKEY_SECRET_KEY_BYTES_SIZE bytes, got ${value.size}"
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HotkeySecretKey) return false
        return value.contentEquals(other.value)
    }

    override fun hashCode(): Int = value.contentHashCode()

    /** Does NOT log the key bytes to prevent accidental secret exposure. */
    override fun toString(): String = "HotkeySecretKey(size=${value.size})"

    companion object {
        fun new(bytes: ByteArray) = HotkeySecretKey(bytes)
    }
}

/**
 * 32-byte Pallas public key for the voting hotkey.
 */
@ConsistentCopyVisibility
data class HotkeyPublicKey internal constructor(val value: ByteArray) {
    init {
        require(value.size == JNI_HOTKEY_PUBLIC_KEY_BYTES_SIZE) {
            "HotkeyPublicKey must be $JNI_HOTKEY_PUBLIC_KEY_BYTES_SIZE bytes, got ${value.size}"
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HotkeyPublicKey) return false
        return value.contentEquals(other.value)
    }

    override fun hashCode(): Int = value.contentHashCode()

    override fun toString(): String = "HotkeyPublicKey(${value.toHexString()})"

    companion object {
        fun new(bytes: ByteArray) = HotkeyPublicKey(bytes)
    }
}

private fun ByteArray.toHexString() = joinToString("") { "%02x".format(it) }

/**
 * A per-round voting hotkey returned by the JNI layer.
 *
 * The primary constructor takes typed [HotkeySecretKey]/[HotkeyPublicKey] values so that
 * the generated [data class] `equals`, `hashCode`, `copy`, and `toString` work correctly.
 * The secondary `internal constructor(ByteArray, ByteArray, String)` matches the JNI
 * signature `([B[BLjava/lang/String;)V` used by voting.rs `make_ffi_voting_hotkey`.
 *
 * [secretKey] 32-byte Orchard spending key — store securely in VotingStorageDataSource.
 * [publicKey] 32-byte public key.
 * [address]   "sv1…" Bech32 address string.
 */
@ConsistentCopyVisibility
data class FfiVotingHotkey internal constructor(
    val secretKey: HotkeySecretKey,
    val publicKey: HotkeyPublicKey,
    val address: String
) {
    /** JNI entry point — voting.rs calls `([B[BLjava/lang/String;)V`. */
    internal constructor(sk: ByteArray, pk: ByteArray, addr: String) :
        this(HotkeySecretKey.new(sk), HotkeyPublicKey.new(pk), addr)
}

/**
 * Result of [VotingRustBackend.setupBundles].
 *
 * [bundleCount]     Number of voting bundles created (one per eligible note group).
 * [eligibleWeight]  Total voting weight in zatoshi across all bundles.
 */
data class FfiBundleSetupResult(
    val bundleCount: Int,
    val eligibleWeight: Long
)

/**
 * Current state of a voting round.
 *
 * [roundId]         Hex-encoded round ID.
 * [phase]           Current protocol phase (0–4, see [FfiRoundPhase]).
 * [snapshotHeight]  Snapshot block height.
 * [hotkeyAddress]   "sv1…" address if hotkey generated, null otherwise.
 * [delegatedWeight] Zatoshi delegated so far, null if not yet delegated.
 * [proofGenerated]  Whether the delegation ZK proof has been generated.
 */
data class FfiRoundState(
    val roundId: String,
    val phase: Int,
    val snapshotHeight: Long,
    val hotkeyAddress: String?,
    val delegatedWeight: Long?,
    val proofGenerated: Boolean
) {
    val roundPhase: FfiRoundPhase get() = FfiRoundPhase.fromInt(phase)
}

/**
 * Round phase values matching RoundPhase enum in zcash_voting/src/types.rs.
 */
enum class FfiRoundPhase(val value: Int) {
    INITIALIZED(0),
    HOTKEY_GENERATED(1),
    DELEGATION_CONSTRUCTED(2),
    DELEGATION_PROVED(3),
    VOTE_READY(4);

    companion object {
        fun fromInt(value: Int) = entries.firstOrNull { it.value == value } ?: INITIALIZED
    }
}
