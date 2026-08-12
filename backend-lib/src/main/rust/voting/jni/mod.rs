//! Marshalling between the voting domain types and their Kotlin `Jni*` models.
//!
//! The counterpart to [`crate::zcash_jni`]: that module holds the conversions
//! any feature needs, this one holds the ones that know what a vote is. The
//! split is by what a helper closes over, not by how generic its body looks —
//! `require_32` and `java_bytes32` read as pure plumbing but are bound to
//! `PROTOCOL_FIELD_BYTES`, so they belong on this side of the line.
//!
//! Files are grouped by the domain object being marshalled rather than by
//! direction, so the decoder and encoder for one Kotlin model sit together and
//! a field added to that model has one place to go.
//!
//! Every encoder here allocates into the current JNI local reference frame, so
//! its result is valid only until the enclosing native call returns.
//!
//! The constants below are the contract with the Kotlin side: a class name or
//! constructor signature that drifts from `JniVotingModels.kt` fails at runtime,
//! on the first call, with a JNI lookup error rather than a compile error. That
//! is why each carries the Kotlin declaration it mirrors, and why several are
//! guarded by `JniVotingModelsTest`.

// Submodules are declared as their first function arrives, so that every commit
// in the migration compiles with no unused-import warnings.

pub(crate) const JNI_ROUND_SUMMARY: &str =
    "cash/z/ecc/android/sdk/internal/model/voting/JniRoundSummary";
pub(crate) const JNI_VOTE_RECORD: &str =
    "cash/z/ecc/android/sdk/internal/model/voting/JniVoteRecord";
pub(crate) const JNI_NOTE_INFO: &str = "cash/z/ecc/android/sdk/internal/model/voting/JniNoteInfo";
pub(crate) const JNI_WITNESS_DATA: &str =
    "cash/z/ecc/android/sdk/internal/model/voting/JniWitnessData";
pub(crate) const JNI_VAN_WITNESS: &str =
    "cash/z/ecc/android/sdk/internal/model/voting/JniVanWitness";
pub(crate) const JNI_WIRE_ENCRYPTED_SHARE: &str =
    "cash/z/ecc/android/sdk/internal/model/voting/JniWireEncryptedShare";
pub(crate) const JNI_VOTE_COMMITMENT_RESULT: &str =
    "cash/z/ecc/android/sdk/internal/model/voting/JniVoteCommitmentResult";
pub(crate) const JNI_SHARE_PAYLOAD: &str =
    "cash/z/ecc/android/sdk/internal/model/voting/JniSharePayload";
pub(crate) const JNI_COMMITMENT_BUNDLE_RECORD: &str =
    "cash/z/ecc/android/sdk/internal/model/voting/JniCommitmentBundleRecord";
pub(crate) const JNI_SHARE_DELEGATION_RECORD: &str =
    "cash/z/ecc/android/sdk/internal/model/voting/JniShareDelegationRecord";
pub(crate) const JNI_VOTING_HOTKEY: &str =
    "cash/z/ecc/android/sdk/internal/model/voting/JniVotingHotkey";
pub(crate) const JNI_BUNDLE_SETUP_RESULT: &str =
    "cash/z/ecc/android/sdk/internal/model/voting/JniBundleSetupResult";
pub(crate) const JNI_GOVERNANCE_PCZT: &str =
    "cash/z/ecc/android/sdk/internal/model/voting/JniGovernancePczt";
pub(crate) const JNI_DELEGATION_PIR_PRECOMPUTE_RESULT: &str =
    "cash/z/ecc/android/sdk/internal/model/voting/JniDelegationPirPrecomputeResult";
pub(crate) const JNI_DELEGATION_PROOF_RESULT: &str =
    "cash/z/ecc/android/sdk/internal/model/voting/JniDelegationProofResult";
pub(crate) const JNI_DELEGATION_SUBMISSION_RESULT: &str =
    "cash/z/ecc/android/sdk/internal/model/voting/JniDelegationSubmissionResult";
pub(crate) const JNI_VOTE_COMMIT_RESULT: &str =
    "cash/z/ecc/android/sdk/internal/model/voting/JniVoteCommitResult";
pub(crate) const JNI_COMMITTED_VOTE_RECORD: &str =
    "cash/z/ecc/android/sdk/internal/model/voting/JniCommittedVoteRecord";
pub(crate) const JNI_DELEGATION_PHASE: &str =
    "cash/z/ecc/android/sdk/internal/model/voting/JniDelegationPhase";

