//! Builders for the Java values handed back across the JNI boundary.
//!
//! The inbound direction validates; this direction allocates. Every function
//! here creates a new Java object in the current local reference frame, so the
//! results are only valid until the enclosing native call returns.

use jni::{JNIEnv, objects::JObject};

pub(crate) fn make_jni_bytes<'local>(
    env: &mut JNIEnv<'local>,
    bytes: &[u8],
) -> anyhow::Result<JObject<'local>> {
    Ok(env.byte_array_from_slice(bytes)?.into())
}
