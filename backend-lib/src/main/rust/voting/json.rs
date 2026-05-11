use super::helpers::*;
use super::*;
use serde::{Deserialize, Serialize};

const NOTE_SCOPE_EXTERNAL: u32 = 0;
const NOTE_SCOPE_INTERNAL: u32 = 1;
const ORCHARD_DIVERSIFIER_BYTES: usize = 11;
const ORCHARD_WITNESS_PATH_DEPTH: usize = 32;

pub(super) fn hex_enc(bytes: &[u8]) -> String {
    hex::encode(bytes)
}

pub(super) fn hex_dec(value: &str, field: &str) -> anyhow::Result<Vec<u8>> {
    hex::decode(value).map_err(|e| anyhow!("field '{field}': invalid hex: {e}"))
}

#[derive(Deserialize)]
pub(super) struct JsonNoteInfo {
    pub(super) commitment: String,
    pub(super) nullifier: String,
    pub(super) value: u64,
    pub(super) position: u64,
    pub(super) diversifier: String,
    pub(super) rho: String,
    pub(super) rseed: String,
    pub(super) scope: u32,
    pub(super) ufvk_str: String,
}

impl TryFrom<JsonNoteInfo> for NoteInfo {
    type Error = anyhow::Error;

    fn try_from(note: JsonNoteInfo) -> anyhow::Result<Self> {
        let scope = require_note_scope(note.scope)?;

        Ok(NoteInfo {
            commitment: require_len(
                hex_dec(&note.commitment, "commitment")?,
                "commitment",
                PROTOCOL_FIELD_BYTES,
            )?,
            nullifier: require_len(
                hex_dec(&note.nullifier, "nullifier")?,
                "nullifier",
                PROTOCOL_FIELD_BYTES,
            )?,
            value: note.value,
            position: note.position,
            diversifier: require_len(
                hex_dec(&note.diversifier, "diversifier")?,
                "diversifier",
                ORCHARD_DIVERSIFIER_BYTES,
            )?,
            rho: require_len(hex_dec(&note.rho, "rho")?, "rho", PROTOCOL_FIELD_BYTES)?,
            rseed: require_len(
                hex_dec(&note.rseed, "rseed")?,
                "rseed",
                PROTOCOL_FIELD_BYTES,
            )?,
            scope,
            ufvk_str: note.ufvk_str,
        })
    }
}

fn require_note_scope(scope: u32) -> anyhow::Result<u32> {
    match scope {
        NOTE_SCOPE_EXTERNAL | NOTE_SCOPE_INTERNAL => Ok(scope),
        _ => Err(anyhow!(
            "scope must be {NOTE_SCOPE_EXTERNAL} (external) or {NOTE_SCOPE_INTERNAL} (internal), got {scope}"
        )),
    }
}

#[derive(Serialize, Deserialize)]
pub(super) struct JsonWitnessData {
    pub(super) note_commitment: String,
    pub(super) position: u64,
    pub(super) root: String,
    pub(super) auth_path: Vec<String>,
}

impl TryFrom<JsonWitnessData> for WitnessData {
    type Error = anyhow::Error;

    fn try_from(witness: JsonWitnessData) -> anyhow::Result<Self> {
        if witness.auth_path.len() != ORCHARD_WITNESS_PATH_DEPTH {
            return Err(anyhow!(
                "auth_path must contain {ORCHARD_WITNESS_PATH_DEPTH} entries, got {}",
                witness.auth_path.len()
            ));
        }

        Ok(WitnessData {
            note_commitment: require_len(
                hex_dec(&witness.note_commitment, "note_commitment")?,
                "note_commitment",
                PROTOCOL_FIELD_BYTES,
            )?,
            position: witness.position,
            root: require_len(
                hex_dec(&witness.root, "root")?,
                "root",
                PROTOCOL_FIELD_BYTES,
            )?,
            auth_path: witness
                .auth_path
                .iter()
                .enumerate()
                .map(|(index, path)| {
                    require_len(
                        hex_dec(path, &format!("auth_path[{index}]"))?,
                        &format!("auth_path[{index}]"),
                        PROTOCOL_FIELD_BYTES,
                    )
                })
                .collect::<anyhow::Result<_>>()?,
        })
    }
}

