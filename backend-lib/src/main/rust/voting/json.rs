use super::helpers::*;
use super::*;
use serde::{Deserialize, Serialize};

const NOTE_SCOPE_EXTERNAL: u32 = 0;
const NOTE_SCOPE_INTERNAL: u32 = 1;
const ORCHARD_DIVERSIFIER_BYTES: usize = 11;

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
}
