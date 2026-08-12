//! Builders for the Java values handed back across the JNI boundary.
//!
//! The inbound direction validates; this direction allocates. Every function
//! here creates a new Java object in the current local reference frame, so the
//! results are only valid until the enclosing native call returns.

use jni::{JNIEnv, objects::JObject};

use super::bytes::require_len;

pub(crate) fn make_jni_bytes<'local>(
    env: &mut JNIEnv<'local>,
    bytes: &[u8],
) -> anyhow::Result<JObject<'local>> {
    Ok(env.byte_array_from_slice(bytes)?.into())
}

pub(crate) fn make_jni_fixed_bytes<'local>(
    env: &mut JNIEnv<'local>,
    bytes: Vec<u8>,
    field: &str,
    expected: usize,
) -> anyhow::Result<JObject<'local>> {
    make_jni_bytes(env, &require_len(bytes, field, expected)?)
}
