//! JNI bindings for the zcash_voting crate.
//!
//! Mirrors rust/src/voting.rs from valargroup/zcash-swift-wallet-sdk (shielded-vote-2.4.10),
//! translating C FFI (`zcashlc_voting_*`) to Android JNI
//! (`Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_*`).
//!
//! Key differences from iOS:
//!   - JNI types (JString, JByteArray) instead of raw C pointer + length pairs.
//!   - Opaque handles encoded as `jlong` (Box::into_raw as i64) instead of *mut ptr.
//!   - `catch_unwind` + `unwrap_exc_or` instead of `catch_panic` + `unwrap_exc_or_null`.
//!   - JSON serialisation uses local JsonXxx wrapper types because zcash_voting types
//!     do not derive Serialize/Deserialize (by design — EncryptedShare has secret fields).
//!   - Progress reporting uses `NoopProgressReporter` (no JNI callback needed for MVP).

use std::{collections::HashMap, sync::Arc};

use anyhow::anyhow;
use jni::{
    JNIEnv,
    objects::{JByteArray, JClass, JObject, JString, JValue},
    sys::{JNI_FALSE, JNI_TRUE, jboolean, jbyteArray, jint, jlong, jobject, jstring},
};
use rusqlite::named_params;
use orchard::keys::Scope;
use serde::{Deserialize, Serialize};
use zcash_client_backend::keys::{UnifiedFullViewingKey, UnifiedSpendingKey};
use zcash_protocol::consensus::Network;

use zcash_voting as voting;
use voting::storage::{RoundPhase, RoundState, RoundSummary, VotingDb};
use voting::tree_sync::VoteTreeSync;
use voting::types::{
    DelegationProofResult, DelegationSubmissionData, GovernancePczt, NoteInfo, NoopProgressReporter,
    SharePayload, VoteCommitmentBundle, WireEncryptedShare, WitnessData,
};

use crate::utils::{
    catch_unwind, exception::unwrap_exc_or, java_nullable_string_to_rust, java_string_to_rust,
};

// =============================================================================
// Opaque handle
// =============================================================================

pub(crate) struct VotingDatabaseHandle {
    db: Arc<VotingDb>,
    tree_sync: VoteTreeSync,
}

fn handle_from_jlong(handle: jlong) -> anyhow::Result<&'static VotingDatabaseHandle> {
    if handle == 0 {
        return Err(anyhow!("VotingDatabaseHandle is null"));
    }
    // SAFETY: pointer was allocated by openVotingDb via Box::into_raw.
    Ok(unsafe { &*(handle as *const VotingDatabaseHandle) })
}

fn network_from_id(id: jint) -> anyhow::Result<Network> {
    match id {
        0 => Ok(Network::MainNetwork),
        1 => Ok(Network::TestNetwork),
        _ => Err(anyhow!("invalid network_id {}", id)),
    }
}

/// Compute the 43-byte raw Orchard address for a hotkey seed.
///
/// The governance PCZT needs the hotkey's Orchard address as the delegation output.
/// This is distinct from VotingHotkey.address (which is a Pallas-based address used
/// for vote commitment signing).
fn hotkey_orchard_raw_address(
    hotkey_seed: &[u8],
    network: Network,
    account_index: u32,
) -> anyhow::Result<Vec<u8>> {
    let account_id = zip32::AccountId::try_from(account_index)
        .map_err(|_| anyhow!("invalid account_index {}", account_index))?;
    let usk = UnifiedSpendingKey::from_seed(&network, hotkey_seed, account_id)
        .map_err(|e| anyhow!("failed to derive hotkey USK: {}", e))?;
    let fvk = usk.to_unified_full_viewing_key();
    let orchard_fvk = fvk
        .orchard()
        .ok_or_else(|| anyhow!("hotkey UFVK has no Orchard component"))?;
    let addr = orchard_fvk.address_at(0u32, Scope::External);
    Ok(addr.to_raw_address_bytes().to_vec())
}

/// Extract 96-byte Orchard FVK bytes from a UFVK string.
fn orchard_fvk_bytes(ufvk_str: &str, network: Network) -> anyhow::Result<Vec<u8>> {
    let ufvk = UnifiedFullViewingKey::decode(&network, ufvk_str)
        .map_err(|e| anyhow!("failed to decode UFVK: {}", e))?;
    let fvk = ufvk
        .orchard()
        .ok_or_else(|| anyhow!("UFVK has no Orchard component"))?;
    Ok(fvk.to_bytes().to_vec())
}

/// NU6 consensus branch ID (same on mainnet and testnet).
const NU6_BRANCH_ID: u32 = 0xC8E71055;

fn coin_type_for(network: Network) -> u32 {
    match network {
        Network::MainNetwork => 133,
        Network::TestNetwork => 1,
    }
}

// =============================================================================
// JSON wrapper types
//
// The zcash_voting crate types do not derive Serialize/Deserialize.
// We use local wrapper types for crossing the JNI boundary as JSON strings.
// Byte arrays are encoded as lowercase hex strings to match the iOS FFI layer.
// =============================================================================

/// Hex-encode helper.
fn hex_enc(b: &[u8]) -> String {
    hex::encode(b)
}

/// Hex-decode helper.
fn hex_dec(s: &str, field: &str) -> anyhow::Result<Vec<u8>> {
    hex::decode(s).map_err(|e| anyhow!("field '{}': invalid hex: {}", field, e))
}

#[derive(Serialize, Deserialize)]
struct JsonNoteInfo {
    commitment: String,   // hex
    nullifier: String,    // hex
    value: u64,
    position: u64,
    diversifier: String,  // hex
    rho: String,          // hex
    rseed: String,        // hex
    scope: u32,
    ufvk_str: String,
}

impl TryFrom<JsonNoteInfo> for NoteInfo {
    type Error = anyhow::Error;
    fn try_from(j: JsonNoteInfo) -> anyhow::Result<Self> {
        Ok(NoteInfo {
            commitment: hex_dec(&j.commitment, "commitment")?,
            nullifier: hex_dec(&j.nullifier, "nullifier")?,
            value: j.value,
            position: j.position,
            diversifier: hex_dec(&j.diversifier, "diversifier")?,
            rho: hex_dec(&j.rho, "rho")?,
            rseed: hex_dec(&j.rseed, "rseed")?,
            scope: j.scope,
            ufvk_str: j.ufvk_str,
        })
    }
}

#[derive(Serialize, Deserialize)]
struct JsonWitnessData {
    note_commitment: String, // hex
    position: u64,
    root: String,            // hex
    auth_path: Vec<String>,  // hex elements
}

impl TryFrom<JsonWitnessData> for WitnessData {
    type Error = anyhow::Error;
    fn try_from(j: JsonWitnessData) -> anyhow::Result<Self> {
        Ok(WitnessData {
            note_commitment: hex_dec(&j.note_commitment, "note_commitment")?,
            position: j.position,
            root: hex_dec(&j.root, "root")?,
            auth_path: j
                .auth_path
                .iter()
                .enumerate()
                .map(|(i, h)| hex_dec(h, &format!("auth_path[{i}]")))
                .collect::<anyhow::Result<_>>()?,
        })
    }
}

impl From<WitnessData> for JsonWitnessData {
    fn from(w: WitnessData) -> Self {
        JsonWitnessData {
            note_commitment: hex_enc(&w.note_commitment),
            position: w.position,
            root: hex_enc(&w.root),
            auth_path: w.auth_path.iter().map(|h| hex_enc(h)).collect(),
        }
    }
}

/// VanWitness JSON (returned by generateVanWitnessJson / passed to buildVoteCommitmentJson).
#[derive(Serialize, Deserialize)]
struct JsonVanWitness {
    auth_path: Vec<String>, // hex, each 32 bytes
    position: u32,
    anchor_height: u32,
}

impl From<voting::tree_sync::VanWitness> for JsonVanWitness {
    fn from(w: voting::tree_sync::VanWitness) -> Self {
        JsonVanWitness {
            auth_path: w.auth_path.iter().map(|h| hex_enc(h)).collect(),
            position: w.position,
            anchor_height: w.anchor_height,
        }
    }
}

#[derive(Serialize)]
struct JsonRoundSummary {
    round_id: String,
    phase: u32,
    snapshot_height: u64,
    created_at: u64,
}

