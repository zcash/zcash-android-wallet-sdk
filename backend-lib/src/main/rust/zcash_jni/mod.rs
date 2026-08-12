//! Cross-feature JNI marshalling helpers.
//!
//! This module is the one home for conversions that cross the Java/Rust
//! boundary and are not specific to any single feature. Feature-specific
//! marshalling — the `NoteInfo`, round-state and share-payload encoders, say —
//! belongs in that feature's own `jni` submodule (`voting::jni`), not here.
//!
//! # Why this module exists
//!
//! The width-checked conversions below started life as `pub(super)` helpers
//! inside `voting::helpers`, where nothing else in the crate could reach them.
//! The result was measurable: when this module was introduced, `lib.rs` carried
//! 83 raw `as jlong` / `as jint` / `as u32` casts and `migration.rs` another 38,
//! against one apiece across the whole `voting` tree.
//!
//! That gap matters because the two spellings are not equivalent. An `as` cast
//! between integer types is total but not injective: it is defined as
//! truncation (or sign reinterpretation) modulo 2^N, so a value that does not
//! fit crosses the boundary silently as a *different* value. `try_from` is the
//! partial, injective map that agrees with `as` exactly where `as` was already
//! correct, and fails everywhere else. Converting a boundary from the first to
//! the second therefore never changes behaviour on valid input; it only
//! replaces silent corruption with a reported error.
//!
//! So: no `as` casts on values crossing JNI. Use the helpers in [`convert`],
//! and pass a `field` name so the error says which parameter was out of range.
//!
//! # Why the name is not `jni`
//!
//! A crate-root module named `jni` would shadow the external `jni` crate in
//! path resolution rather than merely conflict with it: the local module wins,
//! and every `use jni::JNIEnv` in the crate fails to resolve. Naming the module
//! `zcash_jni` sidesteps that, so the `jni` crate stays reachable as plain
//! `jni::` everywhere. Feature-local submodules are free to be called `jni`
//! (`voting::jni`) because they are not at the crate root.

pub(crate) mod convert;
