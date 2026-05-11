package cash.z.ecc.android.sdk.internal.jni

import cash.z.ecc.android.sdk.internal.model.JniAccount

/**
 * The number of bytes in the account UUID parameter. It's used e.g. in [JniAccount.accountUuid], or
 * [JniUnifiedSpendingKey.accountUuid]
 */
const val JNI_ACCOUNT_UUID_BYTES_SIZE = 16

/**
 * The number of bytes in the seed fingerprint parameter. It's used e.g. in [JniAccount.seedFingerprint]
 */
const val JNI_ACCOUNT_SEED_FP_BYTES_SIZE = 32

/**
 * The number of bytes in an HD-derived ZIP 32 key. It's used e.g. in [JniMetadataKey.sk]
 */
const val JNI_METADATA_KEY_SK_SIZE = 32

/**
 * The number of bytes in a chain code. It's used e.g. in [JniMetadataKey.chainCode]
 */
const val JNI_METADATA_KEY_CHAIN_CODE_SIZE = 32

/**
 * The number of bytes in a voting hotkey public key. It's used e.g. in [HotkeyPublicKey.value]
 */
const val JNI_HOTKEY_PUBLIC_KEY_BYTES_SIZE = 32

/**
 * The number of bytes in a protocol field element crossing the voting JNI boundary.
 */
const val JNI_PROTOCOL_FIELD_BYTES_SIZE = 32

/**
 * The number of public input field elements emitted by delegation proof generation.
 */
const val JNI_DELEGATION_PUBLIC_INPUT_COUNT = 14

/**
 * The number of governance nullifiers emitted by delegation proof generation.
 */
const val JNI_GOVERNANCE_NULLIFIER_COUNT = 5

/**
 * The number of bytes in an Orchard spend authorization signature.
 */
const val JNI_SPEND_AUTH_SIG_BYTES_SIZE = 64

/**
 * Voting JNI network id for testnet. Matches [cash.z.ecc.android.sdk.model.ZcashNetwork.ID_TESTNET].
 */
const val JNI_VOTING_NETWORK_ID_TESTNET = 0

/**
 * Voting JNI network id for mainnet. Matches [cash.z.ecc.android.sdk.model.ZcashNetwork.ID_MAINNET].
 */
const val JNI_VOTING_NETWORK_ID_MAINNET = 1