impl From<RoundSummary> for JsonRoundSummary {
    fn from(r: RoundSummary) -> Self {
        JsonRoundSummary {
            round_id: r.round_id,
            phase: round_phase_to_u32(r.phase),
            snapshot_height: r.snapshot_height,
            created_at: r.created_at,
        }
    }
}

fn round_phase_to_u32(p: RoundPhase) -> u32 {
    match p {
        RoundPhase::Initialized => 0,
        RoundPhase::HotkeyGenerated => 1,
        RoundPhase::DelegationConstructed => 2,
        RoundPhase::DelegationProved => 3,
        RoundPhase::VoteReady => 4,
    }
}

#[derive(Serialize)]
struct JsonGovernancePczt {
    pczt_bytes: String,
    rk: String,
    alpha: String,
    nf_signed: String,
    cmx_new: String,
    gov_nullifiers: Vec<String>,
    van: String,
    van_comm_rand: String,
    dummy_nullifiers: Vec<String>,
    rho_signed: String,
    padded_cmx: Vec<String>,
    rseed_signed: String,
    rseed_output: String,
    action_bytes: String,
    action_index: u32,
    padded_note_secrets: Vec<[String; 2]>, // [[rho_hex, rseed_hex], ...]
    pczt_sighash: String,
}

impl From<GovernancePczt> for JsonGovernancePczt {
    fn from(g: GovernancePczt) -> Self {
        JsonGovernancePczt {
            pczt_bytes: hex_enc(&g.pczt_bytes),
            rk: hex_enc(&g.rk),
            alpha: hex_enc(&g.alpha),
            nf_signed: hex_enc(&g.nf_signed),
            cmx_new: hex_enc(&g.cmx_new),
            gov_nullifiers: g.gov_nullifiers.iter().map(|v| hex_enc(v)).collect(),
            van: hex_enc(&g.van),
            van_comm_rand: hex_enc(&g.van_comm_rand),
            dummy_nullifiers: g.dummy_nullifiers.iter().map(|v| hex_enc(v)).collect(),
            rho_signed: hex_enc(&g.rho_signed),
            padded_cmx: g.padded_cmx.iter().map(|v| hex_enc(v)).collect(),
            rseed_signed: hex_enc(&g.rseed_signed),
            rseed_output: hex_enc(&g.rseed_output),
            action_bytes: hex_enc(&g.action_bytes),
            action_index: g.action_index as u32,
            padded_note_secrets: g
                .padded_note_secrets
                .iter()
                .map(|(rho, rseed)| [hex_enc(rho), hex_enc(rseed)])
                .collect(),
            pczt_sighash: hex_enc(&g.pczt_sighash),
        }
    }
}

#[derive(Serialize)]
struct JsonDelegationProofResult {
    proof: String,
    public_inputs: Vec<String>,
    nf_signed: String,
    cmx_new: String,
    gov_nullifiers: Vec<String>,
    van_comm: String,
    rk: String,
}

impl From<DelegationProofResult> for JsonDelegationProofResult {
    fn from(r: DelegationProofResult) -> Self {
        JsonDelegationProofResult {
            proof: hex_enc(&r.proof),
            public_inputs: r.public_inputs.iter().map(|v| hex_enc(v)).collect(),
            nf_signed: hex_enc(&r.nf_signed),
            cmx_new: hex_enc(&r.cmx_new),
            gov_nullifiers: r.gov_nullifiers.iter().map(|v| hex_enc(v)).collect(),
            van_comm: hex_enc(&r.van_comm),
            rk: hex_enc(&r.rk),
        }
    }
}

#[derive(Serialize, Deserialize)]
struct JsonWireEncryptedShare {
    c1: String,          // hex
    c2: String,          // hex
    share_index: u32,
}

impl TryFrom<JsonWireEncryptedShare> for WireEncryptedShare {
    type Error = anyhow::Error;
    fn try_from(j: JsonWireEncryptedShare) -> anyhow::Result<Self> {
        Ok(WireEncryptedShare {
            c1: hex_dec(&j.c1, "c1")?,
            c2: hex_dec(&j.c2, "c2")?,
            share_index: j.share_index,
        })
    }
}

/// VoteCommitmentBundle — serialized for Kotlin; only wire-safe share fields included.
#[derive(Serialize, Deserialize)]
struct JsonVoteCommitmentBundle {
    van_nullifier: String,
    vote_authority_note_new: String,
    vote_commitment: String,
    proposal_id: u32,
    proof: String,
    /// Wire-safe shares only (c1, c2, share_index — no secret plaintext or randomness).
    enc_shares: Vec<JsonWireEncryptedShare>,
    anchor_height: u32,
    vote_round_id: String,
    shares_hash: String,
    share_blinds: Vec<String>,
    share_comms: Vec<String>,
    r_vpk_bytes: String,
    alpha_v: String,
}

impl From<&VoteCommitmentBundle> for JsonVoteCommitmentBundle {
    fn from(b: &VoteCommitmentBundle) -> Self {
        JsonVoteCommitmentBundle {
            van_nullifier: hex_enc(&b.van_nullifier),
            vote_authority_note_new: hex_enc(&b.vote_authority_note_new),
            vote_commitment: hex_enc(&b.vote_commitment),
            proposal_id: b.proposal_id,
            proof: hex_enc(&b.proof),
            enc_shares: b
                .enc_shares
                .iter()
                .map(|s| JsonWireEncryptedShare {
                    c1: hex_enc(&s.c1),
                    c2: hex_enc(&s.c2),
                    share_index: s.share_index,
                })
                .collect(),
            anchor_height: b.anchor_height,
            vote_round_id: b.vote_round_id.clone(),
            shares_hash: hex_enc(&b.shares_hash),
            share_blinds: b.share_blinds.iter().map(|v| hex_enc(v)).collect(),
            share_comms: b.share_comms.iter().map(|v| hex_enc(v)).collect(),
            r_vpk_bytes: hex_enc(&b.r_vpk_bytes),
            alpha_v: hex_enc(&b.alpha_v),
        }
    }
}

#[derive(Serialize)]
struct JsonSharePayload {
    shares_hash: String,
    proposal_id: u32,
    vote_decision: u32,
    enc_share: JsonWireEncryptedShare,
    tree_position: u64,
    all_enc_shares: Vec<JsonWireEncryptedShare>,
    share_comms: Vec<String>,
    primary_blind: String,
}

impl From<SharePayload> for JsonSharePayload {
    fn from(p: SharePayload) -> Self {
        JsonSharePayload {
            shares_hash: hex_enc(&p.shares_hash),
            proposal_id: p.proposal_id,
            vote_decision: p.vote_decision,
            enc_share: JsonWireEncryptedShare {
                c1: hex_enc(&p.enc_share.c1),
                c2: hex_enc(&p.enc_share.c2),
                share_index: p.enc_share.share_index,
            },
            tree_position: p.tree_position,
            all_enc_shares: p
                .all_enc_shares
                .iter()
                .map(|s| JsonWireEncryptedShare {
                    c1: hex_enc(&s.c1),
                    c2: hex_enc(&s.c2),
                    share_index: s.share_index,
                })
                .collect(),
            share_comms: p.share_comms.iter().map(|v| hex_enc(v)).collect(),
            primary_blind: hex_enc(&p.primary_blind),
        }
    }
}

#[derive(Serialize)]
struct JsonDelegationSubmission {
    proof: String,
    rk: String,
    spend_auth_sig: String,
    sighash: String,
    nf_signed: String,
    cmx_new: String,
    gov_comm: String,
    gov_nullifiers: Vec<String>,
    alpha: String,
    vote_round_id: String,
}

impl From<DelegationSubmissionData> for JsonDelegationSubmission {
    fn from(d: DelegationSubmissionData) -> Self {
        JsonDelegationSubmission {
            proof: hex_enc(&d.proof),
            rk: hex_enc(&d.rk),
            spend_auth_sig: hex_enc(&d.spend_auth_sig),
            sighash: hex_enc(&d.sighash),
            nf_signed: hex_enc(&d.nf_signed),
            cmx_new: hex_enc(&d.cmx_new),
            gov_comm: hex_enc(&d.gov_comm),
            gov_nullifiers: d.gov_nullifiers.iter().map(|v| hex_enc(v)).collect(),
            alpha: hex_enc(&d.alpha),
            vote_round_id: d.vote_round_id,
        }
    }
}

#[derive(Serialize)]
struct JsonCommitmentBundleRecord {
    bundle_json: String,
    vc_tree_position: u64,
}

