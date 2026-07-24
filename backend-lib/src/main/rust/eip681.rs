//! EIP-681 transaction request logic.
//!
//! Pure conversions between the wire representations used by the JNI layer and
//! the types of the `eip681` crate. The JNI exports live in
//! [`crate::zcash_jni::eip681`].

/// Parse a nullable decimal chain-ID string into an `Option<u64>`.
pub(crate) fn chain_id_string_to_u64(s: Option<String>) -> anyhow::Result<Option<u64>> {
    match s {
        Some(id) => {
            Ok(Some(id.parse::<u64>().map_err(|e| {
                anyhow::anyhow!("invalid chain ID '{}': {}", id, e)
            })?))
        }
        None => Ok(None),
    }
}
