use super::*;

pub(super) const PROTOCOL_FIELD_BYTES: usize = 32;
pub(super) const VOTE_COMMITMENT_BYTES: usize = PROTOCOL_FIELD_BYTES;
pub(super) const BLIND_BYTES: usize = PROTOCOL_FIELD_BYTES;
pub(super) const SHARE_NULLIFIER_BYTES: usize = 32;

pub(super) fn jint_to_u32(value: jint, field: &str) -> anyhow::Result<u32> {
    u32::try_from(value).map_err(|_| anyhow!("{field} must be non-negative, got {value}"))
}

pub(super) fn require_len(bytes: Vec<u8>, field: &str, expected: usize) -> anyhow::Result<Vec<u8>> {
    if bytes.len() == expected {
        Ok(bytes)
    } else {
        Err(anyhow!(
            "{field} must be exactly {expected} bytes, got {}",
            bytes.len()
        ))
    }
}

pub(super) fn java_bytes(
    env: &mut JNIEnv<'_>,
    array: &JByteArray<'_>,
    field: &str,
) -> anyhow::Result<Vec<u8>> {
    env.convert_byte_array(array)
        .map_err(|e| anyhow!("{field}: failed to read byte array: {e}"))
}

pub(super) fn java_bytes_exact(
    env: &mut JNIEnv<'_>,
    array: &JByteArray<'_>,
    field: &str,
    expected: usize,
) -> anyhow::Result<Vec<u8>> {
    require_len(java_bytes(env, array, field)?, field, expected)
}

pub(super) fn java_fixed_bytes<const N: usize>(
    env: &mut JNIEnv<'_>,
    array: &JByteArray<'_>,
    field: &str,
) -> anyhow::Result<[u8; N]> {
    fixed_bytes(java_bytes(env, array, field)?, field)
}

pub(super) fn fixed_bytes<const N: usize>(
    bytes: Vec<u8>,
    field: &str,
) -> anyhow::Result<[u8; N]> {
    let len = bytes.len();

    bytes
        .try_into()
        .map_err(|_| anyhow!("{field} must be exactly {N} bytes, got {len}"))
}