#[derive(Serialize)]
pub(super) struct JsonGovernancePczt {
    pub(super) pczt_bytes: String,
    pub(super) rk: String,
    pub(super) action_index: u32,
    pub(super) pczt_sighash: String,
}

impl TryFrom<GovernancePczt> for JsonGovernancePczt {
    type Error = anyhow::Error;

    fn try_from(pczt: GovernancePczt) -> anyhow::Result<Self> {
        Ok(JsonGovernancePczt {
            pczt_bytes: hex_enc(&pczt.pczt_bytes),
            rk: hex_enc(&pczt.rk),
            action_index: u32::try_from(pczt.action_index)
                .map_err(|_| anyhow!("action_index is too large for u32: {}", pczt.action_index))?,
            pczt_sighash: hex_enc(&pczt.pczt_sighash),
        })
    }
}

#[derive(Serialize)]
pub(super) struct JsonDelegationPirPrecomputeResult {
    pub(super) cached_count: u32,
    pub(super) fetched_count: u32,
}

impl From<DelegationPirPrecomputeResult> for JsonDelegationPirPrecomputeResult {
    fn from(result: DelegationPirPrecomputeResult) -> Self {
        JsonDelegationPirPrecomputeResult {
            cached_count: result.cached_count,
            fetched_count: result.fetched_count,
        }
    }
}

#[derive(Serialize)]
pub(super) struct JsonDelegationProofResult {
    pub(super) proof: String,
    pub(super) public_inputs: Vec<String>,
    pub(super) nf_signed: String,
    pub(super) cmx_new: String,
    pub(super) gov_nullifiers: Vec<String>,
    pub(super) van_comm: String,
    pub(super) rk: String,
}

impl From<DelegationProofResult> for JsonDelegationProofResult {
    fn from(result: DelegationProofResult) -> Self {
        JsonDelegationProofResult {
            proof: hex_enc(&result.proof),
            public_inputs: result
                .public_inputs
                .iter()
                .map(|input| hex_enc(input))
                .collect(),
            nf_signed: hex_enc(&result.nf_signed),
            cmx_new: hex_enc(&result.cmx_new),
            gov_nullifiers: result
                .gov_nullifiers
                .iter()
                .map(|nullifier| hex_enc(nullifier))
                .collect(),
            van_comm: hex_enc(&result.van_comm),
            rk: hex_enc(&result.rk),
        }
    }
}

pub(super) fn json_to_jstring<T: Serialize>(
    env: &mut JNIEnv<'_>,
    value: &T,
) -> anyhow::Result<jstring> {
    let s = serde_json::to_string(value).map_err(|e| anyhow!("JSON serialization error: {}", e))?;
    Ok(env.new_string(s)?.into_raw())
}

