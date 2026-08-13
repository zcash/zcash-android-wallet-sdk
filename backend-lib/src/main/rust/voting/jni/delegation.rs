//! Delegation marshalling: the governance PCZT, the PIR precompute and proof
//! results, the submission fields, and per-bundle delegation phase.

use super::super::helpers::*;
use super::super::*;

pub(crate) fn make_jni_delegation_phases(
    env: &mut JNIEnv<'_>,
    phases: Vec<(u32, voting::phases::DelegationPhase)>,
) -> anyhow::Result<jobjectArray> {
    // jint conversion happens up front (anyhow::Result) since the rust_vec_to_java
    // element closure below must return a plain jni::errors::Result.
    let payloads = phases
        .into_iter()
        .map(|(bundle_index, phase)| Ok((u32_to_jint(bundle_index, "bundle_index")?, phase)))
        .collect::<anyhow::Result<Vec<_>>>()?;

    Ok(rust_vec_to_java(
        env,
        payloads,
        JNI_DELEGATION_PHASE,
        |env, (bundle_index, phase)| {
            let phase_obj: JObject<'_> = env.new_string(phase.as_str())?.into();
            env.new_object(
                JNI_DELEGATION_PHASE,
                JNI_DELEGATION_PHASE_CTOR_SIG,
                &[JValue::Int(bundle_index), JValue::Object(&phase_obj)],
            )
        },
    )?
    .into_raw())
}

pub(crate) fn make_jni_governance_pczt<'local>(
    env: &mut JNIEnv<'local>,
    pczt: GovernancePczt,
) -> anyhow::Result<jobject> {
    let class = env.find_class(JNI_GOVERNANCE_PCZT)?;
    let action_index = u32_to_jint(
        u32::try_from(pczt.action_index)
            .map_err(|_| anyhow!("action_index is too large for u32: {}", pczt.action_index))?,
        "action_index",
    )?;
    let pczt_bytes = make_jni_bytes(env, &pczt.pczt_bytes)?;
    let rk = make_jni_fixed_bytes(env, pczt.rk, "rk", PROTOCOL_FIELD_BYTES)?;
    let sighash =
        make_jni_fixed_bytes(env, pczt.pczt_sighash, "pczt_sighash", PROTOCOL_FIELD_BYTES)?;

    let obj = env.new_object(
        &class,
        JNI_GOVERNANCE_PCZT_CTOR_SIG,
        &[
            JValue::Object(&pczt_bytes),
            JValue::Object(&rk),
            JValue::Object(&sighash),
            JValue::Int(action_index),
        ],
    )?;
    Ok(obj.into_raw())
}

pub(crate) fn make_jni_delegation_pir_precompute_result<'local>(
    env: &mut JNIEnv<'local>,
    result: DelegationPirPrecomputeResult,
) -> anyhow::Result<jobject> {
    let class = env.find_class(JNI_DELEGATION_PIR_PRECOMPUTE_RESULT)?;
    let obj = env.new_object(
        &class,
        JNI_DELEGATION_PIR_PRECOMPUTE_RESULT_CTOR_SIG,
        &[
            JValue::Long(u64_to_jlong(
                u64::from(result.cached_count),
                "cached_count",
            )?),
            JValue::Long(u64_to_jlong(
                u64::from(result.fetched_count),
                "fetched_count",
            )?),
        ],
    )?;
    Ok(obj.into_raw())
}

