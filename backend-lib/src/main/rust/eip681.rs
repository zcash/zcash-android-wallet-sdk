//! EIP-681 transaction request logic.
//!
//! Pure conversions between the wire representations used by the JNI layer and
//! the types of the `eip681` crate. The JNI exports live in
//! [`crate::zcash_jni::eip681`].

use eip681::U256;
/// Parse a nullable `0x`-prefixed hex string into an `Option<U256>`.
///
/// Returns an error if the string is present but does not start with `0x` or `0X`.
pub(crate) fn hex_string_to_u256(s: Option<String>) -> anyhow::Result<Option<U256>> {
    match s {
        Some(hex) => {
            let stripped = hex
                .strip_prefix("0x")
                .or_else(|| hex.strip_prefix("0X"))
                .ok_or_else(|| anyhow::anyhow!("hex string '{}' missing 0x prefix", hex))?;
            Ok(Some(U256::from_str_radix(stripped, 16).map_err(|e| {
                anyhow::anyhow!("invalid hex U256 '{}': {}", hex, e)
            })?))
        }
        None => Ok(None),
    }
}

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
