//! Helper-share marshalling: the encrypted shares, their payloads, and the
//! per-helper delegation records that track submission.

use super::super::helpers::*;
use super::super::*;

pub(crate) fn java_wire_encrypted_share(
    env: &mut JNIEnv<'_>,
    share: &JObject<'_>,
) -> anyhow::Result<WireEncryptedShare> {
    let share_index = require_share_index(
        jint_to_u32(env.get_field(share, "shareIndex", "I")?.i()?, "shareIndex")?,
        "shareIndex",
    )?;

    Ok(WireEncryptedShare {
        c1: require_len(
            java_byte_array_field(env, share, "c1")?,
            "c1",
            PROTOCOL_FIELD_BYTES,
        )?,
        c2: require_len(
            java_byte_array_field(env, share, "c2")?,
            "c2",
            PROTOCOL_FIELD_BYTES,
        )?,
        share_index,
    })
}

pub(crate) fn java_wire_encrypted_share_list_field(
    env: &mut JNIEnv<'_>,
    obj: &JObject<'_>,
    name: &str,
) -> anyhow::Result<Vec<WireEncryptedShare>> {
    let list = env.get_field(obj, name, "Ljava/util/List;")?.l()?;
    let count = env.call_method(&list, "size", "()I", &[])?.i()?;
    if count < 0 {
        return Err(anyhow!("{name}.size() returned negative count {count}"));
    }

    (0..count)
        .map(|index| {
            let share = env
                .call_method(&list, "get", "(I)Ljava/lang/Object;", &[JValue::Int(index)])?
                .l()?;
            java_wire_encrypted_share(env, &share).map_err(|e| anyhow!("{name}[{index}]: {e}"))
        })
        .collect()
}

pub(crate) fn make_jni_wire_encrypted_share<'local>(
    env: &mut JNIEnv<'local>,
    share: WireEncryptedShare,
) -> anyhow::Result<JObject<'local>> {
    env.with_local_frame_returning_local(8, |env| {
        let class = env.find_class(JNI_WIRE_ENCRYPTED_SHARE)?;
        let c1 = make_jni_fixed_bytes(env, share.c1, "c1", PROTOCOL_FIELD_BYTES)?;
        let c2 = make_jni_fixed_bytes(env, share.c2, "c2", PROTOCOL_FIELD_BYTES)?;

        Ok(env.new_object(
            &class,
            JNI_WIRE_ENCRYPTED_SHARE_CTOR_SIG,
            &[
                JValue::Object(&c1),
                JValue::Object(&c2),
                JValue::Int(u32_to_jint(share.share_index, "share_index")?),
            ],
        )?)
    })
}

pub(crate) fn make_jni_wire_encrypted_share_array<'local>(
    env: &mut JNIEnv<'local>,
    shares: Vec<WireEncryptedShare>,
) -> anyhow::Result<JObjectArray<'local>> {
    let len = usize_to_jint(shares.len(), "shares length")?;
    let class = env.find_class(JNI_WIRE_ENCRYPTED_SHARE)?;
    let mut shares = shares.into_iter().enumerate();
    if let Some((_, first)) = shares.next() {
        let first = make_jni_wire_encrypted_share(env, first)?;
        let array = env.new_object_array(len, &class, &first)?;
        env.delete_local_ref(first)?;
        for (index, share) in shares {
            let share = make_jni_wire_encrypted_share(env, share)?;
            env.set_object_array_element(&array, usize_to_jint(index, "shares index")?, &share)?;
            env.delete_local_ref(share)?;
        }
        Ok(array)
    } else {
        Ok(env.new_object_array(0, &class, JObject::null())?)
    }
}

