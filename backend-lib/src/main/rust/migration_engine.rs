//! Adapter wiring the Android wallet database into `zcash_pool_migration`'s
//! `MigrationBackend`/`MigrationCrypto`/`PoolMigrationRead`/`PoolMigrationWrite` traits.
//!
//! This is deliberately a separate, thinner adapter than `zcash_pool_migration::wallet::
//! WalletMigration`: that type's constructor requires a `UnifiedSpendingKey` unconditionally
//! (its `orchard_fvk()` is derived from the usk), but several JNI entry points in `migration.rs`
//! only ever plan or build unsigned PCZTs (no usk available at that call site — mirroring the old
//! `zcash_pool_migration` crate, which likewise derived the FVK from the account's stored UFVK,
//! not from a spending key). `Backend::usk` is therefore optional: every method needed for
//! planning/building unsigned PCZTs (`orchard_fvk`, `resolve_wallet_note`,
//! `spendable_orchard_note_values`, `chain_tip_height`) works without it; only `sign()` requires
//! one, and `zcash_pool_migration::engine::build_preparation_unsigned` never calls it (only
//! `commit_preparation`'s in-process-signing path does).

use std::convert::Infallible;

use rusqlite::Connection;

use incrementalmerkletree::Position;
use orchard::keys::{FullViewingKey, Scope, SpendAuthorizingKey};
use orchard::note::Note as OrchardNote;
use zcash_client_backend::address::Receiver;
use zcash_client_backend::data_api::MaxSpendMode;
use zcash_client_backend::data_api::wallet::TargetHeight;
use zcash_client_backend::data_api::wallet::input_selection::{LockFilter, LockedInputPolicy};
use zcash_client_backend::data_api::wallet::{ConfirmationsPolicy, propose_send_max_transfer};
use zcash_client_backend::data_api::{Account, InputSource, WalletRead};
use zcash_client_backend::fees::StandardFeeRule;
use zcash_client_backend::keys::UnifiedSpendingKey;
use zcash_client_backend::proposal::Proposal;
use zcash_client_sqlite::AccountUuid;
use zcash_protocol::ShieldedPool;
use zcash_protocol::TxId;
use zcash_protocol::consensus::{BlockHeight, Network, Parameters};
use zcash_protocol::value::Zatoshis;

use zcash_client_sqlite::pool_migration::orchard_ironwood::PoolMigrations;
use zcash_client_sqlite::util::SystemClock;
use zcash_pool_migration::build::AccountDerivation;
use zcash_pool_migration::engine::{
    MigrationBackend, MigrationCrypto, MigrationState, MigrationTransaction, MigrationTransferId,
    MigrationTxState, PoolMigrationRead, PoolMigrationWrite,
};
use zcash_pool_migration::satisfiability::{ReorgSettleDepth, StepSatisfiability};
use zcash_pool_migration::scheduling::SchedulingParams;

use crate::migration::Wallet;

type SpendableNote = (OrchardNote, Position, u64);

/// The migration adapter's `Backend`/`MigrationCrypto`/`PoolMigrationRead`/`PoolMigrationWrite`
/// error type. Everything is folded into `anyhow::Error` (matching the rest of this JNI glue's
/// idiom) rather than the parameterized error type `WalletMigration` uses, since this adapter is
/// only ever instantiated over one concrete wallet type.
pub type EngineError = anyhow::Error;

/// A migration backend over the Android SDK's own wallet database, an account, an optional
/// spending key (required only for in-process signing), and a `PoolMigrations` store borrow.
pub struct Backend<'a, W> {
    wallet: &'a W,
    account: AccountUuid,
    usk: Option<UnifiedSpendingKey>,
    store: PoolMigrations<&'a mut Connection, Network, SystemClock>,
}

