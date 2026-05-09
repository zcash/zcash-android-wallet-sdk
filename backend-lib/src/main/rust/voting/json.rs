use super::helpers::*;
use super::*;
use serde::Deserialize;

const NOTE_SCOPE_EXTERNAL: u32 = 0;
const NOTE_SCOPE_INTERNAL: u32 = 1;

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
            diversifier: hex_dec(&note.diversifier, "diversifier")?,
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

pub(super) fn json_from_jstring<T: for<'de> Deserialize<'de>>(
    env: &mut JNIEnv<'_>,
    value: &JString<'_>,
    field: &str,
) -> anyhow::Result<T> {
    let s = java_string_to_rust(env, value)?;
    serde_json::from_str(&s).map_err(|e| anyhow!("{field}: JSON parse error: {e}"))
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
            diversifier: hex::encode([0u8; 11]),
            rho: hex::encode([0u8; PROTOCOL_FIELD_BYTES]),
            rseed: hex::encode([0u8; PROTOCOL_FIELD_BYTES]),
            scope: 2,
            ufvk_str: String::new(),
        };

        assert!(NoteInfo::try_from(note).is_err());
    }
}
