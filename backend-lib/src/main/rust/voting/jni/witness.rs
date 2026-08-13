//! Merkle witness marshalling: note-commitment witnesses and the VAN witness
//! that ZKP #1 consumes.

use super::super::helpers::*;
use super::super::*;

pub(crate) fn java_witness_data(
    env: &mut JNIEnv<'_>,
    witness: &JObject<'_>,
) -> anyhow::Result<WitnessData> {
    let auth_path = java_byte_array_list_field(env, witness, "authPath")?;
    if auth_path.len() != ORCHARD_WITNESS_PATH_DEPTH {
        return Err(anyhow!(
            "authPath must contain {ORCHARD_WITNESS_PATH_DEPTH} entries, got {}",
            auth_path.len()
        ));
    }

    Ok(WitnessData {
        note_commitment: require_len(
            java_byte_array_field(env, witness, "noteCommitment")?,
            "noteCommitment",
            PROTOCOL_FIELD_BYTES,
        )?,
        position: jlong_to_u64(env.get_field(witness, "position", "J")?.j()?, "position")?,
        root: require_len(
            java_byte_array_field(env, witness, "root")?,
            "root",
            PROTOCOL_FIELD_BYTES,
        )?,
        auth_path: require_each_len(auth_path, "authPath", PROTOCOL_FIELD_BYTES)?,
    })
}

pub(crate) fn java_witness_data_array(
    env: &mut JNIEnv<'_>,
    witnesses: &JObjectArray<'_>,
    field: &str,
) -> anyhow::Result<Vec<WitnessData>> {
    let count = env.get_array_length(witnesses)?;
    (0..count)
        .map(|index| {
            let witness = env.get_object_array_element(witnesses, index)?;
            java_witness_data(env, &witness).map_err(|e| anyhow!("{field}[{index}]: {e}"))
        })
        .collect()
}

pub(crate) fn java_van_witness(
    env: &mut JNIEnv<'_>,
    witness: &JObject<'_>,
) -> anyhow::Result<voting::vote::VanWitness> {
    let auth_path = java_byte_array_list_field(env, witness, "authPath")?;
    let position = jlong_to_u32(env.get_field(witness, "position", "J")?.j()?, "position")?;
    let anchor_height = jlong_to_u32(
        env.get_field(witness, "anchorHeight", "J")?.j()?,
        "anchorHeight",
    )?;

    voting::vote::VanWitness::from_wire(&auth_path, position, anchor_height)
        .map_err(|e| anyhow!("VanWitness::from_wire: {}", e))
}

pub(crate) fn make_jni_witness_data<'local>(
    env: &mut JNIEnv<'local>,
    witness: WitnessData,
) -> anyhow::Result<JObject<'local>> {
    env.with_local_frame_returning_local(48, |env| {
        let class = env.find_class(JNI_WITNESS_DATA)?;
        let note_commitment = make_jni_fixed_bytes(
            env,
            witness.note_commitment,
            "note_commitment",
            PROTOCOL_FIELD_BYTES,
        )?;
        let root = make_jni_fixed_bytes(env, witness.root, "root", PROTOCOL_FIELD_BYTES)?;
        let auth_path = make_jni_fixed_byte_array_vec(
            env,
            witness.auth_path,
            "auth_path",
            ORCHARD_WITNESS_PATH_DEPTH,
            PROTOCOL_FIELD_BYTES,
        )?;
        let auth_path = JObject::from(auth_path);

        Ok(env.new_object(
            &class,
            JNI_WITNESS_DATA_CTOR_SIG,
            &[
                JValue::Object(&note_commitment),
                JValue::Long(u64_to_jlong(witness.position, "position")?),
                JValue::Object(&root),
                JValue::Object(&auth_path),
            ],
        )?)
    })
}

pub(crate) fn make_jni_witness_data_array<'local>(
    env: &mut JNIEnv<'local>,
    witnesses: Vec<WitnessData>,
) -> anyhow::Result<jobjectArray> {
    let len = usize_to_jint(witnesses.len(), "witnesses length")?;
    let class = env.find_class(JNI_WITNESS_DATA)?;
    let mut witnesses = witnesses.into_iter().enumerate();
    if let Some((_, first)) = witnesses.next() {
        let first = make_jni_witness_data(env, first)?;
        let array = env.new_object_array(len, &class, &first)?;
        env.delete_local_ref(first)?;
        for (index, witness) in witnesses {
            let witness = make_jni_witness_data(env, witness)?;
            env.set_object_array_element(
                &array,
                usize_to_jint(index, "witnesses index")?,
                &witness,
            )?;
            env.delete_local_ref(witness)?;
        }
        Ok(array.into_raw())
    } else {
        Ok(env.new_object_array(0, &class, JObject::null())?.into_raw())
    }
}

pub(crate) fn make_jni_van_witness<'local>(
    env: &mut JNIEnv<'local>,
    witness: voting::vote::VanWitness,
) -> anyhow::Result<jobject> {
    let class = env.find_class(JNI_VAN_WITNESS)?;
    let auth_path = witness
        .auth_path
        .into_iter()
        .map(|bytes| bytes.to_vec())
        .collect();
    let auth_path = make_jni_fixed_byte_array_vec(
        env,
        auth_path,
        "auth_path",
        VAN_WITNESS_PATH_DEPTH,
        PROTOCOL_FIELD_BYTES,
    )?;
    let auth_path = JObject::from(auth_path);

    Ok(env
        .new_object(
            &class,
            JNI_VAN_WITNESS_CTOR_SIG,
            &[
                JValue::Object(&auth_path),
                JValue::Long(u64_to_jlong(u64::from(witness.position), "position")?),
                JValue::Long(u64_to_jlong(
                    u64::from(witness.anchor_height),
                    "anchor_height",
                )?),
            ],
        )?
        .into_raw())
}
