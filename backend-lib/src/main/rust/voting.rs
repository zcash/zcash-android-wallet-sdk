//! JNI bindings for the zcash_voting crate.

use std::ptr;

use anyhow::anyhow;
use jni::{
    JNIEnv,
    objects::{JByteArray, JClass},
    sys::{jbyteArray, jint},
};
use zcash_voting as voting;

use crate::utils::{self, catch_unwind, exception::unwrap_exc_or};

const VOTE_COMMITMENT_BYTES: usize = 32;
const BLIND_BYTES: usize = 32;
const SHARE_NULLIFIER_BYTES: usize = 32;

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
        let share_index =
            u32::try_from(share_index).map_err(|_| anyhow!("shareIndex must be non-negative"))?;
        let vote_commitment =
            java_fixed_bytes::<VOTE_COMMITMENT_BYTES>(env, &vote_commitment, "voteCommitment")?;
        let blind = java_fixed_bytes::<BLIND_BYTES>(env, &blind, "blind")?;

        let nullifier =
            voting::share_tracking::compute_share_nullifier(&vote_commitment, share_index, &blind)
                .map_err(|e| anyhow!("compute_share_nullifier failed: {}", e))?;
        let nullifier_len = nullifier.len();
        let nullifier: [u8; SHARE_NULLIFIER_BYTES] = nullifier.try_into().map_err(|_| {
            anyhow!(
                "shareNullifier must be exactly {} bytes, got {}",
                SHARE_NULLIFIER_BYTES,
                nullifier_len
            )
        })?;

        Ok(utils::rust_bytes_to_java(env, &nullifier)?.into_raw())
    });
    unwrap_exc_or(&mut env, res, ptr::null_mut())
}

fn java_fixed_bytes<const N: usize>(
    env: &JNIEnv<'_>,
    array: &JByteArray<'_>,
    field: &str,
) -> anyhow::Result<[u8; N]> {
    let bytes = utils::java_bytes_to_rust(env, array)?;
    let len = bytes.len();

    bytes
        .try_into()
        .map_err(|_| anyhow!("{field} must be exactly {N} bytes, got {len}"))
}
