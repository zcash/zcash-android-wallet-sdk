//! Adapter wiring the Android wallet database into `zcash_pool_migration_backend`'s
//! `MigrationBackend`/`MigrationCrypto`/`PoolMigrationRead`/`PoolMigrationWrite` traits.
//!
//! This is deliberately a separate, thinner adapter than `zcash_pool_migration_backend::wallet::
//! WalletMigration`: that type's constructor requires a `UnifiedSpendingKey` unconditionally
//! (its `orchard_fvk()` is derived from the usk), but several JNI entry points in `migration.rs`
//! only ever plan or build unsigned PCZTs (no usk available at that call site — mirroring the old
//! `zcash_pool_migration` crate, which likewise derived the FVK from the account's stored UFVK,
//! not from a spending key). `Backend::usk` is therefore optional: every method needed for
//! planning/building unsigned PCZTs (`orchard_fvk`, `resolve_wallet_note`,
//! `spendable_orchard_note_values`, `chain_tip_height`) works without it; only `sign()` requires
//! one, and `zcash_pool_migration_backend::engine::build_preparation_unsigned` never calls it (only
//! `commit_preparation`'s in-process-signing path does).

use rusqlite::Connection;

use orchard::keys::{FullViewingKey, SpendAuthorizingKey};
use orchard::note::Note as OrchardNote;
use incrementalmerkletree::Position;
use zcash_client_backend::data_api::wallet::TargetHeight;
use zcash_client_backend::data_api::{Account, InputSource, WalletRead};
use zcash_client_backend::keys::UnifiedSpendingKey;
use zcash_client_sqlite::AccountUuid;
use zcash_protocol::ShieldedPool;
use zcash_protocol::consensus::BlockHeight;
use zcash_protocol::value::Zatoshis;

use zcash_pool_migration_backend::engine::{
    MigrationBackend, MigrationCrypto, MigrationState, MigrationTxId, MigrationTxState,
    PoolMigrationRead, PoolMigrationWrite,
};
use zcash_client_sqlite::pool_migration::orchard_ironwood::PoolMigrations;

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
    store: PoolMigrations<&'a mut Connection>,
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
        wallet: &'a W,
        account: AccountUuid,
        usk: Option<UnifiedSpendingKey>,
        conn: &'a mut Connection,
    ) -> Result<Self, EngineError> {
        let store = PoolMigrations::for_account(conn, account)
            .map_err(|e| anyhow::anyhow!("opening pool-migration store failed: {e:?}"))?;
        Ok(Self {
            wallet,
            account,
            usk,
            store,
        })
    }

    /// The account's spendable Orchard notes, exposed for `migration_finalize`'s witness lookup
    /// (matches by nullifier, computed against the account's FVK).
    pub(crate) fn spendable_orchard_notes(&self) -> Result<Vec<SpendableNote>, EngineError> {
        self.spendable_orchard()
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
        let received = self
            .wallet
            .select_unspent_notes(self.account, &[ShieldedPool::Orchard], target, &[])
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
        zcash_pool_migration_backend::build::sign_pczt(pczt, &ask)
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
        id: MigrationTxId,
        state: MigrationTxState,
    ) -> Result<(), Self::Error> {
        self.store
            .update_transaction(id, state)
            .map_err(|e| anyhow::anyhow!("updating migration transaction failed: {e:?}"))
    }
}
