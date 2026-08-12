//! Length and shape validation for byte payloads crossing the JNI boundary.
//!
//! Java hands every `byte[]` across as a length-prefixed blob with no type
//! information, so the only thing standing between a mis-sized argument and a
//! panic (or a silently wrong proof input) is an explicit check at the boundary.
//! These helpers perform that check and name the offending field in the error.
//!
//! Helpers that close over a domain constant are NOT generic and do not belong
//! here — see `voting::helpers::require_32`, which is `require_len` specialised
//! to a voting protocol constant and stays with the feature.

use anyhow::anyhow;

pub(crate) fn require_len(bytes: Vec<u8>, field: &str, expected: usize) -> anyhow::Result<Vec<u8>> {
    if bytes.len() == expected {
        Ok(bytes)
    } else {
        Err(anyhow!(
            "{field} must be exactly {expected} bytes, got {}",
            bytes.len()
        ))
    }
}
