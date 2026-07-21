//! Prove-at-broadcast-time support for the migration engine (ZIP 374 deferred anchor/witness),
//! ported from `librustzcash` branch `feature/orchard_migration` (historical, not merged),
//! `zcash_pool_migration/src/backend.rs::finalize_self_funding_transfer`/`prove_pczt`/
//! `orchard_anchor_at`, and adapted to the new engine's persistence shape.
//!
//! The core engine (`zcash_pool_migration_backend`) commits every migration transaction fully
//! built and signed, with its Orchard spend witness/anchor left unset — the durable artifact in
//! `MigrationTransaction::pczt()` never changes after commit. Proving derives a fresh, proven copy
//! from that durable pczt each time it's needed, using the `pczt::roles::updater::Updater`
//! (install witness + anchor) → `prover::Prover` → `spend_finalizer::SpendFinalizer` sequence.
//! Nothing about proving is persisted by the engine itself, so this module keeps a small side
//! table (`migration_proven_cache`, in the same store connection) mapping a `MigrationTxId` to its
//! most recently proven bytes + computed txid — bridging the engine's single "prove and broadcast
//! together" step (`AdvanceStep::Broadcast`) onto the app's existing two-JNI-call contract
//! (`finalizeReadyTransfersNative` advances proving; `nextDueTransferNative` + the app's own
//! network submission + `extractBroadcastTxNative` handle the actual broadcast).
//!
//! TODO: this is a stopgap so the SDK can be exercised against the new engine before broadcasting
//! actually works end to end — if/when core (`zcash_pool_migration_backend`) grows an equivalent
//! built-in helper, prefer that over this hand-ported copy.

use rusqlite::{Connection, OptionalExtension, params};

use orchard::keys::FullViewingKey;
use orchard::note::Note as OrchardNote;
use incrementalmerkletree::Position;
use zcash_client_backend::data_api::WalletCommitmentTrees;
use zcash_protocol::consensus::BlockHeight;

use zcash_pool_migration_backend::engine::MigrationTxId;

pub fn init_proven_cache(conn: &Connection) -> rusqlite::Result<()> {
    conn.execute_batch(
        "CREATE TABLE IF NOT EXISTS migration_proven_cache (
            tx_id INTEGER PRIMARY KEY,
            proven_pczt BLOB NOT NULL,
            txid BLOB NOT NULL
        )",
    )
}

pub fn put_proven(
    conn: &Connection,
    tx_id: MigrationTxId,
    proven_pczt: &[u8],
    txid: &[u8; 32],
) -> rusqlite::Result<()> {
    conn.execute(
        "INSERT INTO migration_proven_cache (tx_id, proven_pczt, txid) VALUES (?1, ?2, ?3)
         ON CONFLICT(tx_id) DO UPDATE SET proven_pczt = excluded.proven_pczt, txid = excluded.txid",
        params![u32::from(tx_id), proven_pczt, &txid[..]],
    )?;
    Ok(())
}

pub fn get_proven(
    conn: &Connection,
    tx_id: MigrationTxId,
) -> rusqlite::Result<Option<(Vec<u8>, [u8; 32])>> {
    conn.query_row(
        "SELECT proven_pczt, txid FROM migration_proven_cache WHERE tx_id = ?1",
        params![u32::from(tx_id)],
        |row| {
            let pczt: Vec<u8> = row.get(0)?;
            let txid_bytes: Vec<u8> = row.get(1)?;
            let mut txid = [0u8; 32];
            txid.copy_from_slice(&txid_bytes);
            Ok((pczt, txid))
        },
    )
    .optional()
}

