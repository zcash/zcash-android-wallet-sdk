//! Width-checked conversions between Java primitives and Rust integer types.
//!
//! Every function here preserves the value exactly: it either produces the same
//! number in the target type, or an error naming the offending field. See the
//! module doc on [`super`] for why this is not interchangeable with an `as`
//! cast.