#[derive(Serialize)]
struct JsonShareDelegationRecord {
    round_id: String,
    bundle_index: u32,
    proposal_id: u32,
    share_index: u32,
    sent_to_urls: Vec<String>,
    nullifier: String,
    confirmed: bool,
    submit_at: u64,
    created_at: u64,
}

impl From<voting::ShareDelegationRecord> for JsonShareDelegationRecord {
    fn from(record: voting::ShareDelegationRecord) -> Self {
        JsonShareDelegationRecord {
            round_id: record.round_id,
            bundle_index: record.bundle_index,
            proposal_id: record.proposal_id,
            share_index: record.share_index,
            sent_to_urls: record.sent_to_urls,
            nullifier: hex_enc(&record.nullifier),
            confirmed: record.confirmed,
            submit_at: record.submit_at,
            created_at: record.created_at,
        }
    }
}

// Convenience: deserialise a JSON string into T, throwing a JNI exception on error.
// Lifetimes are left implicit so this works inside catch_unwind closures.
fn json_from_jstring<T: for<'de> Deserialize<'de>>(
    env: &mut JNIEnv<'_>,
    js: &JString<'_>,
    field: &str,
) -> anyhow::Result<T> {
    let s = java_string_to_rust(env, js)?;
    serde_json::from_str(&s).map_err(|e| anyhow!("{} JSON parse error: {}", field, e))
}

fn json_to_jstring<T: Serialize>(env: &mut JNIEnv<'_>, value: &T) -> anyhow::Result<jstring> {
    let s = serde_json::to_string(value)
        .map_err(|e| anyhow!("JSON serialization error: {}", e))?;
    Ok(env.new_string(s)?.into_raw())
}

fn select_bundle_notes(
    conn: &rusqlite::Connection,
    round_id: &str,
    wallet_id: &str,
    bundle_index: u32,
    notes: &[NoteInfo],
) -> anyhow::Result<Vec<NoteInfo>> {
    let positions = voting::storage::queries::load_bundle_note_positions(
        conn,
        round_id,
        wallet_id,
        bundle_index,
    )
    .map_err(|e| anyhow!("load_bundle_note_positions: {}", e))?;

    let mut notes_by_position = HashMap::with_capacity(notes.len());
    for note in notes.iter().cloned() {
        let position = note.position;
        if notes_by_position.insert(position, note).is_some() {
            return Err(anyhow!(
                "duplicate note position {} in provided notes_json",
                position
            ));
        }
    }

    positions
        .into_iter()
        .map(|position| {
            notes_by_position.remove(&position).ok_or_else(|| {
                anyhow!(
                    "bundle {} is missing note position {} from provided notes_json",
                    bundle_index,
                    position
                )
            })
        })
        .collect()
}

fn replace_bundle_witnesses(
    conn: &rusqlite::Connection,
    round_id: &str,
    wallet_id: &str,
    bundle_index: u32,
    witnesses: &[WitnessData],
) -> anyhow::Result<()> {
    conn.execute(
        "DELETE FROM witnesses
         WHERE round_id = :round_id AND wallet_id = :wallet_id AND bundle_index = :bundle_index",
        named_params! {
            ":round_id": round_id,
            ":wallet_id": wallet_id,
            ":bundle_index": bundle_index as i64,
        },
    )
    .map_err(|e| anyhow!("clear_witnesses: {}", e))?;

    voting::storage::queries::store_witnesses(
        conn,
        round_id,
        wallet_id,
        bundle_index,
        witnesses,
    )
    .map_err(|e| anyhow!("store_witnesses: {}", e))
}

// =============================================================================
// A. Database lifecycle
// =============================================================================

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_openVotingDb<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_path: JString<'local>,
) -> jlong {
    let res = catch_unwind(&mut env, |env| {
        let path = java_string_to_rust(env, &db_path)?;
        let db = VotingDb::open(&path)
            .map_err(|e| anyhow!("VotingDb::open failed: {}", e))?;
        let handle = Box::new(VotingDatabaseHandle {
            db: Arc::new(db),
            tree_sync: VoteTreeSync::new(),
        });
        Ok(Box::into_raw(handle) as jlong)
    });
    unwrap_exc_or(&mut env, res, 0)
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_closeVotingDb<
    'local,
>(
    mut _env: JNIEnv<'local>,
    _: JClass<'local>,
    db_handle: jlong,
) {
    if db_handle != 0 {
        unsafe { drop(Box::from_raw(db_handle as *mut VotingDatabaseHandle)) };
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_setWalletId<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_handle: jlong,
    wallet_id: JString<'local>,
) -> jboolean {
    let res = catch_unwind(&mut env, |env| {
        let handle = handle_from_jlong(db_handle)?;
        let id = java_string_to_rust(env, &wallet_id)?;
        handle.db.set_wallet_id(&id);
        Ok(JNI_TRUE)
    });
    unwrap_exc_or(&mut env, res, JNI_FALSE)
}

// =============================================================================
// B. Round management
// =============================================================================

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_initRound<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_handle: jlong,
    round_id: JString<'local>,
    snapshot_height: jlong,
    ea_pk: JByteArray<'local>,
    nc_root: JByteArray<'local>,
    nullifier_imt_root: JByteArray<'local>,
    session_json: JString<'local>,
) -> jboolean {
    let res = catch_unwind(&mut env, |env| {
        let handle = handle_from_jlong(db_handle)?;
        let params = voting::types::VotingRoundParams {
            vote_round_id: java_string_to_rust(env, &round_id)?,
            snapshot_height: snapshot_height as u64,
            ea_pk: env.convert_byte_array(&ea_pk)?,
            nc_root: env.convert_byte_array(&nc_root)?,
            nullifier_imt_root: env.convert_byte_array(&nullifier_imt_root)?,
        };
        let session = java_nullable_string_to_rust(env, &session_json)?;
        handle
            .db
            .init_round(&params, session.as_deref())
            .map_err(|e| anyhow!("init_round: {}", e))?;
        Ok(JNI_TRUE)
    });
    unwrap_exc_or(&mut env, res, JNI_FALSE)
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_getRoundState<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_handle: jlong,
    round_id: JString<'local>,
) -> jobject {
    let res = catch_unwind(&mut env, |env| {
        let handle = handle_from_jlong(db_handle)?;
        let state = handle
            .db
            .get_round_state(&java_string_to_rust(env, &round_id)?)
            .map_err(|e| anyhow!("get_round_state: {}", e))?;
        make_ffi_round_state(env, state)
    });
    unwrap_exc_or(&mut env, res, JObject::null().into_raw())
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_listRoundsJson<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_handle: jlong,
) -> jstring {
    let res = catch_unwind(&mut env, |env| {
        let handle = handle_from_jlong(db_handle)?;
        let rounds: Vec<JsonRoundSummary> = handle
            .db
            .list_rounds()
            .map_err(|e| anyhow!("list_rounds: {}", e))?
            .into_iter()
            .map(JsonRoundSummary::from)
            .collect();
        json_to_jstring(env, &rounds)
    });
    unwrap_exc_or(&mut env, res, std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_clearRound<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_handle: jlong,
    round_id: JString<'local>,
) -> jboolean {
    let res = catch_unwind(&mut env, |env| {
        let handle = handle_from_jlong(db_handle)?;
        handle
            .db
            .clear_round(&java_string_to_rust(env, &round_id)?)
            .map_err(|e| anyhow!("clear_round: {}", e))?;
        Ok(JNI_TRUE)
    });
    unwrap_exc_or(&mut env, res, JNI_FALSE)
}

// =============================================================================
// C. Note setup
// =============================================================================

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_setupBundles<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_handle: jlong,
    round_id: JString<'local>,
    notes_json: JString<'local>,
) -> jobject {
    let res = catch_unwind(&mut env, |env| {
        let handle = handle_from_jlong(db_handle)?;
        let json_notes: Vec<JsonNoteInfo> =
            json_from_jstring(env, &notes_json, "notesJson")?;
        let notes: Vec<NoteInfo> = json_notes
            .into_iter()
            .map(NoteInfo::try_from)
            .collect::<anyhow::Result<_>>()?;
        let (count, weight) = handle
            .db
            .setup_bundles(&java_string_to_rust(env, &round_id)?, &notes)
            .map_err(|e| anyhow!("setup_bundles: {}", e))?;
        make_ffi_bundle_setup_result(env, count, weight)
    });
    unwrap_exc_or(&mut env, res, JObject::null().into_raw())
}

// =============================================================================
// D. Hotkey generation
// =============================================================================

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_generateHotkey<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_handle: jlong,
    round_id: JString<'local>,
    seed: JByteArray<'local>,
) -> jobject {
    let res = catch_unwind(&mut env, |env| {
        let handle = handle_from_jlong(db_handle)?;
        let hotkey = handle
            .db
            .generate_hotkey(
                &java_string_to_rust(env, &round_id)?,
                &env.convert_byte_array(&seed)?,
            )
            .map_err(|e| anyhow!("generate_hotkey: {}", e))?;
        make_ffi_voting_hotkey(env, hotkey)
    });
    unwrap_exc_or(&mut env, res, JObject::null().into_raw())
}

