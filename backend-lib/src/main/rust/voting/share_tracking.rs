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
        let nullifier = voting::share_tracking::compute_share_nullifier(
            &java_fixed_bytes::<VOTE_COMMITMENT_BYTES>(env, &vote_commitment, "voteCommitment")?,
            jint_to_u32(share_index, "share_index")?,
            &java_fixed_bytes::<BLIND_BYTES>(env, &blind, "blind")?,
        )
        .map_err(|e| anyhow!("compute_share_nullifier: {}", e))?;
        let nullifier = fixed_bytes::<SHARE_NULLIFIER_BYTES>(nullifier, "shareNullifier")?;
        Ok(env.byte_array_from_slice(&nullifier)?.into_raw())
    });
    unwrap_exc_or(&mut env, res, std::ptr::null_mut())
}
