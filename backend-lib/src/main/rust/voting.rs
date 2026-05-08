//! JNI bindings for the zcash_voting crate.

use anyhow::anyhow;
use jni::{
    JNIEnv,
    objects::{JByteArray, JClass},
    sys::{jbyteArray, jint},
};
use zcash_voting as voting;

use crate::utils::{catch_unwind, exception::unwrap_exc_or};

mod helpers;
mod share_tracking;