pub(super) fn json_from_jstring<T: for<'de> Deserialize<'de>>(
    env: &mut JNIEnv<'_>,
    value: &JString<'_>,
    field: &str,
) -> anyhow::Result<T> {
    let s = java_string_to_rust(env, value)?;
    serde_json::from_str(&s).map_err(|e| {
        anyhow!(
            "{field}: JSON parse error at line {}, column {}",
            e.line(),
            e.column()
        )
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn json_note_info_rejects_unknown_scope() {
        let note = JsonNoteInfo {
            commitment: hex::encode([1u8; PROTOCOL_FIELD_BYTES]),
            nullifier: hex::encode([2u8; PROTOCOL_FIELD_BYTES]),
            value: 13_000_000,
            position: 0,
            diversifier: hex::encode([0u8; ORCHARD_DIVERSIFIER_BYTES]),
            rho: hex::encode([0u8; PROTOCOL_FIELD_BYTES]),
            rseed: hex::encode([0u8; PROTOCOL_FIELD_BYTES]),
            scope: 2,
            ufvk_str: String::new(),
        };

        assert!(NoteInfo::try_from(note).is_err());
    }

    #[test]
    fn json_witness_data_round_trips_expected_lengths() {
        let witness = witness_json(ORCHARD_WITNESS_PATH_DEPTH);

        let parsed = WitnessData::try_from(witness).expect("valid witness JSON");

        assert_eq!(vec![1; PROTOCOL_FIELD_BYTES], parsed.note_commitment);
        assert_eq!(42, parsed.position);
        assert_eq!(vec![2; PROTOCOL_FIELD_BYTES], parsed.root);
        assert_eq!(ORCHARD_WITNESS_PATH_DEPTH, parsed.auth_path.len());
        assert_eq!(
            vec![3 + (ORCHARD_WITNESS_PATH_DEPTH - 1) as u8; PROTOCOL_FIELD_BYTES],
            parsed.auth_path[ORCHARD_WITNESS_PATH_DEPTH - 1]
        );
    }

    #[test]
    fn json_witness_data_rejects_short_auth_path() {
        let err = WitnessData::try_from(witness_json(ORCHARD_WITNESS_PATH_DEPTH - 1))
            .expect_err("short auth_path must fail");

        assert!(err.to_string().contains("auth_path must contain"));
    }

    #[test]
    fn json_witness_data_rejects_short_auth_path_element() {
        let mut witness = witness_json(ORCHARD_WITNESS_PATH_DEPTH);
        witness.auth_path[0] = hex_bytes(9, PROTOCOL_FIELD_BYTES - 1);

        let err = WitnessData::try_from(witness).expect_err("short auth_path element must fail");

        assert!(err.to_string().contains("auth_path[0]"));
    }

    #[test]
    fn json_delegation_pir_precompute_result_serializes_counts() {
        let result = DelegationPirPrecomputeResult {
            cached_count: 2,
            fetched_count: 3,
        };

        let value = serde_json::to_value(JsonDelegationPirPrecomputeResult::from(result))
            .expect("serializes");

        assert_eq!(json!(2), value["cached_count"]);
        assert_eq!(json!(3), value["fetched_count"]);
    }

    #[test]
    fn json_delegation_proof_result_serializes_exact_hex_fields() {
        let result = DelegationProofResult {
            proof: bytes(1, 3),
            public_inputs: vec![
                bytes(2, PROTOCOL_FIELD_BYTES),
                bytes(3, PROTOCOL_FIELD_BYTES),
            ],
            nf_signed: bytes(4, PROTOCOL_FIELD_BYTES),
            cmx_new: bytes(5, PROTOCOL_FIELD_BYTES),
            gov_nullifiers: vec![
                bytes(6, PROTOCOL_FIELD_BYTES),
                bytes(7, PROTOCOL_FIELD_BYTES),
            ],
            van_comm: bytes(8, PROTOCOL_FIELD_BYTES),
            rk: bytes(9, PROTOCOL_FIELD_BYTES),
        };

        let value =
            serde_json::to_value(JsonDelegationProofResult::from(result)).expect("serializes");

        assert_eq!(json!(hex_bytes(1, 3)), value["proof"]);
        assert_eq!(
            json!(hex_bytes(2, PROTOCOL_FIELD_BYTES)),
            value["public_inputs"][0]
        );
        assert_eq!(
            json!(hex_bytes(3, PROTOCOL_FIELD_BYTES)),
            value["public_inputs"][1]
        );
        assert_eq!(
            json!(hex_bytes(4, PROTOCOL_FIELD_BYTES)),
            value["nf_signed"]
        );
        assert_eq!(json!(hex_bytes(5, PROTOCOL_FIELD_BYTES)), value["cmx_new"]);
        assert_eq!(
            json!(hex_bytes(6, PROTOCOL_FIELD_BYTES)),
            value["gov_nullifiers"][0]
        );
        assert_eq!(
            json!(hex_bytes(7, PROTOCOL_FIELD_BYTES)),
            value["gov_nullifiers"][1]
        );
        assert_eq!(json!(hex_bytes(8, PROTOCOL_FIELD_BYTES)), value["van_comm"]);
        assert_eq!(json!(hex_bytes(9, PROTOCOL_FIELD_BYTES)), value["rk"]);
    }

    fn witness_json(auth_path_len: usize) -> JsonWitnessData {
        JsonWitnessData {
            note_commitment: hex_bytes(1, PROTOCOL_FIELD_BYTES),
            position: 42,
            root: hex_bytes(2, PROTOCOL_FIELD_BYTES),
            auth_path: (0..auth_path_len)
                .map(|index| hex_bytes(3 + index as u8, PROTOCOL_FIELD_BYTES))
                .collect(),
        }
    }

    fn bytes(byte: u8, len: usize) -> Vec<u8> {
        vec![byte; len]
    }

    fn hex_bytes(byte: u8, len: usize) -> String {
        hex::encode(bytes(byte, len))
    }
}
