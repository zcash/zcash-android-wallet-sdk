//! Width-checked conversions between Java primitives and Rust integer types.
//!
//! Every function here preserves the value exactly: it either produces the same
//! number in the target type, or an error naming the offending field. See the
//! module doc on [`super`] for why this is not interchangeable with an `as`
//! cast.

use anyhow::anyhow;
use jni::sys::{jint, jlong};

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