// =============================================================================
// E. Governance PCZT
// =============================================================================

/// Builds the governance PCZT for bundle [bundleIndex].
///
/// Additional parameters vs the simplified original Kotlin API:
/// - [notesJson]       JSON array of NoteInfo for this bundle
/// - [hotkeyRawSeed]   32-byte hotkey seed — used to derive the 43-byte Orchard hotkey address
/// - [seedFingerprint] 32-byte ZIP-32 seed fingerprint of the wallet
/// - [roundName]       Human-readable round name (from session data)
/// - [addressIndex]    ZIP-32 address index (normally 0)
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_buildGovernancePcztJson<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_handle: jlong,
    round_id: JString<'local>,
    bundle_index: jint,
    ufvk: JString<'local>,
    network_id: jint,
    account_index: jint,
    notes_json: JString<'local>,
    hotkey_raw_seed: JByteArray<'local>,
    seed_fingerprint: JByteArray<'local>,
    round_name: JString<'local>,
    address_index: jint,
) -> jstring {
    let res = catch_unwind(&mut env, |env| {
        let handle = handle_from_jlong(db_handle)?;
        let network = network_from_id(network_id)?;
        let ufvk_str = java_string_to_rust(env, &ufvk)?;
        let fvk_bytes = orchard_fvk_bytes(&ufvk_str, network)?;

        let seed_bytes = env.convert_byte_array(&hotkey_raw_seed)?;
        let hotkey_raw_address =
            hotkey_orchard_raw_address(&seed_bytes, network, account_index as u32)?;

        let sf_bytes = env.convert_byte_array(&seed_fingerprint)?;
        let sf_arr: [u8; 32] = sf_bytes
            .as_slice()
            .try_into()
            .map_err(|_| anyhow!("seedFingerprint must be 32 bytes"))?;

        let json_notes: Vec<JsonNoteInfo> =
            json_from_jstring(env, &notes_json, "notesJson")?;
        let notes: Vec<NoteInfo> = json_notes
            .into_iter()
            .map(NoteInfo::try_from)
            .collect::<anyhow::Result<_>>()?;

        let round_id_str = java_string_to_rust(env, &round_id)?;
        let round_name_str = java_string_to_rust(env, &round_name)?;

        let pczt = handle
            .db
            .build_governance_pczt(
                &round_id_str,
                bundle_index as u32,
                &notes,
                &fvk_bytes,
                &hotkey_raw_address,
                NU6_BRANCH_ID,
                coin_type_for(network),
                &sf_arr,
                account_index as u32,
                &round_name_str,
                address_index as u32,
            )
            .map_err(|e| anyhow!("build_governance_pczt: {}", e))?;

        json_to_jstring(env, &JsonGovernancePczt::from(pczt))
    });
    unwrap_exc_or(&mut env, res, std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_extractPcztSighash<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    pczt_bytes: JByteArray<'local>,
) -> jbyteArray {
    let res = catch_unwind(&mut env, |env| {
        let bytes = env.convert_byte_array(&pczt_bytes)?;
        let sighash = voting::action::extract_pczt_sighash(&bytes)
            .map_err(|e| anyhow!("extract_pczt_sighash: {}", e))?;
        Ok(env.byte_array_from_slice(&sighash)?.into_raw())
    });
    unwrap_exc_or(&mut env, res, std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_extractSpendAuthSig<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    signed_pczt_bytes: JByteArray<'local>,
    action_index: jint,
) -> jbyteArray {
    let res = catch_unwind(&mut env, |env| {
        let bytes = env.convert_byte_array(&signed_pczt_bytes)?;
        let sig = voting::action::extract_spend_auth_sig(&bytes, action_index as usize)
            .map_err(|e| anyhow!("extract_spend_auth_sig: {}", e))?;
        Ok(env.byte_array_from_slice(&sig)?.into_raw())
    });
    unwrap_exc_or(&mut env, res, std::ptr::null_mut())
}

// =============================================================================
// F. Witness management
// =============================================================================

/// Cache Merkle witnesses for notes in a bundle (must be called before buildAndProveDelegationJson).
///
/// [witnessesJson] JSON array of WitnessData objects (one per note in the bundle).
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_storeWitnesses<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_handle: jlong,
    round_id: JString<'local>,
    bundle_index: jint,
    witnesses_json: JString<'local>,
) -> jboolean {
    let res = catch_unwind(&mut env, |env| {
        let handle = handle_from_jlong(db_handle)?;
        let json_witnesses: Vec<JsonWitnessData> =
            json_from_jstring(env, &witnesses_json, "witnessesJson")?;
        let witnesses: Vec<WitnessData> = json_witnesses
            .into_iter()
            .map(WitnessData::try_from)
            .collect::<anyhow::Result<_>>()?;
        handle
            .db
            .store_witnesses(
                &java_string_to_rust(env, &round_id)?,
                bundle_index as u32,
                &witnesses,
            )
            .map_err(|e| anyhow!("store_witnesses: {}", e))?;
        Ok(JNI_TRUE)
    });
    unwrap_exc_or(&mut env, res, JNI_FALSE)
}

// =============================================================================
// G. Delegation proof (ZKP1)
// =============================================================================

/// Generates the Halo2 delegation proof. Long-running — call on a background coroutine.
///
/// [notesJson]       JSON array of NoteInfo for this bundle (same notes as storeWitnesses)
/// [hotkeyRawSeed]   32-byte hotkey seed — used to re-derive hotkey Orchard address
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_buildAndProveDelegationJson<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_handle: jlong,
    round_id: JString<'local>,
    bundle_index: jint,
    pir_server_url: JString<'local>,
    network_id: jint,
    notes_json: JString<'local>,
    hotkey_raw_seed: JByteArray<'local>,
) -> jstring {
    let res = catch_unwind(&mut env, |env| {
        let handle = handle_from_jlong(db_handle)?;
        let network = network_from_id(network_id)?;

        let seed_bytes = env.convert_byte_array(&hotkey_raw_seed)?;
        let hotkey_raw_address = hotkey_orchard_raw_address(&seed_bytes, network, 0)?;

        let json_notes: Vec<JsonNoteInfo> =
            json_from_jstring(env, &notes_json, "notesJson")?;
        let notes: Vec<NoteInfo> = json_notes
            .into_iter()
            .map(NoteInfo::try_from)
            .collect::<anyhow::Result<_>>()?;

        let result = handle
            .db
            .build_and_prove_delegation(
                &java_string_to_rust(env, &round_id)?,
                bundle_index as u32,
                &notes,
                &hotkey_raw_address,
                &java_string_to_rust(env, &pir_server_url)?,
                network_id as u32,
                &NoopProgressReporter,
            )
            .map_err(|e| anyhow!("build_and_prove_delegation: {}", e))?;

        json_to_jstring(env, &JsonDelegationProofResult::from(result))
    });
    unwrap_exc_or(&mut env, res, std::ptr::null_mut())
}

// =============================================================================
// H. Vote commitment tree (VAN witness)
// =============================================================================

/// Synchronise the per-round vote commitment tree from the chain node.
///
/// Returns the latest synced block height as a Long, or -1 on error.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_syncVoteTree<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_handle: jlong,
    round_id: JString<'local>,
    node_url: JString<'local>,
) -> jlong {
    let res = catch_unwind(&mut env, |env| {
        let handle = handle_from_jlong(db_handle)?;
        let height = handle
            .tree_sync
            .sync(
                &handle.db,
                &java_string_to_rust(env, &round_id)?,
                &java_string_to_rust(env, &node_url)?,
            )
            .map_err(|e| anyhow!("sync_vote_tree: {}", e))?;
        Ok(height as jlong)
    });
    unwrap_exc_or(&mut env, res, -1)
}

