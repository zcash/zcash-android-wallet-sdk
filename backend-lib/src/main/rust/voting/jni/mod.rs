//! Marshalling between the voting domain types and their Kotlin `Jni*` models.
//!
//! The counterpart to [`crate::zcash_jni`]: that module holds the conversions
//! any feature needs, this one holds the ones that know what a vote is. The
//! split is by what a helper closes over, not by how generic its body looks —
//! `require_32` and `java_bytes32` read as pure plumbing but are bound to
//! `PROTOCOL_FIELD_BYTES`, so they belong on this side of the line.
//!
//! Files are grouped by the domain object being marshalled rather than by
//! direction, so the decoder and encoder for one Kotlin model sit together and
//! a field added to that model has one place to go.
//!
//! Every encoder here allocates into the current JNI local reference frame, so
//! its result is valid only until the enclosing native call returns. The class
//! name constants and payload structs these functions use still live in
//! `voting::helpers`; they move once the marshalling has settled.

// Submodules are declared as their first function arrives, so that every commit
// in the migration compiles with no unused-import warnings.
