use super::helpers::*;
use super::*;

/// Compute the share reveal nullifier from client-known inputs.
///
/// Returns the 32-byte nullifier, or throws a RuntimeException and returns null
/// on malformed inputs.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_computeShareNullifierNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    vote_commitment: JByteArray<'local>,
    share_index: jint,
    blind: JByteArray<'local>,
) -> jbyteArray {
    let res = catch_unwind(&mut env, |env| {
        let nullifier = voting::share::compute_nullifier(
            &java_fixed_bytes::<VOTE_COMMITMENT_BYTES>(env, &vote_commitment, "voteCommitment")?,
            jint_to_u32(share_index, "share_index")?,
            &java_fixed_bytes::<BLIND_BYTES>(env, &blind, "blind")?,
        )
        .map_err(|e| anyhow!("compute_share_nullifier: {}", e))?;
        Ok(env.byte_array_from_slice(&nullifier)?.into_raw())
    });
    unwrap_exc_or(&mut env, res, std::ptr::null_mut())
}

/// Computes when a delegated helper share should submit, honoring the
/// last-moment ceremony window.
///
/// Returns unix seconds; `0` means "submit immediately". `entropy` must be at
/// least 8 bytes of caller-supplied randomness. Throws a RuntimeException on
/// malformed inputs.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_scheduledShareSubmitAtNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    now_seconds: jlong,
    ceremony_start_seconds: jlong,
    vote_end_time_seconds: jlong,
    single_share: jboolean,
    entropy: JByteArray<'local>,
) -> jlong {
    let res = catch_unwind(&mut env, |env| {
        let now_seconds = jlong_to_u64(now_seconds, "now_seconds")?;
        let ceremony_start_seconds =
            jlong_to_u64(ceremony_start_seconds, "ceremony_start_seconds")?;
        let vote_end_time_seconds = jlong_to_u64(vote_end_time_seconds, "vote_end_time_seconds")?;
        let entropy = require_min_len(java_bytes(env, &entropy, "entropy")?, "entropy", 8)?;

        let last_moment_buffer_seconds = voting::share::policy::last_moment_buffer_seconds(
            ceremony_start_seconds,
            vote_end_time_seconds,
        );
        let submit_at = voting::share::policy::scheduled_share_submit_at_from_entropy(
            now_seconds,
            vote_end_time_seconds,
            last_moment_buffer_seconds,
            single_share == JNI_TRUE,
            &entropy,
        )
        .map_err(|e| anyhow!("scheduled_share_submit_at_from_entropy: {}", e))?;

        u64_to_jlong(submit_at, "submit_at")
    });
    unwrap_exc_or(&mut env, res, -1)
}