pub(crate) fn make_jni_delegation_proof_result<'local>(
    env: &mut JNIEnv<'local>,
    result: DelegationProofResult,
) -> anyhow::Result<jobject> {
    let class = env.find_class(JNI_DELEGATION_PROOF_RESULT)?;
    let proof_obj = make_jni_bytes(env, &result.proof)?;
    let public_inputs_array = make_jni_fixed_byte_array_vec(
        env,
        result.public_inputs,
        "public_inputs",
        DELEGATION_PUBLIC_INPUT_COUNT,
        PROTOCOL_FIELD_BYTES,
    )?;
    let nf_signed = make_jni_fixed_bytes(env, result.nf_signed, "nf_signed", PROTOCOL_FIELD_BYTES)?;
    let cmx_new = make_jni_fixed_bytes(env, result.cmx_new, "cmx_new", PROTOCOL_FIELD_BYTES)?;
    let gov_nullifiers_array = make_jni_fixed_byte_array_vec(
        env,
        result.gov_nullifiers,
        "gov_nullifiers",
        GOVERNANCE_NULLIFIER_COUNT,
        PROTOCOL_FIELD_BYTES,
    )?;
    let van_comm = make_jni_fixed_bytes(env, result.van_comm, "van_comm", PROTOCOL_FIELD_BYTES)?;
    let rk = make_jni_fixed_bytes(env, result.rk, "rk", PROTOCOL_FIELD_BYTES)?;
    let public_inputs_obj = JObject::from(public_inputs_array);
    let gov_nullifiers_obj = JObject::from(gov_nullifiers_array);

    let obj = env.new_object(
        &class,
        JNI_DELEGATION_PROOF_RESULT_CTOR_SIG,
        &[
            JValue::Object(&proof_obj),
            JValue::Object(&public_inputs_obj),
            JValue::Object(&nf_signed),
            JValue::Object(&cmx_new),
            JValue::Object(&gov_nullifiers_obj),
            JValue::Object(&van_comm),
            JValue::Object(&rk),
        ],
    )?;
    Ok(obj.into_raw())
}

pub(crate) fn make_jni_delegation_submission_result<'local>(
    env: &mut JNIEnv<'local>,
    data: DelegationSubmissionData,
) -> anyhow::Result<jobject> {
    let class = env.find_class(JNI_DELEGATION_SUBMISSION_RESULT)?;
    let proof = make_jni_bytes(env, &data.proof)?;
    let rk = make_jni_fixed_bytes(env, data.rk, "rk", PROTOCOL_FIELD_BYTES)?;
    let spend_auth_sig = make_jni_fixed_bytes(
        env,
        data.spend_auth_sig,
        "spend_auth_sig",
        SPEND_AUTH_SIG_BYTES,
    )?;
    let sighash = make_jni_fixed_bytes(env, data.sighash, "sighash", PROTOCOL_FIELD_BYTES)?;
    let tx1_effects = make_jni_fixed_bytes(
        env,
        data.tx1_effects,
        "tx1_effects",
        voting::tx1::TX1_EFFECTS_LEN,
    )?;
    let nf_signed = make_jni_fixed_bytes(env, data.nf_signed, "nf_signed", PROTOCOL_FIELD_BYTES)?;
    let cmx_new = make_jni_fixed_bytes(env, data.cmx_new, "cmx_new", PROTOCOL_FIELD_BYTES)?;
    let gov_comm = make_jni_fixed_bytes(env, data.gov_comm, "gov_comm", PROTOCOL_FIELD_BYTES)?;
    let gov_nullifiers_array = make_jni_fixed_byte_array_vec(
        env,
        data.gov_nullifiers,
        "gov_nullifiers",
        GOVERNANCE_NULLIFIER_COUNT,
        PROTOCOL_FIELD_BYTES,
    )?;
    let vote_round_id: JObject<'local> = env.new_string(data.vote_round_id)?.into();
    let gov_nullifiers = JObject::from(gov_nullifiers_array);

    let obj = env.new_object(
        &class,
        JNI_DELEGATION_SUBMISSION_RESULT_CTOR_SIG,
        &[
            JValue::Object(&proof),
            JValue::Object(&rk),
            JValue::Object(&spend_auth_sig),
            JValue::Object(&sighash),
            JValue::Object(&tx1_effects),
            JValue::Object(&nf_signed),
            JValue::Object(&cmx_new),
            JValue::Object(&gov_comm),
            JValue::Object(&gov_nullifiers),
            JValue::Object(&vote_round_id),
        ],
    )?;
    Ok(obj.into_raw())
}
