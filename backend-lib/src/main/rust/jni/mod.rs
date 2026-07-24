//! This crate's JNI export surface.
//!
//! Every `Java_*` `#[unsafe(no_mangle)] pub extern "C"` function this library exposes to Kotlin
//! lives somewhere under this module tree, and nothing else does. Each submodule here fronts one
//! sibling logic module: `jni::eip681` holds `RustEip681Tool`'s exports while `crate::eip681`
//! holds the parsing logic, and so on. Those sibling modules therefore carry no `Java_*` symbols
//! and no dependency on the `jni` crate; they are ordinary Rust that `cargo test` can call
//! directly.
//!
//! Marshalling (Java class descriptors, object encoders, argument decoders) lives BESIDE the
//! exports it serves, never in a shared grab-bag: `jni::eip681` keeps its own inline because there
//! is little of it. A submodule here starts as a single file and becomes a directory when it
//! grows. Marshalling that is genuinely domain-neutral (`java_bytes_to_rust`, `rust_vec_to_java`,
//! ...) does not belong here at all; it goes in [`crate::utils`], which every export may use.
//!
//! The symbol names here are the ABI Kotlin binds to by name at runtime, so they must never be
//! renamed, and their signatures must keep matching the corresponding `external fun` declarations.
//!
//! Note for anything added under this tree: because this module is named `jni`, a bare `jni::`
//! path inside `lib.rs` (where `mod jni;` is declared) resolves to THIS module, not the `jni`
//! crate. Write `::jni::` there when the external crate is meant.

mod eip681;