impl<'a, W> Backend<'a, W>
where
    W: WalletRead<AccountId = AccountUuid> + InputSource<AccountId = AccountUuid>,
    <W as WalletRead>::Error: std::error::Error + Send + Sync + 'static,
    <W as InputSource>::Error: std::error::Error + Send + Sync + 'static,
{
    /// Fails if `account` has no row in the wallet's `accounts` table (the store is now scoped to
    /// the account row, not a per-wallet singleton — see `PoolMigrations::for_account`).
    pub fn new(
        network: &Network,
        wallet: &'a W,
        account: AccountUuid,
        usk: Option<UnifiedSpendingKey>,
        conn: &'a mut Connection,
    ) -> Result<Self, EngineError> {
        let store = PoolMigrations::for_account(*network, SystemClock, conn, account)
            .map_err(|e| anyhow::anyhow!("opening pool-migration store failed: {e:?}"))?;
        Ok(Self {
            wallet,
            account,
            usk,
            store,
        })
    }

    fn selection_target(&self) -> Result<TargetHeight, EngineError> {
        let tip = self
            .wallet
            .chain_height()
            .map_err(|e| anyhow::anyhow!("chain height lookup failed: {e}"))?
            .ok_or_else(|| anyhow::anyhow!("wallet has no chain tip yet"))?;
        Ok(TargetHeight::from(u32::from(tip) + 1))
    }

    /// The account's spendable Orchard notes as `(note, tree position, value)`, sorted by tree
    /// position so an index is stable across calls within one JNI invocation (matches
    /// `WalletMigration`'s own ordering contract, which the engine relies on).
    fn spendable_orchard(&self) -> Result<Vec<SpendableNote>, EngineError> {
        let target = self.selection_target()?;
        // Exclude notes locked by another in-flight proposal (e.g. a concurrent foreground send)
        // rather than ignoring locks — migration actually spends these notes, so racing a locked
        // one would double-spend against whatever proposal is holding it.
        let received = self
            .wallet
            .select_unspent_notes(
                self.account,
                &[ShieldedPool::Orchard],
                target,
                &[],
                LockFilter::Policy(&LockedInputPolicy::Exclude),
            )
            .map_err(|e| anyhow::anyhow!("selecting spendable Orchard notes failed: {e}"))?;
        let mut notes: Vec<SpendableNote> = received
            .orchard()
            .iter()
            .map(|rn| {
                let note = *rn.note();
                let value = note.value().inner();
                (note, rn.note_commitment_tree_position(), value)
            })
            .collect();
        notes.sort_by_key(|(_, pos, _)| *pos);
        Ok(notes)
    }
}

impl<'a, W> MigrationBackend for Backend<'a, W>
where
    W: WalletRead<AccountId = AccountUuid> + InputSource<AccountId = AccountUuid>,
    <W as WalletRead>::Error: std::error::Error + Send + Sync + 'static,
    <W as InputSource>::Error: std::error::Error + Send + Sync + 'static,
{
    type Error = EngineError;

    fn spendable_orchard_note_values(&self) -> Result<Vec<Zatoshis>, Self::Error> {
        self.spendable_orchard()?
            .into_iter()
            .enumerate()
            .map(|(i, (_, _, value))| {
                Zatoshis::from_u64(value)
                    .map_err(|_| anyhow::anyhow!("spendable note {i} has an invalid value"))
            })
            .collect()
    }

    fn chain_tip_height(&self) -> Result<BlockHeight, Self::Error> {
        self.wallet
            .chain_height()
            .map_err(|e| anyhow::anyhow!("chain height lookup failed: {e}"))?
            .ok_or_else(|| anyhow::anyhow!("wallet has no chain tip yet"))
    }

    /// Read off the wallet's own anchor retention grid (configured per network by
    /// `crate::anchor_retention_interval`) rather than chosen here, so a transfer can only be
    /// anchored to a boundary whose checkpoint the wallet actually keeps. The delay distributions
    /// are scaled from that same grid, which reproduces the ZIP 318 schedule exactly at the ZIP 318
    /// interval and compresses it proportionally on a test network.
    fn scheduling_params(&self) -> SchedulingParams {
        SchedulingParams::new_with_default_distributions(self.wallet.anchor_retention_interval())
    }
}