pub(crate) fn make_jni_share_payload<'local>(
    env: &mut JNIEnv<'local>,
    payload: SharePayload,
) -> anyhow::Result<JObject<'local>> {
    env.with_local_frame_returning_local(48, |env| {
        let class = env.find_class(JNI_SHARE_PAYLOAD)?;
        let shares_hash = make_jni_fixed_bytes(
            env,
            payload.shares_hash,
            "shares_hash",
            PROTOCOL_FIELD_BYTES,
        )?;
        let enc_share = make_jni_wire_encrypted_share(env, payload.enc_share)?;
        let all_enc_shares =
            require_count(payload.all_enc_shares, "all_enc_shares", VOTE_SHARE_COUNT)?;
        let all_enc_shares = make_jni_wire_encrypted_share_array(env, all_enc_shares)?;
        let share_comms = make_jni_fixed_byte_array_vec(
            env,
            payload.share_comms,
            "share_comms",
            VOTE_SHARE_COUNT,
            PROTOCOL_FIELD_BYTES,
        )?;
        let primary_blind = make_jni_fixed_bytes(
            env,
            payload.primary_blind,
            "primary_blind",
            PROTOCOL_FIELD_BYTES,
        )?;
        let all_enc_shares = JObject::from(all_enc_shares);
        let share_comms = JObject::from(share_comms);

        Ok(env.new_object(
            &class,
            JNI_SHARE_PAYLOAD_CTOR_SIG,
            &[
                JValue::Object(&shares_hash),
                JValue::Int(u32_to_jint(payload.proposal_id, "proposal_id")?),
                JValue::Int(u32_to_jint(payload.vote_decision, "vote_decision")?),
                JValue::Object(&enc_share),
                JValue::Long(u64_to_jlong(payload.tree_position, "tree_position")?),
                JValue::Object(&all_enc_shares),
                JValue::Object(&share_comms),
                JValue::Object(&primary_blind),
            ],
        )?)
    })
}

pub(crate) fn make_jni_share_payload_array<'local>(
    env: &mut JNIEnv<'local>,
    payloads: Vec<SharePayload>,
) -> anyhow::Result<jobjectArray> {
    let len = usize_to_jint(payloads.len(), "payloads length")?;
    let class = env.find_class(JNI_SHARE_PAYLOAD)?;
    let mut payloads = payloads.into_iter().enumerate();
    if let Some((_, first)) = payloads.next() {
        let first = make_jni_share_payload(env, first)?;
        let array = env.new_object_array(len, &class, &first)?;
        env.delete_local_ref(first)?;
        for (index, payload) in payloads {
            let payload = make_jni_share_payload(env, payload)?;
            env.set_object_array_element(
                &array,
                usize_to_jint(index, "payloads index")?,
                &payload,
            )?;
            env.delete_local_ref(payload)?;
        }
        Ok(array.into_raw())
    } else {
        Ok(env.new_object_array(0, &class, JObject::null())?.into_raw())
    }
}

pub(crate) fn make_jni_share_delegation_record<'local>(
    env: &mut JNIEnv<'local>,
    record: voting::ShareDelegationRecord,
) -> anyhow::Result<JObject<'local>> {
    env.with_local_frame_returning_local(24, |env| {
        let class = env.find_class(JNI_SHARE_DELEGATION_RECORD)?;
        let round_id: JObject<'_> = env.new_string(record.round_id)?.into();
        let sent_to_urls = make_jni_string_array(env, record.sent_to_urls)?;
        let sent_to_urls = JObject::from(sent_to_urls);
        let nullifier = make_jni_fixed_bytes(
            env,
            record.nullifier,
            "share_delegation.nullifier",
            SHARE_NULLIFIER_BYTES,
        )?;

        Ok(env.new_object(
            &class,
            JNI_SHARE_DELEGATION_RECORD_CTOR_SIG,
            &[
                JValue::Object(&round_id),
                JValue::Int(u32_to_jint(record.bundle_index, "bundle_index")?),
                JValue::Int(u32_to_jint(record.proposal_id, "proposal_id")?),
                JValue::Int(u32_to_jint(record.share_index, "share_index")?),
                JValue::Object(&sent_to_urls),
                JValue::Object(&nullifier),
                JValue::Bool(record.confirmed as jboolean),
                JValue::Long(u64_to_jlong(record.submit_at, "submit_at")?),
                JValue::Long(u64_to_jlong(record.created_at, "created_at")?),
            ],
        )?)
    })
}

pub(crate) fn make_jni_share_delegation_record_array<'local>(
    env: &mut JNIEnv<'local>,
    records: Vec<voting::ShareDelegationRecord>,
) -> anyhow::Result<jobjectArray> {
    let len = usize_to_jint(records.len(), "share delegation record length")?;
    let class = env.find_class(JNI_SHARE_DELEGATION_RECORD)?;
    let mut records = records.into_iter().enumerate();
    if let Some((_, first)) = records.next() {
        let first = make_jni_share_delegation_record(env, first)?;
        let array = env.new_object_array(len, &class, &first)?;
        env.delete_local_ref(first)?;
        for (index, record) in records {
            let record = make_jni_share_delegation_record(env, record)?;
            env.set_object_array_element(
                &array,
                usize_to_jint(index, "share delegation record index")?,
                &record,
            )?;
            env.delete_local_ref(record)?;
        }
        Ok(array.into_raw())
    } else {
        Ok(env.new_object_array(0, &class, JObject::null())?.into_raw())
    }
}