/// Store the VAN leaf position after the delegation TX is confirmed.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_storeVanPosition<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_handle: jlong,
    round_id: JString<'local>,
    bundle_index: jint,
    position: jint,
) -> jboolean {
    let res = catch_unwind(&mut env, |env| {
        let handle = handle_from_jlong(db_handle)?;
        handle
            .db
            .store_van_position(
                &java_string_to_rust(env, &round_id)?,
                bundle_index as u32,
                position as u32,
            )
            .map_err(|e| anyhow!("store_van_position: {}", e))?;
        Ok(JNI_TRUE)
    });
    unwrap_exc_or(&mut env, res, JNI_FALSE)
}

/// Generate the Merkle witness for the VAN note. Must be called after [syncVoteTree].
///
/// Returns JSON-encoded VanWitness, or null on error.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_generateVanWitnessJson<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_handle: jlong,
    round_id: JString<'local>,
    bundle_index: jint,
    anchor_height: jint,
) -> jstring {
    let res = catch_unwind(&mut env, |env| {
        let handle = handle_from_jlong(db_handle)?;
        let witness = handle
            .tree_sync
            .generate_van_witness(
                &handle.db,
                &java_string_to_rust(env, &round_id)?,
                bundle_index as u32,
                anchor_height as u32,
            )
            .map_err(|e| anyhow!("generate_van_witness: {}", e))?;
        json_to_jstring(env, &JsonVanWitness::from(witness))
    });
    unwrap_exc_or(&mut env, res, std::ptr::null_mut())
}

// =============================================================================
// I. Vote commitment (ZKP2)
// =============================================================================

/// Build the vote commitment proof for one proposal choice.
///
/// [witnessJson]   JSON-encoded VanWitness returned by [generateVanWitnessJson]
/// [singleShare]   true for single-share mode (test/dev only)
///
/// Returns JSON-encoded VoteCommitmentBundle (wire-safe — no secret share fields).
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_buildVoteCommitmentJson<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_handle: jlong,
    round_id: JString<'local>,
    bundle_index: jint,
    hotkey_seed: JByteArray<'local>,
    proposal_id: jint,
    choice: jint,
    num_options: jint,
    witness_json: JString<'local>,
    van_position: jint,
    anchor_height: jint,
    network_id: jint,
    single_share: jboolean,
) -> jstring {
    let res = catch_unwind(&mut env, |env| {
        let handle = handle_from_jlong(db_handle)?;
        let seed = env.convert_byte_array(&hotkey_seed)?;

        // Extract auth_path from VanWitness JSON (24 × 32-byte sibling hashes).
        let van: JsonVanWitness = json_from_jstring(env, &witness_json, "witnessJson")?;
        let auth_path: Vec<[u8; 32]> = van
            .auth_path
            .iter()
            .enumerate()
            .map(|(i, h)| {
                let bytes = hex_dec(h, &format!("auth_path[{i}]"))?;
                bytes
                    .try_into()
                    .map_err(|_| anyhow!("auth_path[{i}] must be 32 bytes"))
            })
            .collect::<anyhow::Result<_>>()?;

        let bundle = handle
            .db
            .build_vote_commitment(
                &java_string_to_rust(env, &round_id)?,
                bundle_index as u32,
                &seed,
                network_id as u32,
                proposal_id as u32,
                choice as u32,
                num_options as u32,
                &auth_path,
                van_position as u32,
                anchor_height as u32,
                single_share == JNI_TRUE,
                &NoopProgressReporter,
            )
            .map_err(|e| anyhow!("build_vote_commitment: {}", e))?;

        json_to_jstring(env, &JsonVoteCommitmentBundle::from(&bundle))
    });
    unwrap_exc_or(&mut env, res, std::ptr::null_mut())
}

// =============================================================================
// J. Share payloads
// =============================================================================

/// Build share payloads for distribution to tally-server helpers.
///
/// [encSharesJson]    JSON array of WireEncryptedShare (c1/c2/share_index)
///                    extracted from the VoteCommitmentBundle.enc_shares field.
/// [commitmentJson]   Full JSON-encoded VoteCommitmentBundle.
/// [vcTreePosition]   Position of the vote commitment leaf in the VC tree
///                    (known after the cast-vote TX is confirmed on chain).
///
/// Returns JSON array of SharePayload.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_buildSharePayloadsJson<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    enc_shares_json: JString<'local>,
    commitment_json: JString<'local>,
    vote_decision: jint,
    num_options: jint,
    vc_tree_position: jlong,
    single_share_mode: jboolean,
) -> jstring {
    let res = catch_unwind(&mut env, |env| {
        // Deserialize enc_shares (wire-safe public components)
        let json_shares: Vec<JsonWireEncryptedShare> =
            json_from_jstring(env, &enc_shares_json, "encSharesJson")?;
        let enc_shares: Vec<WireEncryptedShare> = json_shares
            .into_iter()
            .map(WireEncryptedShare::try_from)
            .collect::<anyhow::Result<_>>()?;

        // Deserialize VoteCommitmentBundle from JSON (reconstruct wire-safe version)
        let json_bundle: JsonVoteCommitmentBundle =
            json_from_jstring(env, &commitment_json, "commitmentJson")?;
        let commitment = VoteCommitmentBundle {
            van_nullifier: hex_dec(&json_bundle.van_nullifier, "van_nullifier")?,
            vote_authority_note_new: hex_dec(
                &json_bundle.vote_authority_note_new,
                "vote_authority_note_new",
            )?,
            vote_commitment: hex_dec(&json_bundle.vote_commitment, "vote_commitment")?,
            proposal_id: json_bundle.proposal_id,
            proof: hex_dec(&json_bundle.proof, "proof")?,
            enc_shares: Vec::new(), // wire-only path — not used by build_share_payloads
            anchor_height: json_bundle.anchor_height,
            vote_round_id: json_bundle.vote_round_id,
            shares_hash: hex_dec(&json_bundle.shares_hash, "shares_hash")?,
            share_blinds: json_bundle
                .share_blinds
                .iter()
                .enumerate()
                .map(|(i, h)| hex_dec(h, &format!("share_blinds[{i}]")))
                .collect::<anyhow::Result<_>>()?,
            share_comms: json_bundle
                .share_comms
                .iter()
                .enumerate()
                .map(|(i, h)| hex_dec(h, &format!("share_comms[{i}]")))
                .collect::<anyhow::Result<_>>()?,
            r_vpk_bytes: hex_dec(&json_bundle.r_vpk_bytes, "r_vpk_bytes")?,
            alpha_v: hex_dec(&json_bundle.alpha_v, "alpha_v")?,
        };

        // Note: build_share_payloads is a pure function (no VotingDb needed).
        let payloads: Vec<JsonSharePayload> = voting::vote_commitment::build_share_payloads(
            &enc_shares,
            &commitment,
            vote_decision as u32,
            num_options as u32,
            vc_tree_position as u64,
            single_share_mode == JNI_TRUE,
        )
        .map_err(|e| anyhow!("build_share_payloads: {}", e))?
        .into_iter()
        .map(JsonSharePayload::from)
        .collect();

        json_to_jstring(env, &payloads)
    });
    unwrap_exc_or(&mut env, res, std::ptr::null_mut())
}

// =============================================================================
// K. Cast vote signature
// =============================================================================

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_signCastVote<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    hotkey_seed: JByteArray<'local>,
    network_id: jint,
    round_id: JString<'local>,
    r_vpk: JByteArray<'local>,
    van_nullifier: JByteArray<'local>,
    van_new: JByteArray<'local>,
    vote_commitment: JByteArray<'local>,
    proposal_id: jint,
    anchor_height: jint,
    alpha_v: JByteArray<'local>,
) -> jbyteArray {
    let res = catch_unwind(&mut env, |env| {
        let sig = voting::vote_commitment::sign_cast_vote(
            &env.convert_byte_array(&hotkey_seed)?,
            network_id as u32,
            &java_string_to_rust(env, &round_id)?,
            &env.convert_byte_array(&r_vpk)?,
            &env.convert_byte_array(&van_nullifier)?,
            &env.convert_byte_array(&van_new)?,
            &env.convert_byte_array(&vote_commitment)?,
            proposal_id as u32,
            anchor_height as u32,
            &env.convert_byte_array(&alpha_v)?,
        )
        .map_err(|e| anyhow!("sign_cast_vote: {}", e))?;
        Ok(env.byte_array_from_slice(&sig.vote_auth_sig)?.into_raw())
    });
    unwrap_exc_or(&mut env, res, std::ptr::null_mut())
}