impl<'a, W> MigrationCrypto for Backend<'a, W>
where
    W: WalletRead<AccountId = AccountUuid> + InputSource<AccountId = AccountUuid>,
    <W as WalletRead>::Error: std::error::Error + Send + Sync + 'static,
    <W as InputSource>::Error: std::error::Error + Send + Sync + 'static,
{
    type Error = EngineError;

    /// Derived from the account's stored Orchard UFVK (not from `self.usk`) so this works whether
    /// or not a spending key was provided — matches the old `zcash_pool_migration` crate's
    /// `account_orchard_fvk` helper.
    fn orchard_fvk(&self) -> Result<FullViewingKey, Self::Error> {
        let account = self
            .wallet
            .get_account(self.account)
            .map_err(|e| anyhow::anyhow!("account lookup failed: {e}"))?
            .ok_or_else(|| anyhow::anyhow!("unknown account"))?;
        account
            .ufvk()
            .and_then(|ufvk| ufvk.orchard())
            .cloned()
            .ok_or_else(|| anyhow::anyhow!("account has no Orchard full viewing key"))
    }

    /// The account's ZIP 32 derivation as the wallet records it, or `None` for an account held
    /// only as a viewing key. The builders stamp this onto every spend still awaiting a
    /// signature, which is how the Keystone signer recognizes those spends as this account's;
    /// returning it unconditionally (rather than only when signing is delegated) keeps the
    /// in-process and hardware-wallet paths producing identical PCZTs.
    fn account_derivation(&self) -> Result<Option<AccountDerivation>, Self::Error> {
        Ok(self
            .wallet
            .get_account(self.account)
            .map_err(|e| anyhow::anyhow!("account lookup failed: {e}"))?
            .and_then(|account| {
                account
                    .source()
                    .key_derivation()
                    .map(AccountDerivation::from)
            }))
    }

    fn resolve_wallet_note(&self, index: usize) -> Result<OrchardNote, Self::Error> {
        let notes = self.spendable_orchard()?;
        let &(note, _, _) = notes
            .get(index)
            .ok_or_else(|| anyhow::anyhow!("no spendable note at index {index}"))?;
        Ok(note)
    }

    fn sign(&self, pczt: pczt::Pczt) -> Result<pczt::Pczt, Self::Error> {
        let usk = self
            .usk
            .as_ref()
            .ok_or_else(|| anyhow::anyhow!("no spending key available for in-process signing"))?;
        let ask = SpendAuthorizingKey::from(usk.orchard());
        zcash_pool_migration::build::sign_pczt(pczt, &ask)
            .map_err(|e| anyhow::anyhow!("signing the migration PCZT failed: {e:?}"))
    }
}

impl<'a, W> PoolMigrationRead for Backend<'a, W>
where
    W: WalletRead<AccountId = AccountUuid> + InputSource<AccountId = AccountUuid>,
    <W as WalletRead>::Error: std::error::Error + Send + Sync + 'static,
    <W as InputSource>::Error: std::error::Error + Send + Sync + 'static,
{
    type Error = EngineError;

    fn get_migration(&self) -> Result<Option<MigrationState>, Self::Error> {
        self.store
            .get_migration()
            .map_err(|e| anyhow::anyhow!("reading persisted migration failed: {e:?}"))
    }

    fn check_step_satisfiability(
        &self,
        tx: &MigrationTransaction,
        settle: ReorgSettleDepth,
    ) -> Result<StepSatisfiability, Self::Error> {
        self.store
            .check_step_satisfiability(tx, settle)
            .map_err(|e| anyhow::anyhow!("checking step satisfiability failed: {e:?}"))
    }

    fn mined_height(&self, txid: TxId) -> Result<Option<BlockHeight>, Self::Error> {
        self.store
            .mined_height(txid)
            .map_err(|e| anyhow::anyhow!("reading mined height failed: {e:?}"))
    }
}