// Must match JniNoteInfo(ByteArray, ByteArray, Long, Long, ByteArray,
// ByteArray, ByteArray, Int, String) in JniVotingModels.kt.
// Guarded by JniVotingModelsTest.
pub(crate) const JNI_NOTE_INFO_CTOR_SIG: &str = "([B[BJJ[B[B[BILjava/lang/String;)V";
// Must match JniWitnessData(ByteArray, Long, ByteArray, Array<ByteArray>)
// in JniVotingModels.kt. Guarded by JniVotingModelsTest.
pub(crate) const JNI_WITNESS_DATA_CTOR_SIG: &str = "([BJ[B[[B)V";
// Must match JniVanWitness(Array<ByteArray>, Long, Long) in
// JniVotingModels.kt. Guarded by JniVotingModelsTest.
pub(crate) const JNI_VAN_WITNESS_CTOR_SIG: &str = "([[BJJ)V";
// Must match JniWireEncryptedShare(ByteArray, ByteArray, Int) in
// JniVotingModels.kt.
pub(crate) const JNI_WIRE_ENCRYPTED_SHARE_CTOR_SIG: &str = "([B[BI)V";
// Must match JniVoteCommitmentResult(ByteArray, ByteArray, ByteArray, Int, Int,
// ByteArray, Array<JniWireEncryptedShare>, Long, String, ByteArray,
// Array<ByteArray>, Array<ByteArray>, ByteArray, ByteArray) in
// JniVotingModels.kt. Guarded by JniVotingModelsTest.
pub(crate) const JNI_VOTE_COMMITMENT_RESULT_CTOR_SIG: &str = "([B[B[BII[B[Lcash/z/ecc/android/sdk/internal/model/voting/JniWireEncryptedShare;JLjava/lang/String;[B[[B[[B[B[B)V";
// Must match JniCommitmentBundleRecord(JniVoteCommitmentResult, Long) in
// JniVotingModels.kt. Guarded by JniVotingModelsTest.
pub(crate) const JNI_COMMITMENT_BUNDLE_RECORD_CTOR_SIG: &str =
    "(Lcash/z/ecc/android/sdk/internal/model/voting/JniVoteCommitmentResult;J)V";
// Must match JniSharePayload(ByteArray, Int, Int, JniWireEncryptedShare,
// Long, Array<JniWireEncryptedShare>, Array<ByteArray>, ByteArray) in
// JniVotingModels.kt. Guarded by JniVotingModelsTest.
pub(crate) const JNI_SHARE_PAYLOAD_CTOR_SIG: &str = "([BIILcash/z/ecc/android/sdk/internal/model/voting/JniWireEncryptedShare;J[Lcash/z/ecc/android/sdk/internal/model/voting/JniWireEncryptedShare;[[B[B)V";
// Must match JniShareDelegationRecord(String, Int, Int, Int, Array<String>,
// ByteArray, Boolean, Long, Long) in JniVotingModels.kt. Guarded by
// JniVotingModelsTest.
pub(crate) const JNI_SHARE_DELEGATION_RECORD_CTOR_SIG: &str =
    "(Ljava/lang/String;III[Ljava/lang/String;[BZJJ)V";
// Must match JniVotingHotkey(ByteArray, ByteArray, String) in JniVotingModels.kt.
pub(crate) const JNI_VOTING_HOTKEY_CTOR_SIG: &str = "([B[BLjava/lang/String;)V";
// Must match JniBundleSetupResult(Int, Long, LongArray) in JniVotingModels.kt.
pub(crate) const JNI_BUNDLE_SETUP_RESULT_CTOR_SIG: &str = "(IJ[J)V";
// Must match JniGovernancePczt(ByteArray, ByteArray, ByteArray, Int) in
// JniVotingModels.kt.
pub(crate) const JNI_GOVERNANCE_PCZT_CTOR_SIG: &str = "([B[B[BI)V";
// Must match JniDelegationPirPrecomputeResult(Long, Long) in JniVotingModels.kt.
pub(crate) const JNI_DELEGATION_PIR_PRECOMPUTE_RESULT_CTOR_SIG: &str = "(JJ)V";
// Must match JniDelegationProofResult(ByteArray, Array<ByteArray>, ByteArray,
// ByteArray, Array<ByteArray>, ByteArray, ByteArray) in JniVotingModels.kt.
pub(crate) const JNI_DELEGATION_PROOF_RESULT_CTOR_SIG: &str = "([B[[B[B[B[[B[B[B)V";
// Must match JniDelegationSubmissionResult(ByteArray, ByteArray, ByteArray,
// ByteArray, ByteArray, ByteArray, ByteArray, ByteArray, Array<ByteArray>,
// String) in JniVotingModels.kt. `sighash` (32 bytes) is the local ZIP-244
// signing digest, still needed for Keystone-signature verification;
// `tx1_effects` (821 bytes, zcash_voting::tx1::TX1_EFFECTS_LEN) is the
// versioned Ironwood effecting data the vote-chain server now requires in
// place of sighash on delegate-vote submission (vote-chain 400:
// "invalid message field: tx1 effects must be 821 bytes, got 0").
pub(crate) const JNI_DELEGATION_SUBMISSION_RESULT_CTOR_SIG: &str =
    "([B[B[B[B[B[B[B[B[[BLjava/lang/String;)V";
// Must match JniVoteCommitResult(Int, Int, Int, String, ByteArray, ByteArray,
// ByteArray, ByteArray, Array<JniWireEncryptedShare>, Long, ByteArray,
// Array<ByteArray>, ByteArray, ByteArray, Array<JniSharePayload>) in
// JniVotingModels.kt. This is the one-shot vote::commit result: the signed
// commitment bundle plus the vote_auth_sig and share_payloads it produces.
pub(crate) const JNI_VOTE_COMMIT_RESULT_CTOR_SIG: &str = "(IIILjava/lang/String;[B[B[B[B[Lcash/z/ecc/android/sdk/internal/model/voting/JniWireEncryptedShare;J[B[[B[B[B[Lcash/z/ecc/android/sdk/internal/model/voting/JniSharePayload;)V";
// Must match JniCommittedVoteRecord(JniVoteCommitResult, Long) in JniVotingModels.kt.
pub(crate) const JNI_COMMITTED_VOTE_RECORD_CTOR_SIG: &str =
    "(Lcash/z/ecc/android/sdk/internal/model/voting/JniVoteCommitResult;J)V";
