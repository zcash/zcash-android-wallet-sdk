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
use jni::{JNIEnv, objects::JByteArray};
use secrecy::SecretVec;

pub(crate) fn java_secret_bytes_at_least(
    env: &mut JNIEnv<'_>,
    array: &JByteArray<'_>,
    field: &str,
    minimum: usize,
) -> anyhow::Result<SecretVec<u8>> {
    require_min_len(java_bytes(env, array, field)?, field, minimum).map(SecretVec::new)
}

/// Like [`java_secret_bytes_at_least`], but for call sites where the caller-supplied bytes
/// feed the exact same ZIP-32 `UnifiedSpendingKey::from_seed` derivation
/// `VotingHotkey::from_stored_secret` uses internally, so a length that from_stored_secret
/// would reject should be rejected here too rather than accepted and only failing later.
pub(crate) fn java_secret_bytes_exact(
    env: &mut JNIEnv<'_>,
    array: &JByteArray<'_>,
    field: &str,
    expected: usize,
) -> anyhow::Result<SecretVec<u8>> {
    require_len(java_bytes(env, array, field)?, field, expected).map(SecretVec::new)
}

pub(crate) fn java_bytes(
    env: &mut JNIEnv<'_>,
    array: &JByteArray<'_>,
    field: &str,
) -> anyhow::Result<Vec<u8>> {
    env.convert_byte_array(array)
        .map_err(|e| anyhow!("{field}: failed to read byte array: {e}"))
}

pub(crate) fn java_bytes_exact(
    env: &mut JNIEnv<'_>,
    array: &JByteArray<'_>,
    field: &str,
    expected: usize,
) -> anyhow::Result<Vec<u8>> {
    require_len(java_bytes(env, array, field)?, field, expected)
}

pub(crate) fn java_fixed_bytes<const N: usize>(
    env: &mut JNIEnv<'_>,
    array: &JByteArray<'_>,
    field: &str,
) -> anyhow::Result<[u8; N]> {
    fixed_bytes(java_bytes(env, array, field)?, field)
}

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

pub(crate) fn require_each_len(
    values: Vec<Vec<u8>>,
    field: &str,
    expected: usize,
) -> anyhow::Result<Vec<Vec<u8>>> {
    values
        .into_iter()
        .enumerate()
        .map(|(index, value)| require_len(value, &format!("{field}[{index}]"), expected))
        .collect()
}

pub(crate) fn require_count<T>(
    values: Vec<T>,
    field: &str,
    expected: usize,
) -> anyhow::Result<Vec<T>> {
    if values.len() == expected {
        Ok(values)
    } else {
        Err(anyhow!(
            "{field} must contain {expected} entries, got {}",
            values.len()
        ))
    }
}

pub(crate) fn require_min_len(
    bytes: Vec<u8>,
    field: &str,
    minimum: usize,
) -> anyhow::Result<Vec<u8>> {
    if bytes.len() >= minimum {
        Ok(bytes)
    } else {
        Err(anyhow!(
            "{field} must be at least {minimum} bytes, got {}",
            bytes.len()
        ))
    }
}

pub(crate) fn fixed_bytes<const N: usize>(bytes: Vec<u8>, field: &str) -> anyhow::Result<[u8; N]> {
    let len = bytes.len();

    bytes
        .try_into()
        .map_err(|_| anyhow!("{field} must be exactly {N} bytes, got {len}"))
}

#[cfg(test)]
mod tests {
    use super::*;

    // The JNIEnv-taking helpers in this module (java_bytes and its
    // compositions) need a live JVM and are covered by the instrumentation
    // suite. What is testable here is the validation layer they delegate to,
    // which is where the off-by-one lives.

    // ---- require_len -------------------------------------------------------

    #[test]
    fn require_len_accepts_exact_length() {
        assert_eq!(require_len(vec![1, 2, 3], "f", 3).unwrap(), vec![1, 2, 3]);
    }

    #[test]
    fn require_len_accepts_empty_when_zero_expected() {
        assert_eq!(require_len(vec![], "f", 0).unwrap(), Vec::<u8>::new());
    }

    #[test]
    fn require_len_rejects_off_by_one_both_directions() {
        assert!(require_len(vec![1, 2], "f", 3).is_err());
        assert!(require_len(vec![1, 2, 3, 4], "f", 3).is_err());
    }