impl<'a, W> PoolMigrationWrite for Backend<'a, W>
where
    W: WalletRead<AccountId = AccountUuid> + InputSource<AccountId = AccountUuid>,
    <W as WalletRead>::Error: std::error::Error + Send + Sync + 'static,
    <W as InputSource>::Error: std::error::Error + Send + Sync + 'static,
{
    fn replace_migration(&mut self, state: &MigrationState) -> Result<(), Self::Error> {
        self.store
            .replace_migration(state)
            .map_err(|e| anyhow::anyhow!("persisting migration failed: {e:?}"))
    }

    fn update_transaction(
        &mut self,
        id: MigrationTransferId,
        state: MigrationTxState,
    ) -> Result<(), Self::Error> {
        self.store
            .update_transaction(id, state)
            .map_err(|e| anyhow::anyhow!("updating migration transaction failed: {e:?}"))
    }

    fn store_proved_transaction(
        &mut self,
        state: &mut MigrationState,
        proven: zcash_pool_migration::engine::ProvedTransaction,
    ) -> Result<(), Self::Error> {
        self.store
            .store_proved_transaction(state, proven)
            .map_err(|e| anyhow::anyhow!("storing proved transaction failed: {e:?}"))
    }
}

/// Builds an ordinary send-max proposal sweeping every spendable Orchard note into the account's
/// own Ironwood receiver — bypassing `zcash_pool_migration` entirely. Unlike AUTOMATIC
/// mode's `plan_migration`/`commit_preparation`/`commit_or_reuse` path, this function never reads
/// or writes the persisted `MigrationState`: there is nothing to reconcile, no `is_immediate`
/// flag, no consumed-run bookkeeping, because the engine's `InProgress`/`Complete` derivation
/// (which only ever looks at `PoolMigrationRead::get_migration`) is simply never invoked for an
/// immediate run. IMMEDIATE is a synchronous, foreground, user-driven send — behaviorally
/// identical to an ordinary send once this proposal exists; the caller is expected to build/sign/
/// submit it exactly like any other `propose_transfer` result (see `migration.rs`'s
/// `proposeImmediateSendMaxNative`, which encodes the returned `Proposal` with the same
/// `proto::proposal::Proposal::from_standard_proposal` path an ordinary send already uses).
///
/// The destination is the account's own internal Ironwood receiver. Ironwood shares the Orchard
/// receiver encoding end to end (confirmed in `zcash_keys::address::Address::can_receive_as`:
/// `PoolType::Shielded(ShieldedPool::Orchard | ShieldedPool::Ironwood)` both match an Orchard
/// receiver) — there is no separate "Ironwood address" type, so deriving
/// `orchard_fvk.address_at(0u32, Scope::Internal)` and wrapping it as `Receiver::Orchard` before
/// encoding to a `ZcashAddress` is both correct and exactly how
/// `zcash_pool_migration::build::build_transfer_pczt` derives a migration transfer's own
/// crossing destination (its `recipient = orchard_fvk.address_at(0u32, Scope::Internal)`) — this
/// reuses that same derivation, not a second one.
pub fn propose_immediate_send_max(
    params: &Network,
    wallet: &mut Wallet,
    account: AccountUuid,
) -> anyhow::Result<Proposal<StandardFeeRule, <Wallet as InputSource>::NoteRef>> {
    let orchard_fvk = wallet
        .get_account(account)
        .map_err(|e| anyhow::anyhow!("account lookup failed: {e}"))?
        .ok_or_else(|| anyhow::anyhow!("unknown account"))?
        .ufvk()
        .and_then(|ufvk| ufvk.orchard())
        .cloned()
        .ok_or_else(|| anyhow::anyhow!("account has no Orchard full viewing key"))?;

    let ironwood_receiver = orchard_fvk.address_at(0u32, Scope::Internal);
    let recipient = Receiver::Orchard(ironwood_receiver).to_zcash_address(params.network_type());

    propose_send_max_transfer::<_, _, _, Infallible>(
        wallet,
        params,
        account,
        &[ShieldedPool::Orchard],
        &StandardFeeRule::Zip317,
        recipient,
        None, // no memo
        MaxSpendMode::MaxSpendable,
        ConfirmationsPolicy::default(),
        &LockedInputPolicy::Exclude,
        None, // no note locking for the immediate sweep itself
    )
    .map_err(|e| anyhow::anyhow!("Error proposing immediate send-max: {:?}", e))
}
