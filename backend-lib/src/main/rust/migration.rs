//! Orchard to Ironwood pool migration.
//!
//! The generic capability this builds on lives upstream:
//! [`propose_send_max_transfer`] already spends the entire spendable balance of
//! a caller-chosen set of shielded pools to a single recipient, computing the
//! fee so that nothing is left over. This module does not reimplement any of
//! that, nor does it expose the general form. It pins every parameter that
//! makes the call a migration, and that single instance is all the SDK offers.
//!
//! The "no remainder" property is the reason a migration needs send-max rather
//! than an ordinary transfer: the caller cannot compute the right amount
//! themselves, because the fee depends on which inputs the selector picks.

use anyhow::anyhow;
use rand::rngs::OsRng;
use zcash_address::{ToAddress, ZcashAddress, unified, unified::Encoding as _};
use zcash_client_backend::{
    data_api::{
        Account as _, InputSource, MaxSpendMode, WalletRead,
        wallet::{ConfirmationsPolicy, propose_send_max_transfer},
    },
    fees::StandardFeeRule,
    proposal::Proposal,
};
use zcash_client_sqlite::{AccountUuid, WalletDb, util::SystemClock};
use zcash_protocol::{
    ShieldedPool,
    consensus::{Network, NetworkUpgrade, Parameters},
    memo::MemoBytes,
};

/// The wallet database as the JNI layer holds it.
type Db = WalletDb<rusqlite::Connection, Network, SystemClock, OsRng>;

type MigrationProposal = Proposal<StandardFeeRule, <Db as InputSource>::NoteRef>;

/// Proposes migrating the account's Orchard balance into the Ironwood pool.
///
/// Ironwood needs no address type of its own: it shares the Orchard receiver
/// encoding, and once Ironwood is active `zcash_client_backend` represents a
/// payment to an Orchard receiver as an Ironwood-pool output, which the
/// transaction builder places in the Ironwood bundle. Sending the maximum from
/// Orchard to the account's own internal Orchard receiver is therefore exactly
/// the pool crossing, with nothing left behind in Orchard.
///
/// Uses [`MaxSpendMode::Everything`], so the proposal fails rather than
/// migrating only part of the balance. `MaxSpendable` would silently skip any
/// Orchard note that is not yet spendable (`zcash_client_sqlite` maps the two
/// modes to `Ok(None)` and `Err(IneligibleNotes)` respectively for such a
/// note), leaving funds in a pool the wallet is trying to leave and reporting
/// success. The eligibility check covers only the pools being spent, so an
/// unconfirmed Sapling note does not block this.
///
/// Fails if NU6.3 is not yet active. Before activation the identical call is
/// still accepted by the input selector, but it constructs an *Orchard*-pool
/// output rather than an Ironwood one (`input_selection.rs` picks the pool on
/// `ironwood_active_at`), so it would be a self-send that migrates nothing and
/// costs a fee. Refusing beats silently doing that.
///
/// **This is not a private migration.** It produces one transaction whose
/// value is the account's entire Orchard balance, which any observer can read
/// off the chain. Splitting a crossing into less-identifying denominations is
/// a separate mechanism; this deliberately does not attempt it.
pub(crate) fn propose_orchard_to_ironwood(
    db_data: &mut Db,
    network: &Network,
    account: AccountUuid,
) -> anyhow::Result<MigrationProposal> {
    // Before NU6.3 an Orchard-receiver payment is built as an Orchard output,
    // so this would be a fee-costing no-op rather than a migration. After it,
    // the Orchard turnstile (a consensus rule) forbids adding value to the
    // Orchard pool at all, which is what makes the crossing necessary.
    let chain_tip = db_data
        .chain_height()
        .map_err(|e| anyhow!("Error reading the chain tip: {}", e))?
        .ok_or_else(|| anyhow!("Wallet has not yet scanned any blocks."))?;
    match network.activation_height(NetworkUpgrade::Nu6_3) {
        Some(h) if chain_tip >= h => (),
        _ => {
            return Err(anyhow!(
                "Ironwood (NU6.3) is not active yet; there is nothing to migrate to."
            ));
        }
    }

    let orchard_fvk = db_data
        .get_account(account)
        .map_err(|e| anyhow!("Error looking up account: {}", e))?
        .ok_or_else(|| anyhow!("Unknown account."))?
        .ufvk()
        .and_then(|ufvk| ufvk.orchard())
        .cloned()
        .ok_or_else(|| anyhow!("Account has no Orchard full viewing key."))?;

    // The internal scope is the account's own change address, so the funds
    // stay with the account rather than being exposed as an external payment.
    let receiver = orchard_fvk.address_at(0u32, orchard::keys::Scope::Internal);
    let recipient = ZcashAddress::from_unified(
        network.network_type(),
        unified::Address::try_from_items(vec![unified::Receiver::Orchard(
            receiver.to_raw_address_bytes(),
        )])
        .map_err(|e| anyhow!("Unable to construct the migration recipient: {}", e))?,
    );

    // Orchard only. Sapling and transparent funds are deliberately left where
    // they are: this migrates one pool, it is not a sweep of the wallet.
    let spend_pools = [ShieldedPool::Orchard];

    // Always ZIP 317, as everywhere else in this backend.
    let fee_rule = StandardFeeRule::Zip317;

    // A transfer to oneself has no counterparty to carry a memo for.
    let memo: Option<MemoBytes> = None;

    // Fail rather than migrate part of the balance. For a note that is not yet
    // spendable, `zcash_client_sqlite` maps `MaxSpendable` to `Ok(None)` and
    // `Everything` to `Err(IneligibleNotes)`, so `MaxSpendable` would skip such
    // notes silently and report a migration that in fact left funds in a pool
    // the wallet is trying to leave. The check covers only the pools being
    // spent, so an unconfirmed Sapling note does not block this.
    let mode = MaxSpendMode::Everything;

    // The wallet-wide default; this migration has no reason to be stricter or
    // more lax about confirmations than an ordinary send.
    let confirmations_policy = ConfirmationsPolicy::default();

    propose_send_max_transfer::<_, _, _, std::convert::Infallible>(
        db_data,
        network,
        account,
        &spend_pools,
        &fee_rule,
        recipient,
        memo,
        mode,
        confirmations_policy,
    )
    .map_err(|e| anyhow!("Error creating the migration proposal: {}", e))
}
