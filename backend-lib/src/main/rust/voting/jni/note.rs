//! `NoteInfo` marshalling: the eligible-note records that cross between the
//! wallet database and the Kotlin voting layer.

use super::super::helpers::*;
use super::super::*;

pub(crate) fn java_note_info(env: &mut JNIEnv<'_>, note: &JObject<'_>) -> anyhow::Result<NoteInfo> {
    let scope = require_note_scope(jint_to_u32(
        env.get_field(note, "scope", "I")?.i()?,
        "scope",
    )?)?;

    Ok(NoteInfo {
        commitment: require_len(
            java_byte_array_field(env, note, "commitment")?,
            "commitment",
            PROTOCOL_FIELD_BYTES,
        )?,
        nullifier: require_len(
            java_byte_array_field(env, note, "nullifier")?,
            "nullifier",
            PROTOCOL_FIELD_BYTES,
        )?,
        value: jlong_to_u64(env.get_field(note, "value", "J")?.j()?, "value")?,
        position: jlong_to_u64(env.get_field(note, "position", "J")?.j()?, "position")?,
        diversifier: require_len(
            java_byte_array_field(env, note, "diversifier")?,
            "diversifier",
            ORCHARD_DIVERSIFIER_BYTES,
        )?,
        rho: require_len(
            java_byte_array_field(env, note, "rho")?,
            "rho",
            PROTOCOL_FIELD_BYTES,
        )?,
        rseed: require_len(
            java_byte_array_field(env, note, "rseed")?,
            "rseed",
            PROTOCOL_FIELD_BYTES,
        )?,
        scope,
        ufvk_str: java_string_field(env, note, "ufvk")?,
    })
}

pub(crate) fn java_note_info_array(
    env: &mut JNIEnv<'_>,
    notes: &JObjectArray<'_>,
    field: &str,
) -> anyhow::Result<Vec<NoteInfo>> {
    let count = env.get_array_length(notes)?;
    (0..count)
        .map(|index| {
            let note = env.get_object_array_element(notes, index)?;
            java_note_info(env, &note).map_err(|e| anyhow!("{field}[{index}]: {e}"))
        })
        .collect()
}

pub(crate) fn make_jni_note_info<'local>(
    env: &mut JNIEnv<'local>,
    note: NoteInfo,
) -> anyhow::Result<JObject<'local>> {
    env.with_local_frame_returning_local(16, |env| {
        let class = env.find_class(JNI_NOTE_INFO)?;
        let commitment =
            make_jni_fixed_bytes(env, note.commitment, "commitment", PROTOCOL_FIELD_BYTES)?;
        let nullifier =
            make_jni_fixed_bytes(env, note.nullifier, "nullifier", PROTOCOL_FIELD_BYTES)?;
        let diversifier = make_jni_fixed_bytes(
            env,
            note.diversifier,
            "diversifier",
            ORCHARD_DIVERSIFIER_BYTES,
        )?;
        let rho = make_jni_fixed_bytes(env, note.rho, "rho", PROTOCOL_FIELD_BYTES)?;
        let rseed = make_jni_fixed_bytes(env, note.rseed, "rseed", PROTOCOL_FIELD_BYTES)?;
        let ufvk: JObject<'_> = env.new_string(note.ufvk_str)?.into();

        Ok(env.new_object(
            &class,
            JNI_NOTE_INFO_CTOR_SIG,
            &[
                JValue::Object(&commitment),
                JValue::Object(&nullifier),
                JValue::Long(u64_to_jlong(note.value, "value")?),
                JValue::Long(u64_to_jlong(note.position, "position")?),
                JValue::Object(&diversifier),
                JValue::Object(&rho),
                JValue::Object(&rseed),
                JValue::Int(u32_to_jint(note.scope, "scope")?),
                JValue::Object(&ufvk),
            ],
        )?)
    })
}

pub(crate) fn make_jni_note_info_array<'local>(
    env: &mut JNIEnv<'local>,
    notes: Vec<NoteInfo>,
) -> anyhow::Result<jobjectArray> {
    let len = usize_to_jint(notes.len(), "notes length")?;
    let class = env.find_class(JNI_NOTE_INFO)?;
    let mut notes = notes.into_iter().enumerate();
    if let Some((_, first)) = notes.next() {
        let first = make_jni_note_info(env, first)?;
        let array = env.new_object_array(len, &class, &first)?;
        env.delete_local_ref(first)?;
        for (index, note) in notes {
            let note = make_jni_note_info(env, note)?;
            env.set_object_array_element(&array, usize_to_jint(index, "notes index")?, &note)?;
            env.delete_local_ref(note)?;
        }
        Ok(array.into_raw())
    } else {
        Ok(env.new_object_array(0, &class, JObject::null())?.into_raw())
    }
}