    #[test]
    fn require_len_rejects_empty_when_bytes_expected() {
        assert!(require_len(vec![], "f", 32).is_err());
    }

    // ---- require_min_len ---------------------------------------------------

    #[test]
    fn require_min_len_boundary_is_inclusive() {
        // Length exactly at the minimum must pass: this is the one that a
        // `>` instead of `>=` would silently break.
        assert_eq!(require_min_len(vec![1, 2], "f", 2).unwrap(), vec![1, 2]);
        assert!(require_min_len(vec![1], "f", 2).is_err());
    }

    #[test]
    fn require_min_len_accepts_longer_and_empty_at_zero() {
        assert_eq!(
            require_min_len(vec![1, 2, 3], "f", 2).unwrap(),
            vec![1, 2, 3]
        );
        assert_eq!(require_min_len(vec![], "f", 0).unwrap(), Vec::<u8>::new());
    }

    // ---- require_count -----------------------------------------------------

    #[test]
    fn require_count_accepts_exact_and_empty() {
        assert_eq!(require_count(vec![1, 2], "f", 2).unwrap(), vec![1, 2]);
        assert_eq!(
            require_count(Vec::<u8>::new(), "f", 0).unwrap(),
            Vec::<u8>::new()
        );
    }

    #[test]
    fn require_count_rejects_off_by_one_both_directions() {
        assert!(require_count(vec![1], "f", 2).is_err());
        assert!(require_count(vec![1, 2, 3], "f", 2).is_err());
    }

    // ---- require_each_len --------------------------------------------------

    #[test]
    fn require_each_len_accepts_empty_vacuously() {
        assert_eq!(
            require_each_len(vec![], "f", 32).unwrap(),
            Vec::<Vec<u8>>::new()
        );
    }

    #[test]
    fn require_each_len_accepts_all_matching() {
        let values = vec![vec![1, 2], vec![3, 4]];
        assert_eq!(require_each_len(values.clone(), "f", 2).unwrap(), values);
    }

    #[test]
    fn require_each_len_error_names_the_offending_index() {
        // The "{field}[{index}]" format string exists precisely so a bad entry
        // in a list of shares can be located; assert it actually does that,
        // and that it reports the failing index rather than the first one.
        let values = vec![vec![1, 2], vec![3, 4], vec![5]];
        let err = require_each_len(values, "shares", 2)
            .unwrap_err()
            .to_string();
        assert!(err.contains("shares[2]"), "unexpected message: {err}");
    }

    // ---- fixed_bytes -------------------------------------------------------

    #[test]
    fn fixed_bytes_accepts_exact_width() {
        let out: [u8; 3] = fixed_bytes(vec![1, 2, 3], "f").unwrap();
        assert_eq!(out, [1, 2, 3]);
    }

    #[test]
    fn fixed_bytes_accepts_zero_width_from_empty() {
        let out: [u8; 0] = fixed_bytes(vec![], "f").unwrap();
        assert_eq!(out, [0u8; 0]);
    }

    #[test]
    fn fixed_bytes_rejects_off_by_one_both_directions() {
        assert!(fixed_bytes::<3>(vec![1, 2], "f").is_err());
        assert!(fixed_bytes::<3>(vec![1, 2, 3, 4], "f").is_err());
    }

    #[test]
    fn fixed_bytes_error_reports_actual_length() {
        let err = fixed_bytes::<32>(vec![1, 2], "seed")
            .unwrap_err()
            .to_string();
        assert!(err.contains("seed"), "unexpected message: {err}");
        assert!(err.contains("32"), "unexpected message: {err}");
        assert!(err.contains('2'), "unexpected message: {err}");
    }

    // ---- field naming ------------------------------------------------------

    #[test]
    fn errors_name_the_field() {
        let cases = [
            require_len(vec![1], "alpha", 2).unwrap_err(),
            require_min_len(vec![1], "alpha", 2).unwrap_err(),
            require_count(vec![1], "alpha", 2).unwrap_err(),
            fixed_bytes::<2>(vec![1], "alpha").unwrap_err(),
        ];
        for err in cases {
            assert!(
                err.to_string().contains("alpha"),
                "error did not name the field: {err}"
            );
        }
    }
}
