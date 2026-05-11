//! JNI bindings for the zcash_voting crate.

use anyhow::anyhow;
use jni::{
    JNIEnv,
    objects::{JByteArray, JClass, JObject, JString, JValue},
    sys::{jboolean, jbyteArray, jint, jlong, jobject, jstring},
};
use serde::Serialize;
use std::{
    collections::HashMap,
    sync::{
        Arc, Mutex, OnceLock,
        atomic::{AtomicI64, Ordering},
    },
};
use zcash_voting as voting;

use voting::storage::{RoundPhase, RoundState, RoundSummary, VoteRecord, VotingDb};

use crate::utils::{
    catch_unwind, exception::unwrap_exc_or, java_nullable_string_to_rust, java_string_to_rust,
};

mod db;
mod helpers;
mod json;
mod rounds;
mod share_tracking;
