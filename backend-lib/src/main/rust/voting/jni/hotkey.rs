//! Voting hotkey marshalling.

use super::super::helpers::*;
use super::super::*;

/// Builds the Kotlin hotkey JNI model, including the opaque stored secret.
///
/// Unlike the pre-1.0 wallet-seed-derived hotkey, `generateHotkeyNative` can
/// mint a fresh app-owned hotkey identity, so its stored secret must cross
/// JNI here for Android to persist in secure storage; the crate never
/// re-derives it from the wallet seed.
pub(crate) fn make_jni_voting_hotkey<'local>(
    env: &mut JNIEnv<'local>,
    hotkey: voting::types::VotingHotkey,
) -> anyhow::Result<jobject> {
    let stored_secret = require_len(
        hotkey.stored_secret().to_vec(),
        "hotkey_stored_secret",
        HOTKEY_STORED_SECRET_BYTES,
    )?;
    let raw_address = *hotkey.raw_orchard_address();
    let address = hotkey_unified_address(&raw_address, hotkey.network())?;

    let class = env.find_class(JNI_VOTING_HOTKEY)?;
    let secret_obj: JObject<'local> = env.byte_array_from_slice(&stored_secret)?.into();
    let raw_address_obj: JObject<'local> = env.byte_array_from_slice(&raw_address)?.into();
    let addr_obj: JObject<'local> = env.new_string(&address)?.into();
    let obj = env.new_object(
        &class,
        JNI_VOTING_HOTKEY_CTOR_SIG,
        &[
            JValue::Object(&secret_obj),
            JValue::Object(&raw_address_obj),
            JValue::Object(&addr_obj),
        ],
    )?;
    Ok(obj.into_raw())
}