/// Attempts to complete one migration transaction's PCZT. Two cases:
///
/// - **No redacted spend** (`witness().is_none()` finds nothing): the PCZT never needed deferred
///   witnessing (per `MigrationTransaction::anchor_boundary`'s doc, this is always true for
///   preparation transactions that spend an already-witnessed, already-mined wallet note directly
///   — e.g. the note-split transaction — since `anchor_boundary` is `None` for those specifically
///   *because* they don't anchor to a scheduled boundary at all). Already complete: extracts and
///   returns the given bytes' txid as-is, no proving work needed here (proving already happened
///   at commit time for these).
/// - **A redacted spend exists**: this is a ZIP 374 deferred-witness transaction (always true for
///   transfers, and for any later preparation layer that spends a note minted by an earlier one
///   not yet mined). `anchor_height` must be provided (`tx.anchor_boundary()` for a transfer; for
///   a dependent preparation transaction pass the wallet's current natural anchor height instead,
///   matching the old crate's non-ZIP-318-scheduled preparation-transaction handling). Matches the
///   redacted spend to a currently-spendable wallet note by nullifier, fetches that note's real
///   Merkle path at `anchor_height`, installs it + the anchor via the `Updater` role, proves,
///   finalizes spends, and extracts the resulting txid.
///
/// Returns `Ok(None)` — not an error — when a redacted spend exists but the funding note isn't a
/// currently spendable note yet (its parent hasn't mined/scanned yet), no `anchor_height` was
/// given for it, or its witness at `anchor_height` isn't available (checkpoint not reached, or
/// pruned — see `PRUNING_DEPTH`/`BOUNDARY_MODULUS` mismatch tracked in
/// `docs/superpowers/specs/2026-07-21-current-migration-implementation-spec.md` §6.4/§7 item 1,
/// which is expected to make this permanently `None` for core-scheduled transfer anchors until
/// that's fixed upstream): all are the ordinary transient "not ready yet" state, not a failure.
pub fn finalize_transaction<W>(
    wallet: &mut W,
    fvk: &FullViewingKey,
    spendable: &[(OrchardNote, Position, u64)],
    anchor_height: Option<BlockHeight>,
    pczt_bytes: &[u8],
) -> anyhow::Result<Option<(Vec<u8>, [u8; 32])>>
where
    W: WalletCommitmentTrees,
    W::Error: std::error::Error + Send + Sync + 'static,
{
    let pczt = pczt::Pczt::parse(pczt_bytes)
        .map_err(|e| anyhow::anyhow!("finalize transfer: parse pczt: {e:?}"))?;

    let actions = pczt.orchard().actions();
    // A transaction can spend more than one wallet note at once (e.g. the note-split transaction,
    // which may gather several inputs to fund its outputs) — every action whose spend has no
    // witness yet needs one resolved, not just the first (confirmed live: only resolving the first
    // left the rest unset, and `Prover` failed with `MissingWitness`).
    let redacted_indices: Vec<usize> = actions
        .iter()
        .enumerate()
        .filter(|(_, action)| action.spend().witness().is_none())
        .map(|(i, _)| i)
        .collect();
    tracing::debug!(
        "MIGRATION_DIAG finalize_transaction: {} orchard action(s), {} redacted spend(s), \
         anchor_height={:?}",
        actions.len(),
        redacted_indices.len(),
        anchor_height,
    );
    if redacted_indices.is_empty() {
        // No redacted spend awaiting a witness — already complete, extract as-is.
        let tx = pczt::roles::tx_extractor::TransactionExtractor::new(pczt)
            .extract()
            .map_err(|e| anyhow::anyhow!("finalize transfer: extract tx: {e:?}"))?;
        let txid: [u8; 32] = *tx.txid().as_ref();
        return Ok(Some((pczt_bytes.to_vec(), txid)));
    }
    let Some(anchor_height) = anchor_height else {
        tracing::debug!(
            "MIGRATION_DIAG finalize_transaction: {} redacted spend(s) but no anchor_height given",
            redacted_indices.len(),
        );
        // Redacted spends exist but we weren't given a height to resolve them against yet.
        return Ok(None);
    };

    let anchor = wallet
        .with_orchard_tree_mut::<_, _, anyhow::Error>(|tree| {
            Ok(tree.root_at_checkpoint_id(&anchor_height)?.map(Into::into))
        })
        .map_err(|e| anyhow::anyhow!("finalize transfer: read anchor: {e}"))?;
    let Some(anchor): Option<orchard::Anchor> = anchor else {
        tracing::debug!(
            "MIGRATION_DIAG finalize_transaction: no checkpoint at anchor_height {:?} — transient",
            anchor_height,
        );
        // No checkpoint at anchor_height yet (or it's been pruned) — transient, retry later.
        return Ok(None);
    };

    let mut witnesses = Vec::with_capacity(redacted_indices.len());
    for spend_index in redacted_indices {
        let nullifier_bytes = *actions[spend_index].spend().nullifier();
        let Some(&(_, position, _)) = spendable
            .iter()
            .find(|(note, _, _)| note.nullifier(fvk).to_bytes() == nullifier_bytes)
        else {
            tracing::debug!(
                "MIGRATION_DIAG finalize_transaction: no spendable note matches nullifier {} at \
                 index {spend_index} — transient, funding note not observed yet",
                hex::encode(nullifier_bytes),
            );
            // The funding note isn't a currently spendable wallet note yet — transient, retry.
            return Ok(None);
        };

        let witness = wallet
            .with_orchard_tree_mut::<_, _, anyhow::Error>(|tree| {
                match tree.witness_at_checkpoint_id_caching(position, &anchor_height) {
                    Ok(path) => Ok(path),
                    Err(shardtree::error::ShardTreeError::Query(
                        shardtree::error::QueryError::NotContained(_)
                        | shardtree::error::QueryError::CheckpointPruned,
                    )) => Ok(None),
                    Err(e) => Err(anyhow::anyhow!("finalize transfer: read witness: {e}")),
                }
            })
            .map_err(|e| anyhow::anyhow!("finalize transfer: {e}"))?;
        let Some(merkle_path) = witness else {
            tracing::debug!(
                "MIGRATION_DIAG finalize_transaction: note at position {:?} (index {spend_index}) \
                 not witnessable at anchor_height {:?} — transient",
                position,
                anchor_height,
            );
            // Not witnessable at anchor_height yet — transient, retry later.
            return Ok(None);
        };
        witnesses.push((spend_index, orchard::tree::MerklePath::from(merkle_path)));
    }
    tracing::debug!(
        "MIGRATION_DIAG finalize_transaction: {} witness(es) resolved, proving now",
        witnesses.len(),
    );

    let updated = pczt::roles::updater::Updater::new(pczt)
        .set_orchard_spend_witnesses(witnesses)
        .map_err(|e| anyhow::anyhow!("finalize transfer: set spend witness: {e:?}"))?
        .set_orchard_anchor(anchor)
        .map_err(|e| anyhow::anyhow!("finalize transfer: set anchor: {e:?}"))?
        .finish();

    let proven = prove_pczt(updated)?;
    let finalized = pczt::roles::spend_finalizer::SpendFinalizer::new(proven)
        .finalize_spends()
        .map_err(|e| anyhow::anyhow!("finalize transfer: finalize spends: {e:?}"))?;
    let pczt_bytes = finalized
        .clone()
        .serialize()
        .map_err(|e| anyhow::anyhow!("finalize transfer: serialize pczt: {e:?}"))?;

    let tx = pczt::roles::tx_extractor::TransactionExtractor::new(finalized)
        .extract()
        .map_err(|e| anyhow::anyhow!("finalize transfer: extract tx: {e:?}"))?;
    let txid: [u8; 32] = *tx.txid().as_ref();

    Ok(Some((pczt_bytes, txid)))
}

/// Ported from `librustzcash` branch `feature/orchard_migration`,
/// `zcash_pool_migration/src/backend.rs::shielded_proving_key`.
fn shielded_proving_key() -> &'static orchard::circuit::ProvingKey {
    static PK: std::sync::OnceLock<orchard::circuit::ProvingKey> = std::sync::OnceLock::new();
    PK.get_or_init(|| {
        orchard::circuit::ProvingKey::build(orchard::circuit::OrchardCircuitVersion::PostNu6_3)
    })
}

fn prove_pczt(pczt: pczt::Pczt) -> anyhow::Result<pczt::Pczt> {
    let mut prover = pczt::roles::prover::Prover::new(pczt);
    if prover.requires_orchard_proof() {
        prover = prover
            .create_orchard_proof(shielded_proving_key())
            .map_err(|e| anyhow::anyhow!("orchard proof: {e:?}"))?;
    }
    if prover.requires_ironwood_proof() {
        prover = prover
            .create_ironwood_proof(shielded_proving_key())
            .map_err(|e| anyhow::anyhow!("ironwood proof: {e:?}"))?;
    }
    Ok(prover.finish())
}
