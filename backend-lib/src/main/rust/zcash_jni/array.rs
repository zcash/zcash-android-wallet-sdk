//! Builders for the Java values handed back across the JNI boundary.
//!
//! The inbound direction validates; this direction allocates. Every function
//! here creates a new Java object in the current local reference frame, so the
//! results are only valid until the enclosing native call returns.

use anyhow::anyhow;
use jni::{
    JNIEnv,
    objects::{JObject, JObjectArray},
};

use super::bytes::{require_each_len, require_len};
use crate::utils::rust_vec_to_java;

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

pub(crate) fn make_jni_fixed_byte_array_vec<'local>(
    env: &mut JNIEnv<'local>,
    values: Vec<Vec<u8>>,
    field: &str,
    expected_count: usize,
    expected_size: usize,
) -> anyhow::Result<JObjectArray<'local>> {
    if values.len() != expected_count {
        return Err(anyhow!(
            "{field} must contain {expected_count} entries, got {}",
            values.len()
        ));
    }

    let values = require_each_len(values, field, expected_size)?;

    Ok(rust_vec_to_java(env, values, "[B", |env, bytes| {
        Ok(JObject::from(env.byte_array_from_slice(&bytes)?))
    })?)
}
