//! Keystone (hardware-wallet) batch-signing UR bridge for the Orchard migration flow.
//!
//! Bypasses the compiled `keystone-sdk-android` AAR (stale — single-PCZT only, no batch API) by
//! depending directly on `ur-registry`/`ur` (see `Cargo.toml`'s comment), mirroring the pattern
//! `chainapsis/vizor-wallet` already uses for its own Keystone integration. The batching
//! primitive itself is `pczt::roles::signer::batch::{BatchSignRequest, BatchSignResponse}` — not
//! Keystone-specific; these crates only provide the outer UR/CBOR/QR envelope
//! (`ZcashSignBatch`/`ZcashBatchSigResult`, registry types `"zcash-sign-batch"`/
//! `"zcash-batch-sig-result"`).
//!
//! `BatchSignResponse`'s signatures are aligned by *position*, not by any id embedded in the
//! wire format — callers here are responsible for retaining the same (split, then transfers-in-
//! schedule-order) ordering between [`build_sign_batch_qr_parts`] and [`apply_batch_signatures`].
//! The unsigned PCZT bytes are passed back into `apply_batch_signatures` by the Kotlin caller
//! (which already holds them, from the `createUnsignedNoteSplitPczt`/`createUnsignedTransferPczts`
//! calls that produced them) rather than retained as Rust-side session state — the only
//! genuinely stateful piece here is the multi-frame QR *decode* accumulation, which inherently
//! spans multiple JNI calls (one per scanned camera frame).

use std::sync::Mutex;

use pczt::roles::signer::batch::{BatchSignRequest, BatchSignResponse};
use pczt::roles::signer::{Signer, SpendAuthSignature};
use ur_registry::traits::RegistryItem;
use ur_registry::zcash::zcash_batch_sig_result::ZcashBatchSigResult;
use ur_registry::zcash::zcash_sign_batch::ZcashSignBatch;

/// Builds the animated multi-part QR frames for a Keystone batch-signing request covering the
/// optional note-split PCZT and every schedule transfer's unsigned PCZT, in that order.
///
/// `request_id` is an opaque correlation token (e.g. a UUID's bytes) round-tripped by the device
/// and checked in [`decode_sign_batch_part`] to reject a scan of an unrelated/stale response.
pub fn build_sign_batch_qr_parts(
    request_id: Vec<u8>,
    split_unsigned: Option<&[u8]>,
    transfer_unsigned: &[Vec<u8>],
    max_fragment_len: usize,
) -> anyhow::Result<Vec<String>> {
    let mut pczts = Vec::with_capacity(transfer_unsigned.len() + 1);
    if let Some(split) = split_unsigned {
        pczts.push(pczt::parse(split).map_err(|e| anyhow::anyhow!("parse split pczt: {e:?}"))?);
    }
    for bytes in transfer_unsigned {
        pczts.push(
            pczt::parse(bytes).map_err(|e| anyhow::anyhow!("parse transfer pczt: {e:?}"))?,
        );
    }

    let request = BatchSignRequest::new(pczts);
    let data = request
        .serialize()
        .map_err(|e| anyhow::anyhow!("serialize batch sign request: {e:?}"))?;
    let batch = ZcashSignBatch::new(request_id, data);
    let cbor: Vec<u8> = batch
        .try_into()
        .map_err(|e| anyhow::anyhow!("cbor-encode zcash-sign-batch: {e:?}"))?;

    let mut encoder = ur::Encoder::new(
        &cbor,
        max_fragment_len,
        ZcashSignBatch::get_registry_type().get_type(),
    )
    .map_err(|e| anyhow::anyhow!("ur encoder: {e}"))?;
    let count = encoder.fragment_count();
    let mut parts = Vec::with_capacity(count);
    for _ in 0..count {
        parts.push(
            encoder
                .next_part()
                .map_err(|e| anyhow::anyhow!("ur next_part: {e}"))?
                .to_uppercase(),
        );
    }
    Ok(parts)
}

/// In-flight multi-part `zcash-batch-sig-result` scan session. `None` means no session in
/// flight — mirrors `chainapsis/vizor-wallet`'s `UR_SESSION`/`UrSession` pattern for the same
/// reason: a JNI call per scanned QR frame has nowhere else to keep fountain-decoder state.
static DECODE_SESSION: Mutex<Option<ur::Decoder>> = Mutex::new(None);

/// The result of feeding one scanned QR frame to [`decode_sign_batch_part`].
pub struct DecodePartResult {
    pub complete: bool,
    pub progress: u32,
    /// The serialized `BatchSignResponse` bytes, once `complete` — feed into
    /// [`apply_batch_signatures`].
    pub data: Option<Vec<u8>>,
}

/// Discards any in-flight multi-part scan session. Callers should invoke this on scan-screen
/// entry so a new attempt always starts from a clean slate regardless of how a previous attempt
/// ended (cancel, back button, mid-stream error).
pub fn reset_sign_batch_decoder() {
    if let Ok(mut guard) = DECODE_SESSION.lock() {
        *guard = None;
    }
}