// =============================================================================
// L. Delegation submission
// =============================================================================

/// Reconstruct the chain-ready delegation TX payload from the DB + wallet seed.
///
/// Loads proof artifacts and signs the ZIP-244 sighash with the randomised hotkey spending key.
/// Returns JSON-encoded DelegationSubmissionData.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_getDelegationSubmissionJson<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_handle: jlong,
    round_id: JString<'local>,
    bundle_index: jint,
    sender_seed: JByteArray<'local>,
    network_id: jint,
    account_index: jint,
) -> jstring {
    let res = catch_unwind(&mut env, |env| {
        let handle = handle_from_jlong(db_handle)?;
        let data = handle
            .db
            .get_delegation_submission(
                &java_string_to_rust(env, &round_id)?,
                bundle_index as u32,
                &env.convert_byte_array(&sender_seed)?,
                network_id as u32,
                account_index as u32,
            )
            .map_err(|e| anyhow!("get_delegation_submission: {}", e))?;
        json_to_jstring(env, &JsonDelegationSubmission::from(data))
    });
    unwrap_exc_or(&mut env, res, std::ptr::null_mut())
}

/// Reconstruct the delegation TX payload using a Keystone-provided signature.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_getDelegationSubmissionWithKeystoneSigJson<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_handle: jlong,
    round_id: JString<'local>,
    bundle_index: jint,
    keystone_sig: JByteArray<'local>,
    keystone_sighash: JByteArray<'local>,
) -> jstring {
    let res = catch_unwind(&mut env, |env| {
        let handle = handle_from_jlong(db_handle)?;
        let data = handle
            .db
            .get_delegation_submission_with_keystone_sig(
                &java_string_to_rust(env, &round_id)?,
                bundle_index as u32,
                &env.convert_byte_array(&keystone_sig)?,
                &env.convert_byte_array(&keystone_sighash)?,
            )
            .map_err(|e| anyhow!("get_delegation_submission_with_keystone_sig: {}", e))?;
        json_to_jstring(env, &JsonDelegationSubmission::from(data))
    });
    unwrap_exc_or(&mut env, res, std::ptr::null_mut())
}

