//! Readers for fields of a Java object crossing the JNI boundary.
//!
//! Each of these pairs a JNI field lookup with its type signature string
//! (`"[B"`, `"Ljava/lang/String;"`, `"Ljava/util/List;"`). Those signatures are
//! the part with no compile-time checking on either side of the boundary: a
//! renamed or retyped Kotlin field fails here at runtime, so the field name is
//! carried into the error rather than surfacing as a bare JNI panic.

use jni::{
    JNIEnv,
    objects::{JByteArray, JObject},
};

use super::bytes::java_bytes;

pub(crate) fn java_byte_array_field(
    env: &mut JNIEnv<'_>,
    obj: &JObject<'_>,
    name: &str,
) -> anyhow::Result<Vec<u8>> {
    let field = JByteArray::from(env.get_field(obj, name, "[B")?.l()?);
    java_bytes(env, &field, name)
}
