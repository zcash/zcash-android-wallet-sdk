//! Round marshalling: per-round state, the round list, and bundle setup.

use super::super::helpers::*;
use super::super::*;

pub(crate) struct JniRoundSummaryPayload {
    round_id: String,
    phase: jint,
    snapshot_height: jlong,
    created_at: jlong,
}

impl TryFrom<RoundSummary> for JniRoundSummaryPayload {
    type Error = anyhow::Error;

    fn try_from(round: RoundSummary) -> anyhow::Result<Self> {
        Ok(JniRoundSummaryPayload {
            round_id: round.round_id,
            phase: u32_to_jint(round_phase_to_u32(round.phase), "phase")?,
            snapshot_height: u64_to_jlong(round.snapshot_height, "snapshot_height")?,
            created_at: u64_to_jlong(round.created_at, "created_at")?,
        })
    }
}

pub(crate) fn make_jni_round_state<'local>(
    env: &mut JNIEnv<'local>,
    state: RoundState,
) -> anyhow::Result<jobject> {
    let phase = round_phase_to_u32(state.phase);
    let class = env.find_class("cash/z/ecc/android/sdk/internal/model/voting/JniRoundState")?;
    let round_id_obj: JObject<'local> = env.new_string(&state.round_id)?.into();
    let hotkey_obj: JObject<'local> = match &state.hotkey_address {
        Some(a) => env.new_string(a)?.into(),
        None => JObject::null(),
    };
    let long_class = env.find_class("java/lang/Long")?;
    let weight_obj: JObject<'local> = match state.delegated_weight {
        Some(w) => env.new_object(
            &long_class,
            "(J)V",
            &[JValue::Long(u64_to_jlong(w, "delegated_weight")?)],
        )?,
        None => JObject::null(),
    };
    let obj = env.new_object(
        &class,
        // Matches JniRoundState(roundId, phase, snapshotHeight, hotkeyAddress,
        //                       delegatedWeight, proofGenerated).
        "(Ljava/lang/String;IJLjava/lang/String;Ljava/lang/Long;Z)V",
        &[
            JValue::Object(&round_id_obj),
            JValue::Int(u32_to_jint(phase, "round_phase")?),
            JValue::Long(u64_to_jlong(state.snapshot_height, "snapshot_height")?),
            JValue::Object(&hotkey_obj),
            JValue::Object(&weight_obj),
            JValue::Bool(state.proof_generated as jboolean),
        ],
    )?;
    Ok(obj.into_raw())
}

pub(crate) fn make_jni_round_summaries(
    env: &mut JNIEnv<'_>,
    rounds: Vec<RoundSummary>,
) -> anyhow::Result<jobjectArray> {
    let payloads = rounds
        .into_iter()
        .map(JniRoundSummaryPayload::try_from)
        .collect::<anyhow::Result<Vec<_>>>()?;

    Ok(
        rust_vec_to_java(env, payloads, JNI_ROUND_SUMMARY, |env, round| {
            let round_id_obj: JObject<'_> = env.new_string(round.round_id)?.into();
            env.new_object(
                JNI_ROUND_SUMMARY,
                // Matches JniRoundSummary(roundId, phase, snapshotHeight, createdAt).
                "(Ljava/lang/String;IJJ)V",
                &[
                    JValue::Object(&round_id_obj),
                    JValue::Int(round.phase),
                    JValue::Long(round.snapshot_height),
                    JValue::Long(round.created_at),
                ],
            )
        })?
        .into_raw(),
    )
}

/// Builds the Kotlin bundle setup JNI model with width-checked Java primitives.
pub(crate) fn make_jni_bundle_setup_result<'local>(
    env: &mut JNIEnv<'local>,
    count: u32,
    weight: u64,
    bundle_weights: &[u64],
) -> anyhow::Result<jobject> {
    let class = env.find_class(JNI_BUNDLE_SETUP_RESULT)?;
    let weights = bundle_weights
        .iter()
        .enumerate()
        .map(|(index, weight)| u64_to_jlong(*weight, &format!("bundle_weights[{index}]")))
        .collect::<anyhow::Result<Vec<_>>>()?;
    let weights_array =
        env.new_long_array(usize_to_jint(weights.len(), "bundle_weights length")?)?;
    env.set_long_array_region(&weights_array, 0, &weights)?;
    let weights_array_obj = JObject::from(weights_array);
    let obj = env.new_object(
        &class,
        JNI_BUNDLE_SETUP_RESULT_CTOR_SIG,
        &[
            JValue::Int(u32_to_jint(count, "bundle_count")?),
            JValue::Long(u64_to_jlong(weight, "eligible_weight")?),
            JValue::Object(&weights_array_obj),
        ],
    )?;
    Ok(obj.into_raw())
}
