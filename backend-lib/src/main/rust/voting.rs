//! JNI bindings for the zcash_voting crate.

use anyhow::anyhow;
use jni::{
    JNIEnv, JavaVM,
    objects::{GlobalRef, JByteArray, JClass, JObject, JObjectArray, JString, JValue},
    sys::{
        JNI_FALSE, JNI_TRUE, jboolean, jbyteArray, jint, jlong, jlongArray, jobject, jobjectArray,
    },
};
use orchard::keys::Scope;
use secrecy::{ExposeSecret, SecretVec};
use std::{
    collections::HashMap,
    sync::{
        Arc, Mutex, OnceLock,
        atomic::{AtomicI64, Ordering},
    },
};
use zcash_client_backend::keys::{UnifiedFullViewingKey, UnifiedSpendingKey};
use zcash_protocol::consensus::{BranchId, Network, NetworkConstants};
use zcash_voting as voting;

use voting::storage::{RoundPhase, RoundState, RoundSummary, VoteRecord, VotingDb};
use voting::tree_sync::VoteTreeSync;
use voting::types::{
    DelegationPirPrecomputeResult, DelegationProofResult, DelegationSubmissionData, GovernancePczt,
    NoopProgressReporter, NoteInfo, ProofProgressReporter, SharePayload, VoteCommitmentBundle,
    WireEncryptedShare, WitnessData,
};

use crate::utils::{
    catch_unwind, exception::unwrap_exc_or, java_nullable_string_to_rust, java_string_to_rust,
    rust_vec_to_java,
};

mod db;
mod delegation;
mod helpers;
mod notes;
mod progress;
mod rounds;
mod share_tracking;
mod tree;
mod util;
mod vote;