// =============================================================================
// M. Recovery state
// =============================================================================

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_storeDelegationTxHash<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_handle: jlong,
    round_id: JString<'local>,
    bundle_index: jint,
    tx_hash: JString<'local>,
) -> jboolean {
    let res = catch_unwind(&mut env, |env| {
        let handle = handle_from_jlong(db_handle)?;
        handle
            .db
            .store_delegation_tx_hash(
                &java_string_to_rust(env, &round_id)?,
                bundle_index as u32,
                &java_string_to_rust(env, &tx_hash)?,
            )
            .map_err(|e| anyhow!("store_delegation_tx_hash: {}", e))?;
        Ok(JNI_TRUE)
    });
    unwrap_exc_or(&mut env, res, JNI_FALSE)
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_getDelegationTxHash<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_handle: jlong,
    round_id: JString<'local>,
    bundle_index: jint,
) -> jstring {
    let res = catch_unwind(&mut env, |env| {
        let handle = handle_from_jlong(db_handle)?;
        let tx_hash = handle
            .db
            .get_delegation_tx_hash(&java_string_to_rust(env, &round_id)?, bundle_index as u32)
            .map_err(|e| anyhow!("get_delegation_tx_hash: {}", e))?;
        match tx_hash {
            Some(value) => Ok(env.new_string(value)?.into_raw()),
            None => Ok(std::ptr::null_mut()),
        }
    });
    unwrap_exc_or(&mut env, res, std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_storeVoteTxHash<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_handle: jlong,
    round_id: JString<'local>,
    bundle_index: jint,
    proposal_id: jint,
    tx_hash: JString<'local>,
) -> jboolean {
    let res = catch_unwind(&mut env, |env| {
        let handle = handle_from_jlong(db_handle)?;
        handle
            .db
            .store_vote_tx_hash(
                &java_string_to_rust(env, &round_id)?,
                bundle_index as u32,
                proposal_id as u32,
                &java_string_to_rust(env, &tx_hash)?,
            )
            .map_err(|e| anyhow!("store_vote_tx_hash: {}", e))?;
        Ok(JNI_TRUE)
    });
    unwrap_exc_or(&mut env, res, JNI_FALSE)
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_markVoteSubmitted<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_handle: jlong,
    round_id: JString<'local>,
    bundle_index: jint,
    proposal_id: jint,
) -> jboolean {
    let res = catch_unwind(&mut env, |env| {
        let handle = handle_from_jlong(db_handle)?;
        handle
            .db
            .mark_vote_submitted(
                &java_string_to_rust(env, &round_id)?,
                bundle_index as u32,
                proposal_id as u32,
            )
            .map_err(|e| anyhow!("mark_vote_submitted: {}", e))?;
        Ok(JNI_TRUE)
    });
    unwrap_exc_or(&mut env, res, JNI_FALSE)
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_getVoteTxHash<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_handle: jlong,
    round_id: JString<'local>,
    bundle_index: jint,
    proposal_id: jint,
) -> jstring {
    let res = catch_unwind(&mut env, |env| {
        let handle = handle_from_jlong(db_handle)?;
        let tx_hash = handle
            .db
            .get_vote_tx_hash(
                &java_string_to_rust(env, &round_id)?,
                bundle_index as u32,
                proposal_id as u32,
            )
            .map_err(|e| anyhow!("get_vote_tx_hash: {}", e))?;
        match tx_hash {
            Some(value) => Ok(env.new_string(value)?.into_raw()),
            None => Ok(std::ptr::null_mut()),
        }
    });
    unwrap_exc_or(&mut env, res, std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_storeCommitmentBundle<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_handle: jlong,
    round_id: JString<'local>,
    bundle_index: jint,
    proposal_id: jint,
    bundle_json: JString<'local>,
    vc_tree_position: jlong,
) -> jboolean {
    let res = catch_unwind(&mut env, |env| {
        let handle = handle_from_jlong(db_handle)?;
        handle
            .db
            .store_commitment_bundle(
                &java_string_to_rust(env, &round_id)?,
                bundle_index as u32,
                proposal_id as u32,
                &java_string_to_rust(env, &bundle_json)?,
                vc_tree_position as u64,
            )
            .map_err(|e| anyhow!("store_commitment_bundle: {}", e))?;
        Ok(JNI_TRUE)
    });
    unwrap_exc_or(&mut env, res, JNI_FALSE)
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_getCommitmentBundleJson<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_handle: jlong,
    round_id: JString<'local>,
    bundle_index: jint,
    proposal_id: jint,
) -> jstring {
    let res = catch_unwind(&mut env, |env| {
        let handle = handle_from_jlong(db_handle)?;
        let record = handle
            .db
            .get_commitment_bundle(
                &java_string_to_rust(env, &round_id)?,
                bundle_index as u32,
                proposal_id as u32,
            )
            .map_err(|e| anyhow!("get_commitment_bundle: {}", e))?;
        match record {
            Some((bundle_json, vc_tree_position)) => json_to_jstring(
                env,
                &JsonCommitmentBundleRecord {
                    bundle_json,
                    vc_tree_position,
                },
            ),
            None => Ok(std::ptr::null_mut()),
        }
    });
    unwrap_exc_or(&mut env, res, std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_clearRecoveryState<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_handle: jlong,
    round_id: JString<'local>,
) -> jboolean {
    let res = catch_unwind(&mut env, |env| {
        let handle = handle_from_jlong(db_handle)?;
        handle
            .db
            .clear_recovery_state(&java_string_to_rust(env, &round_id)?)
            .map_err(|e| anyhow!("clear_recovery_state: {}", e))?;
        Ok(JNI_TRUE)
    });
    unwrap_exc_or(&mut env, res, JNI_FALSE)
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_recordShareDelegation<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_handle: jlong,
    round_id: JString<'local>,
    bundle_index: jint,
    proposal_id: jint,
    share_index: jint,
    sent_to_urls_json: JString<'local>,
    nullifier: JByteArray<'local>,
    submit_at: jlong,
) -> jboolean {
    let res = catch_unwind(&mut env, |env| {
        let handle = handle_from_jlong(db_handle)?;
        let sent_to_urls: Vec<String> =
            json_from_jstring(env, &sent_to_urls_json, "sentToUrlsJson")?;
        handle
            .db
            .record_share_delegation(
                &java_string_to_rust(env, &round_id)?,
                bundle_index as u32,
                proposal_id as u32,
                share_index as u32,
                &sent_to_urls,
                &env.convert_byte_array(&nullifier)?,
                submit_at as u64,
            )
            .map_err(|e| anyhow!("record_share_delegation: {}", e))?;
        Ok(JNI_TRUE)
    });
    unwrap_exc_or(&mut env, res, JNI_FALSE)
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_getShareDelegationsJson<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_handle: jlong,
    round_id: JString<'local>,
) -> jstring {
    let res = catch_unwind(&mut env, |env| {
        let handle = handle_from_jlong(db_handle)?;
        let records: Vec<JsonShareDelegationRecord> = handle
            .db
            .get_share_delegations(&java_string_to_rust(env, &round_id)?)
            .map_err(|e| anyhow!("get_share_delegations: {}", e))?
            .into_iter()
            .map(JsonShareDelegationRecord::from)
            .collect();
        json_to_jstring(env, &records)
    });
    unwrap_exc_or(&mut env, res, std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_markShareConfirmed<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_handle: jlong,
    round_id: JString<'local>,
    bundle_index: jint,
    proposal_id: jint,
    share_index: jint,
) -> jboolean {
    let res = catch_unwind(&mut env, |env| {
        let handle = handle_from_jlong(db_handle)?;
        handle
            .db
            .mark_share_confirmed(
                &java_string_to_rust(env, &round_id)?,
                bundle_index as u32,
                proposal_id as u32,
                share_index as u32,
            )
            .map_err(|e| anyhow!("mark_share_confirmed: {}", e))?;
        Ok(JNI_TRUE)
    });
    unwrap_exc_or(&mut env, res, JNI_FALSE)
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_addSentServers<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_handle: jlong,
    round_id: JString<'local>,
    bundle_index: jint,
    proposal_id: jint,
    share_index: jint,
    new_urls_json: JString<'local>,
) -> jboolean {
    let res = catch_unwind(&mut env, |env| {
        let handle = handle_from_jlong(db_handle)?;
        let new_urls: Vec<String> = json_from_jstring(env, &new_urls_json, "newUrlsJson")?;
        handle
            .db
            .add_sent_servers(
                &java_string_to_rust(env, &round_id)?,
                bundle_index as u32,
                proposal_id as u32,
                share_index as u32,
                &new_urls,
            )
            .map_err(|e| anyhow!("add_sent_servers: {}", e))?;
        Ok(JNI_TRUE)
    });
    unwrap_exc_or(&mut env, res, JNI_FALSE)
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_computeShareNullifier<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    vote_commitment: JByteArray<'local>,
    share_index: jint,
    blind: JByteArray<'local>,
) -> jbyteArray {
    let res = catch_unwind(&mut env, |env| {
        let nullifier = voting::share_tracking::compute_share_nullifier(
            &env.convert_byte_array(&vote_commitment)?,
            share_index as u32,
            &env.convert_byte_array(&blind)?,
        )
        .map_err(|e| anyhow!("compute_share_nullifier: {}", e))?;
        Ok(env.byte_array_from_slice(&nullifier)?.into_raw())
    });
    unwrap_exc_or(&mut env, res, std::ptr::null_mut())
}

// =============================================================================
// N. Stateless utilities
// =============================================================================

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_decomposeWeightJson<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    weight: jlong,
) -> jstring {
    let res = catch_unwind(&mut env, |env| {
        let parts = voting::decompose::decompose_weight(weight as u64);
        json_to_jstring(env, &parts)
    });
    unwrap_exc_or(&mut env, res, std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_extractOrchardFvkFromUfvk<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    ufvk: JString<'local>,
    network_id: jint,
) -> jbyteArray {
    let res = catch_unwind(&mut env, |env| {
        let network = network_from_id(network_id)?;
        let bytes = orchard_fvk_bytes(&java_string_to_rust(env, &ufvk)?, network)?;
        Ok(env.byte_array_from_slice(&bytes)?.into_raw())
    });
    unwrap_exc_or(&mut env, res, std::ptr::null_mut())
}

// =============================================================================
// JNI object constructors (for Kotlin data classes with typed fields)
// =============================================================================

fn make_ffi_round_state<'local>(
    env: &mut JNIEnv<'local>,
    state: RoundState,
) -> anyhow::Result<jobject> {
    let phase = round_phase_to_u32(state.phase) as i32;
    let class =
        env.find_class("cash/z/ecc/android/sdk/internal/model/voting/FfiRoundState")?;
    let round_id_obj: JObject<'local> = env.new_string(&state.round_id)?.into();
    let hotkey_obj: JObject<'local> = match &state.hotkey_address {
        Some(a) => env.new_string(a)?.into(),
        None => JObject::null(),
    };
    let long_class = env.find_class("java/lang/Long")?;
    let weight_obj: JObject<'local> = match state.delegated_weight {
        Some(w) => env.new_object(&long_class, "(J)V", &[JValue::Long(w as i64)])?,
        None => JObject::null(),
    };
    let obj = env.new_object(
        &class,
        "(Ljava/lang/String;IJLjava/lang/String;Ljava/lang/Long;Z)V",
        &[
            JValue::Object(&round_id_obj),
            JValue::Int(phase),
            JValue::Long(state.snapshot_height as i64),
            JValue::Object(&hotkey_obj),
            JValue::Object(&weight_obj),
            JValue::Bool(state.proof_generated as jboolean),
        ],
    )?;
    Ok(obj.into_raw())
}

fn make_ffi_voting_hotkey<'local>(
    env: &mut JNIEnv<'local>,
    hotkey: voting::types::VotingHotkey,
) -> anyhow::Result<jobject> {
    let class =
        env.find_class("cash/z/ecc/android/sdk/internal/model/voting/FfiVotingHotkey")?;
    let sk_obj: JObject<'local> = env.byte_array_from_slice(&hotkey.secret_key)?.into();
    let pk_obj: JObject<'local> = env.byte_array_from_slice(&hotkey.public_key)?.into();
    let addr_obj: JObject<'local> = env.new_string(&hotkey.address)?.into();
    let obj = env.new_object(
        &class,
        "([B[BLjava/lang/String;)V",
        &[
            JValue::Object(&sk_obj),
            JValue::Object(&pk_obj),
            JValue::Object(&addr_obj),
        ],
    )?;
    Ok(obj.into_raw())
}

fn make_ffi_bundle_setup_result<'local>(
    env: &mut JNIEnv<'local>,
    count: u32,
    weight: u64,
) -> anyhow::Result<jobject> {
    let class =
        env.find_class("cash/z/ecc/android/sdk/internal/model/voting/FfiBundleSetupResult")?;
    let obj = env.new_object(
        &class,
        "(IJ)V",
        &[JValue::Int(count as i32), JValue::Long(weight as i64)],
    )?;
    Ok(obj.into_raw())
}

/// Convert a ReceivedNote<_, orchard::note::Note> to a JsonNoteInfo.
fn received_note_to_note_info(
    note: &zcash_client_backend::wallet::ReceivedNote<
        zcash_client_sqlite::ReceivedNoteId,
        orchard::note::Note,
    >,
    ufvk: &UnifiedFullViewingKey,
    network: &zcash_protocol::consensus::Network,
) -> anyhow::Result<JsonNoteInfo> {
    use orchard::keys::Scope;

    let orchard_note = note.note();
    let fvk = ufvk
        .orchard()
        .ok_or_else(|| anyhow!("UFVK has no Orchard component"))?;

    let nullifier = orchard_note.nullifier(fvk);
    let cmx: orchard::note::ExtractedNoteCommitment = orchard_note.commitment().into();

    let diversifier = orchard_note.recipient().diversifier().as_array().to_vec();
    let value = orchard_note.value().inner();
    let rho = orchard_note.rho().to_bytes().to_vec();
    let rseed = orchard_note.rseed().as_bytes().to_vec();
    let position = u64::from(note.note_commitment_tree_position());
    let scope = match note.spending_key_scope() {
        Scope::External => 0u32,
        Scope::Internal => 1u32,
    };
    let ufvk_str = ufvk.encode(network);

    Ok(JsonNoteInfo {
        commitment: hex_enc(&cmx.to_bytes()),
        nullifier: hex_enc(&nullifier.to_bytes()),
        value,
        position,
        diversifier: hex_enc(&diversifier),
        rho: hex_enc(&rho),
        rseed: hex_enc(&rseed),
        scope,
        ufvk_str,
    })
}

