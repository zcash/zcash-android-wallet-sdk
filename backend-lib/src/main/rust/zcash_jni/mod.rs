//! The JNI export surface.
//!
//! Every `Java_*` export lives under this module, one submodule per Java class,
//! together with the marshalling that serves it. The sibling logic modules hold
//! no `extern "C"` functions.
//!
//! The boundary rule is whether a symbol *speaks JNI*, not whether it is an
//! export: anything whose signature mentions [`JNIEnv`] or a `J*` / `j*` type,
//! any Java class descriptor, and any encoder or decoder over `env.new_object`
//! belongs here. Everything expressible in plain Rust types stays in the logic
//! module.
//!
//! The module is named `zcash_jni` rather than `jni` so that a bare `jni::`
//! path keeps resolving to the `jni` crate everywhere, including in the crate
//! root next to `mod zcash_jni;`.
//!
//! [`JNIEnv`]: jni::JNIEnv

pub(crate) mod eip681;
