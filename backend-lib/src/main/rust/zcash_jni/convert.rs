//! Width-checked conversions between Java primitives and Rust integer types.
//!
//! Every function here preserves the value exactly: it either produces the same
//! number in the target type, or an error naming the offending field. See the
//! module doc on [`super`] for why this is not interchangeable with an `as`
//! cast.

use anyhow::anyhow;
use jni::sys::{jint, jlong};
use zcash_protocol::consensus::Network;

/// The network identifiers Kotlin passes across the boundary. These are the JNI
/// wire encoding, shared by every feature, not a per-feature value: `lib.rs`
/// independently hardcoded the same 0 and 1 before this module existed.
pub(crate) const NETWORK_ID_TESTNET: jint = 0;
pub(crate) const NETWORK_ID_MAINNET: jint = 1;

pub(crate) fn jint_to_u32(value: jint, field: &str) -> anyhow::Result<u32> {
    u32::try_from(value).map_err(|_| anyhow!("{field} must be non-negative, got {value}"))
}

pub(crate) fn jlong_to_u64(value: jlong, field: &str) -> anyhow::Result<u64> {
    u64::try_from(value).map_err(|_| anyhow!("{field} must be non-negative, got {value}"))
}

pub(crate) fn jlong_to_u32(value: jlong, field: &str) -> anyhow::Result<u32> {
    u32::try_from(value).map_err(|_| anyhow!("{field} must be in range 0..=u32::MAX, got {value}"))
}

pub(crate) fn jint_to_usize(value: jint, field: &str) -> anyhow::Result<usize> {
    usize::try_from(value).map_err(|_| anyhow!("{field} must be non-negative, got {value}"))
}

pub(crate) fn u32_to_jint(value: u32, field: &str) -> anyhow::Result<jint> {
    jint::try_from(value).map_err(|_| anyhow!("{field} exceeds signed Int range: {value}"))
}

pub(crate) fn usize_to_jint(value: usize, field: &str) -> anyhow::Result<jint> {
    jint::try_from(value).map_err(|_| anyhow!("{field} exceeds signed Int range: {value}"))
}

pub(crate) fn u64_to_jlong(value: u64, field: &str) -> anyhow::Result<jlong> {
    jlong::try_from(value).map_err(|_| anyhow!("{field} exceeds signed Long range: {value}"))
}