/// Feeds one scanned QR frame into the active (or a freshly started) decode session, pinned to
/// the `"zcash-batch-sig-result"` UR type. `expected_request_id` must match the decoded
/// `ZcashBatchSigResult`'s own request id once complete, or this returns an error (a scan of an
/// unrelated/stale response) instead of silently accepting it.
pub fn decode_sign_batch_part(
    part: &str,
    expected_request_id: &[u8],
) -> anyhow::Result<DecodePartResult> {
    let part_lower = part.to_lowercase();
    let mut guard = DECODE_SESSION
        .lock()
        .map_err(|_| anyhow::anyhow!("decode session lock poisoned"))?;

    if guard.is_none() {
        let (kind, cbor) =
            ur::decode(&part_lower).map_err(|e| anyhow::anyhow!("ur decode: {e}"))?;
        match kind {
            ur::ur::Kind::SinglePart => return finish_decode(cbor, expected_request_id),
            ur::ur::Kind::MultiPart => {
                let mut decoder = ur::Decoder::default();
                decoder
                    .receive(&part_lower)
                    .map_err(|e| anyhow::anyhow!("ur receive: {e}"))?;
                let progress = decoder.progress();
                *guard = Some(decoder);
                return Ok(DecodePartResult {
                    complete: false,
                    progress: progress as u32,
                    data: None,
                });
            }
        }
    }

    if let Err(e) = guard.as_mut().unwrap().receive(&part_lower) {
        *guard = None;
        return Err(anyhow::anyhow!("ur receive: {e}"));
    }

    if guard.as_ref().unwrap().complete() {
        let message = guard
            .as_mut()
            .unwrap()
            .message()
            .map_err(|e| anyhow::anyhow!("ur message: {e}"))?;
        *guard = None;
        let cbor = message.ok_or_else(|| anyhow::anyhow!("decoder complete but no message"))?;
        return finish_decode(cbor, expected_request_id);
    }

    let progress = guard.as_ref().unwrap().progress();
    Ok(DecodePartResult {
        complete: false,
        progress: progress as u32,
        data: None,
    })
}

fn finish_decode(cbor: Vec<u8>, expected_request_id: &[u8]) -> anyhow::Result<DecodePartResult> {
    let result = ZcashBatchSigResult::try_from(cbor)
        .map_err(|e| anyhow::anyhow!("cbor-decode zcash-batch-sig-result: {e:?}"))?;
    if result.get_request_id() != expected_request_id {
        return Err(anyhow::anyhow!(
            "zcash-batch-sig-result request id does not match the outstanding sign request"
        ));
    }
    Ok(DecodePartResult {
        complete: true,
        progress: 100,
        data: Some(result.get_data().to_vec()),
    })
}

/// Applies a decoded `BatchSignResponse` back to the retained unsigned PCZTs — in the exact
/// split-then-transfers order they were passed to [`build_sign_batch_qr_parts`] — producing
/// signed-but-unproven PCZT bytes for each. These are the same shape `store_signed_note_split_pczt`/
/// `store_signed_schedule_pczts` already expect from the software-signing composition (they
/// combine this with the staged *proven* original internally) — no other change needed there.
///
/// Returns an error if the response's signature-set count doesn't match the number of PCZTs sent.
pub fn apply_batch_signatures(
    split_unsigned: Option<&[u8]>,
    transfer_unsigned: &[Vec<u8>],
    batch_sign_response: &[u8],
) -> anyhow::Result<(Option<Vec<u8>>, Vec<Vec<u8>>)> {
    let response = BatchSignResponse::parse(batch_sign_response)
        .map_err(|e| anyhow::anyhow!("parse batch sign response: {e:?}"))?;
    let signatures = response.signatures();
    let expected = transfer_unsigned.len() + usize::from(split_unsigned.is_some());
    if signatures.len() != expected {
        return Err(anyhow::anyhow!(
            "batch sign response has {} signature set(s), expected {expected}",
            signatures.len(),
        ));
    }

    let mut idx = 0;
    let split_signed = match split_unsigned {
        Some(bytes) => {
            let signed = apply_signatures_to_one(bytes, &signatures[idx])?;
            idx += 1;
            Some(signed)
        }
        None => None,
    };

    let mut transfers_signed = Vec::with_capacity(transfer_unsigned.len());
    for bytes in transfer_unsigned {
        transfers_signed.push(apply_signatures_to_one(bytes, &signatures[idx])?);
        idx += 1;
    }

    Ok((split_signed, transfers_signed))
}

fn apply_signatures_to_one(
    unsigned_bytes: &[u8],
    sigs: &[SpendAuthSignature],
) -> anyhow::Result<Vec<u8>> {
    let pczt =
        pczt::parse(unsigned_bytes).map_err(|e| anyhow::anyhow!("parse unsigned pczt: {e:?}"))?;
    let mut signer = Signer::new(pczt).map_err(|e| anyhow::anyhow!("signer init: {e:?}"))?;
    for sig in sigs {
        signer
            .apply_orchard_spend_auth_signature(sig)
            .map_err(|e| anyhow::anyhow!("apply spend auth signature: {e:?}"))?;
    }
    signer
        .finish()
        .serialize()
        .map_err(|e| anyhow::anyhow!("serialize signed pczt: {e:?}"))
}
