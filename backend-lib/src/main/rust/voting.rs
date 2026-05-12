//! JNI bindings for the zcash_voting crate.

use anyhow::anyhow;
use jni::{
    objects::{JByteArray, JClass, JObject, JString, JValue},
    sys::{jboolean, jbyteArray, jint, jlong, jobject, jobjectArray, jstring},
    JNIEnv,
};
use orchard::keys::Scope;
use secrecy::{ExposeSecret, SecretVec};
use std::{
    collections::HashMap,
    sync::{
        atomic::{AtomicI64, Ordering},
        Arc, Mutex, OnceLock,
    },
};
use zcash_client_backend::keys::{UnifiedFullViewingKey, UnifiedSpendingKey};
use zcash_protocol::consensus::{BranchId, Network, NetworkConstants};
use zcash_voting as voting;

use voting::storage::{RoundPhase, RoundState, RoundSummary, VoteRecord, VotingDb};
use voting::types::{GovernancePczt, NoteInfo};

use crate::utils::{
    catch_unwind, exception::unwrap_exc_or, java_nullable_string_to_rust, java_string_to_rust,
    rust_vec_to_java,
};

mod db;
mod delegation;
mod helpers;
mod json;
mod notes;
mod rounds;
mod share_tracking;
mod util;