pub(crate) fn network_from_id(id: jint) -> anyhow::Result<Network> {
    match id {
        NETWORK_ID_TESTNET => Ok(Network::TestNetwork),
        NETWORK_ID_MAINNET => Ok(Network::MainNetwork),
        _ => Err(anyhow!("invalid network_id {}", id)),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Values spanning the accepted range of a `jint`, including every power-of-two
    /// boundary a width bug is likely to sit on. Used by the round-trip and
    /// agreement properties below, which must hold at every one of them.
    fn representative_jints() -> Vec<jint> {
        let mut values = vec![
            0,
            1,
            2,
            127,
            128,
            255,
            256,
            32767,
            32768,
            65535,
            65536,
            jint::MAX - 1,
            jint::MAX,
        ];
        // A coarse sweep as well, so the property is not only checked at values
        // chosen because they looked interesting.
        let mut v: jint = 1;
        while v < jint::MAX / 2 {
            values.push(v);
            v = v.saturating_mul(3).saturating_add(1);
        }
        values
    }

    // ---- jint_to_u32 -------------------------------------------------------

    #[test]
    fn jint_to_u32_accepts_zero_and_max() {
        assert_eq!(jint_to_u32(0, "f").unwrap(), 0);
        assert_eq!(jint_to_u32(1, "f").unwrap(), 1);
        assert_eq!(jint_to_u32(jint::MAX, "f").unwrap(), jint::MAX as u32);
    }

    #[test]
    fn jint_to_u32_rejects_negative() {
        assert!(jint_to_u32(-1, "f").is_err());
        assert!(jint_to_u32(jint::MIN, "f").is_err());
    }

    // ---- jint_to_usize -----------------------------------------------------

    #[test]
    fn jint_to_usize_accepts_zero_and_max() {
        assert_eq!(jint_to_usize(0, "f").unwrap(), 0);
        assert_eq!(jint_to_usize(jint::MAX, "f").unwrap(), jint::MAX as usize);
    }

    #[test]
    fn jint_to_usize_rejects_negative() {
        assert!(jint_to_usize(-1, "f").is_err());
        assert!(jint_to_usize(jint::MIN, "f").is_err());
    }

    // ---- jlong_to_u64 ------------------------------------------------------

    #[test]
    fn jlong_to_u64_accepts_zero_and_max() {
        assert_eq!(jlong_to_u64(0, "f").unwrap(), 0);
        assert_eq!(jlong_to_u64(jlong::MAX, "f").unwrap(), jlong::MAX as u64);
    }

    #[test]
    fn jlong_to_u64_rejects_negative() {
        assert!(jlong_to_u64(-1, "f").is_err());
        assert!(jlong_to_u64(jlong::MIN, "f").is_err());
    }

    // ---- jlong_to_u32 ------------------------------------------------------
    //
    // The only two-sided bound in this module: a jlong can overflow u32 from
    // above as well as fall below zero. Its error message says `0..=u32::MAX`
    // where the others say "non-negative", so both ends are checked here.

    #[test]
    fn jlong_to_u32_accepts_zero_and_u32_max() {
        assert_eq!(jlong_to_u32(0, "f").unwrap(), 0);
        assert_eq!(jlong_to_u32(u32::MAX as jlong, "f").unwrap(), u32::MAX);
    }

    #[test]
    fn jlong_to_u32_rejects_both_ends() {
        assert!(jlong_to_u32(-1, "f").is_err());
        assert!(jlong_to_u32(jlong::MIN, "f").is_err());
        assert!(jlong_to_u32(u32::MAX as jlong + 1, "f").is_err());
        assert!(jlong_to_u32(jlong::MAX, "f").is_err());
    }

    // ---- u32_to_jint -------------------------------------------------------

    #[test]
    fn u32_to_jint_accepts_up_to_i32_max() {
        assert_eq!(u32_to_jint(0, "f").unwrap(), 0);
        assert_eq!(u32_to_jint(jint::MAX as u32, "f").unwrap(), jint::MAX);
    }

    #[test]
    fn u32_to_jint_rejects_above_i32_max() {
        assert!(u32_to_jint(jint::MAX as u32 + 1, "f").is_err());
        assert!(u32_to_jint(u32::MAX, "f").is_err());
    }

    // ---- usize_to_jint -----------------------------------------------------

    #[test]
    fn usize_to_jint_accepts_up_to_i32_max() {
        assert_eq!(usize_to_jint(0, "f").unwrap(), 0);
        assert_eq!(usize_to_jint(jint::MAX as usize, "f").unwrap(), jint::MAX);
    }

    #[test]
    fn usize_to_jint_rejects_above_i32_max() {
        assert!(usize_to_jint(jint::MAX as usize + 1, "f").is_err());
    }

    // ---- u64_to_jlong ------------------------------------------------------

    #[test]
    fn u64_to_jlong_accepts_up_to_i64_max() {
        assert_eq!(u64_to_jlong(0, "f").unwrap(), 0);
        assert_eq!(u64_to_jlong(jlong::MAX as u64, "f").unwrap(), jlong::MAX);
    }

    #[test]
    fn u64_to_jlong_rejects_above_i64_max() {
        assert!(u64_to_jlong(jlong::MAX as u64 + 1, "f").is_err());
        assert!(u64_to_jlong(u64::MAX, "f").is_err());
    }

    // ---- properties --------------------------------------------------------

    /// Injectivity, stated directly: on the range both types represent, the
    /// inbound and outbound maps are mutually inverse.
    #[test]
    fn jint_u32_round_trips_on_shared_range() {
        for value in representative_jints() {
            let as_u32 = jint_to_u32(value, "f").unwrap();
            assert_eq!(u32_to_jint(as_u32, "f").unwrap(), value, "value {value}");
        }
    }

    #[test]
    fn jlong_u64_round_trips_on_shared_range() {
        for value in representative_jints() {
            let value = jlong::from(value);
            let as_u64 = jlong_to_u64(value, "f").unwrap();
            assert_eq!(u64_to_jlong(as_u64, "f").unwrap(), value, "value {value}");
        }
    }

    /// Wherever a checked conversion succeeds it agrees with the `as` cast it
    /// replaces. This is the property that makes swapping a raw cast for one of
    /// these a no-op on every input the cast already handled correctly, and so
    /// the one that licenses the lib.rs / migration.rs adoption.
    #[test]
    fn accepted_values_agree_with_as_cast() {
        for value in representative_jints() {
            assert_eq!(jint_to_u32(value, "f").unwrap(), value as u32);
            assert_eq!(jint_to_usize(value, "f").unwrap(), value as usize);

            let wide = jlong::from(value);
            assert_eq!(jlong_to_u64(wide, "f").unwrap(), wide as u64);
            assert_eq!(jlong_to_u32(wide, "f").unwrap(), wide as u32);
        }
    }

    /// The `field` argument exists so a failure names the offending parameter.
    /// A conversion that dropped it would still pass every test above.
    #[test]
    fn errors_name_the_field() {
        let cases = [
            jint_to_u32(-1, "alpha").unwrap_err(),
            jint_to_usize(-1, "alpha").unwrap_err(),
            jlong_to_u64(-1, "alpha").unwrap_err(),
            jlong_to_u32(-1, "alpha").unwrap_err(),
            u32_to_jint(u32::MAX, "alpha").unwrap_err(),
            usize_to_jint(jint::MAX as usize + 1, "alpha").unwrap_err(),
            u64_to_jlong(u64::MAX, "alpha").unwrap_err(),
        ];
        for err in cases {
            assert!(
                err.to_string().contains("alpha"),
                "error did not name the field: {err}"
            );
        }
    }

    // ---- network_from_id ---------------------------------------------------

    #[test]
    fn network_from_id_maps_the_two_wire_values() {
        assert_eq!(
            network_from_id(NETWORK_ID_TESTNET).unwrap(),
            Network::TestNetwork
        );
        assert_eq!(
            network_from_id(NETWORK_ID_MAINNET).unwrap(),
            Network::MainNetwork
        );
    }

    /// Everything outside {0, 1} is rejected, including the legacy "custom
    /// network" id 2 that older callers used.
    #[test]
    fn network_from_id_rejects_everything_else() {
        for id in [-1, 2, 3, jint::MIN, jint::MAX] {
            assert!(network_from_id(id).is_err(), "id {id} was accepted");
        }
    }

    /// The call sites this replaces read `parse_network(network_id as u32)`.
    /// That cast reinterprets a negative jint as a large u32, which the old
    /// match then rejected -- so the accept/reject partition is unchanged and
    /// the adoption is behaviour-preserving. What changes is only the reported
    /// value: the signed input now appears as itself rather than as its
    /// two's-complement reinterpretation.
    #[test]
    fn network_from_id_agrees_with_the_cast_it_replaces() {
        for id in [-1, 0, 1, 2, jint::MIN, jint::MAX] {
            let accepted_now = network_from_id(id).is_ok();
            let accepted_before = matches!(id as u32, 0 | 1);
            assert_eq!(accepted_now, accepted_before, "id {id} changed partition");
        }
        // And the diagnostic actually improved at the negative end.
        let err = network_from_id(-1).unwrap_err().to_string();
        assert!(err.contains("-1"), "unexpected message: {err}");
        assert!(
            !err.contains("4294967295"),
            "reported the cast value: {err}"
        );
    }

    /// The rejected value itself is reported, which is what makes a bad
    /// argument diagnosable from a stack trace alone.
    #[test]
    fn errors_report_the_offending_value() {
        assert!(jint_to_u32(-7, "f").unwrap_err().to_string().contains("-7"));
        assert!(
            u32_to_jint(u32::MAX, "f")
                .unwrap_err()
                .to_string()
                .contains(&u32::MAX.to_string())
        );
    }
}