/// Caches the lightwalletd TreeState protobuf for the snapshot height.
/// Must be called before [generateNoteWitnessesJson].
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_storeTreeState<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_handle: jlong,
    round_id: JString<'local>,
    tree_state_bytes: JByteArray<'local>,
) -> jboolean {
    let res = catch_unwind(&mut env, |env| {
        let handle = handle_from_jlong(db_handle)?;
        let bytes = env.convert_byte_array(&tree_state_bytes)?;
        handle
            .db
            .store_tree_state(&java_string_to_rust(env, &round_id)?, &bytes)
            .map_err(|e| anyhow!("store_tree_state: {}", e))?;
        Ok(JNI_TRUE)
    });
    unwrap_exc_or(&mut env, res, JNI_FALSE)
}

/// Returns JSON array of NoteInfo for unspent Orchard notes at [snapshotHeight].
///
/// Opens the wallet SQLite at [walletDbPath] read-only and calls
/// `get_unspent_orchard_notes_at_historical_height`. The [accountUuidBytes]
/// parameter must be exactly 16 bytes (UUID).
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_getWalletNotesJson<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    wallet_db_path: JString<'local>,
    snapshot_height: jlong,
    network_id: jint,
    account_uuid_bytes: JByteArray<'local>,
) -> jstring {
    let res = catch_unwind(&mut env, |env| {
        use zcash_client_backend::data_api::{Account, WalletRead};
        use zcash_protocol::consensus::BlockHeight;

        let path = java_string_to_rust(env, &wallet_db_path)?;
        let network = network_from_id(network_id)?;
        let height = BlockHeight::from_u32(snapshot_height as u32);

        let uuid_bytes = env.convert_byte_array(&account_uuid_bytes)?;
        let uuid_arr: [u8; 16] = uuid_bytes
            .try_into()
            .map_err(|_| anyhow!("accountUuidBytes must be 16 bytes"))?;
        let account_uuid =
            zcash_client_sqlite::AccountUuid::from_uuid(uuid::Uuid::from_bytes(uuid_arr));

        let wallet_db = zcash_client_sqlite::WalletDb::for_path(
            &path,
            network,
            zcash_client_sqlite::util::SystemClock,
            rand::rngs::OsRng,
        )
        .map_err(|e| anyhow!("failed to open wallet DB: {}", e))?;

        let account = wallet_db
            .get_account(account_uuid)
            .map_err(|e| anyhow!("get_account: {}", e))?
            .ok_or_else(|| anyhow!("account not found in wallet DB"))?;
        let ufvk = account
            .ufvk()
            .ok_or_else(|| anyhow!("account has no UFVK"))?
            .clone();

        let notes = wallet_db
            .get_unspent_orchard_notes_at_historical_height(account_uuid, height)
            .map_err(|e| anyhow!("get_unspent_orchard_notes_at_historical_height: {}", e))?;

        let json_notes: Vec<JsonNoteInfo> = notes
            .iter()
            .map(|rn| received_note_to_note_info(rn, &ufvk, &network))
            .collect::<anyhow::Result<_>>()?;

        json_to_jstring(env, &json_notes)
    });
    unwrap_exc_or(&mut env, res, std::ptr::null_mut())
}

/// Generates Orchard Merkle witnesses for notes in a bundle and caches them in the voting DB.
///
/// Requires [storeTreeState] to have been called first (loads the frontier from the
/// cached tree state). Also calls [storeWitnesses] internally.
///
/// [notesJson] is the JSON array of NoteInfo from [getWalletNotesJson].
/// The persisted bundle note positions are used to select only the notes that
/// belong to [bundleIndex], so callers may safely pass the full wallet note set.
/// Returns JSON array of WitnessData, or null on error.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_generateNoteWitnessesJson<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_handle: jlong,
    round_id: JString<'local>,
    bundle_index: jint,
    wallet_db_path: JString<'local>,
    notes_json: JString<'local>,
) -> jstring {
    let res = catch_unwind(&mut env, |env| {
        use incrementalmerkletree::Position;
        use orchard::tree::MerkleHashOrchard;
        use prost::Message;
        use zcash_client_backend::proto::service::TreeState;
        use zcash_protocol::consensus::BlockHeight;

        let handle = handle_from_jlong(db_handle)?;
        let round_id_str = java_string_to_rust(env, &round_id)?;
        let wallet_path = java_string_to_rust(env, &wallet_db_path)?;

        let json_notes: Vec<JsonNoteInfo> =
            json_from_jstring(env, &notes_json, "notesJson")?;
        let core_notes: Vec<NoteInfo> = json_notes
            .into_iter()
            .map(NoteInfo::try_from)
            .collect::<anyhow::Result<_>>()?;

        // Load tree state and params from voting DB
        let conn = handle.db.conn();
        let wallet_id = handle.db.wallet_id();
        let bundle_index_u32 = bundle_index as u32;
        let bundle_notes = select_bundle_notes(
            &conn,
            &round_id_str,
            &wallet_id,
            bundle_index_u32,
            &core_notes,
        )?;
        let tree_state_bytes =
            voting::storage::queries::load_tree_state(&conn, &round_id_str, &wallet_id)
                .map_err(|e| anyhow!("load_tree_state: {}", e))?;
        let params =
            voting::storage::queries::load_round_params(&conn, &round_id_str, &wallet_id)
                .map_err(|e| anyhow!("load_round_params: {}", e))?;
        drop(conn);

        // Parse frontier from cached TreeState
        let tree_state = TreeState::decode(tree_state_bytes.as_slice())
            .map_err(|e| anyhow!("decode TreeState: {}", e))?;
        let orchard_ct = tree_state
            .orchard_tree()
            .map_err(|e| anyhow!("parse orchard_tree: {}", e))?;
        let frontier_root = orchard_ct.root();
        let nonempty_frontier = orchard_ct
            .to_frontier()
            .take()
            .ok_or_else(|| anyhow!("empty orchard frontier — no Orchard activity at snapshot"))?;

        let height =
            BlockHeight::from_u32(params.snapshot_height as u32);

        // Open wallet DB and generate witnesses
        let wallet_db = zcash_client_sqlite::WalletDb::for_path(
            &wallet_path,
            zcash_protocol::consensus::Network::MainNetwork, // network not used for tree ops
            zcash_client_sqlite::util::SystemClock,
            rand::rngs::OsRng,
        )
        .map_err(|e| anyhow!("open wallet DB: {}", e))?;

        let positions: Vec<Position> = bundle_notes
            .iter()
            .map(|n| Position::from(n.position))
            .collect();

        let merkle_paths = wallet_db
            .generate_orchard_witnesses_at_historical_height(
                &positions,
                nonempty_frontier,
                height,
            )
            .map_err(|e| anyhow!("generate_orchard_witnesses_at_historical_height: {}", e))?;

        let root_bytes = frontier_root.to_bytes().to_vec();
        let witnesses: Vec<WitnessData> = merkle_paths
            .into_iter()
            .zip(bundle_notes.iter())
            .map(|(path, note)| {
                let auth_path: Vec<Vec<u8>> = path
                    .path_elems()
                    .iter()
                    .map(|h: &MerkleHashOrchard| h.to_bytes().to_vec())
                    .collect();
                WitnessData {
                    note_commitment: note.commitment.clone(),
                    position: note.position,
                    root: root_bytes.clone(),
                    auth_path,
                }
            })
            .collect();

        // Overwrite any previously cached witness set so retries recover from
        // stale bundle selections created by older client builds.
        let conn = handle.db.conn();
        replace_bundle_witnesses(
            &conn,
            &round_id_str,
            &wallet_id,
            bundle_index_u32,
            &witnesses,
        )?;

        let json_witnesses: Vec<JsonWitnessData> =
            witnesses.into_iter().map(JsonWitnessData::from).collect();
        json_to_jstring(env, &json_witnesses)
    });
    unwrap_exc_or(&mut env, res, std::ptr::null_mut())
}
