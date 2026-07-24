use std::num::NonZeroUsize;
use std::path::PathBuf;

use anyhow::anyhow;
use bitflags::bitflags;
use rand::rngs::OsRng;
use tracing::debug;

use zcash_client_backend::{
    address::UnifiedAddress,
    data_api::wallet::input_selection::GreedyInputSelector,
    fees::{DustOutputPolicy, SplitPolicy, StandardFeeRule, zip317::MultiOutputChangeStrategy},
    keys::{ReceiverRequirement, ReceiverRequirementError, UnifiedAddressRequest},
    tor::DormantMode,
};
use zcash_client_sqlite::{FsBlockDb, WalletDb, util::SystemClock};
use zcash_protocol::{
    ShieldedPool,
    consensus::{
        BlockHeight, Network,
        Network::{MainNetwork, TestNetwork},
        NetworkType, Parameters,
    },
    memo::MemoBytes,
    value::Zatoshis,
};

mod tor;
mod utils;
mod zcash_jni;

#[cfg(debug_assertions)]
fn print_debug_state() {
    debug!("WARNING! Debugging enabled! This will likely slow things down 10X!");
}

#[cfg(not(debug_assertions))]
fn print_debug_state() {
    debug!("Release enabled (congrats, this is NOT a debug build).");
}

fn wallet_db<P: Parameters>(
    path: PathBuf,
    params: P,
) -> anyhow::Result<WalletDb<rusqlite::Connection, P, SystemClock, OsRng>> {
    WalletDb::for_path(path, params, SystemClock, OsRng)
        .map_err(|e| anyhow!("Error opening wallet database connection: {}", e))
}

fn block_db(path: PathBuf) -> anyhow::Result<FsBlockDb> {
    FsBlockDb::for_path(path)
        .map_err(|e| anyhow!("Error opening block source database connection: {:?}", e))
}

bitflags! {
    /// A set of bitflags used to specify the types of receivers a unified address can contain.
    #[derive(Clone, Copy, Debug, PartialEq, Eq)]
    struct ReceiverFlags: u32 {
        /// The requested address can receive transparent p2pkh outputs.
        const P2PKH = 0b00000001;
        /// The requested address can receive Sapling outputs.
        const SAPLING = 0b00000100;
        /// The requested address can receive Orchard outputs.
        const ORCHARD = 0b00001000;
    }
}

impl ReceiverFlags {
    fn address_request(&self) -> Result<UnifiedAddressRequest, ReceiverRequirementError> {
        UnifiedAddressRequest::custom(
            if self.contains(ReceiverFlags::ORCHARD) {
                ReceiverRequirement::Require
            } else {
                ReceiverRequirement::Omit
            },
            if self.contains(ReceiverFlags::SAPLING) {
                ReceiverRequirement::Require
            } else {
                ReceiverRequirement::Omit
            },
            if self.contains(ReceiverFlags::P2PKH) {
                ReceiverRequirement::Require
            } else {
                ReceiverRequirement::Omit
            },
        )
    }
}

fn zip317_helper<DbT>(
    change_memo: Option<MemoBytes>,
) -> (
    MultiOutputChangeStrategy<StandardFeeRule, DbT>,
    GreedyInputSelector<DbT>,
) {
    (
        MultiOutputChangeStrategy::new(
            StandardFeeRule::Zip317,
            change_memo,
            ShieldedPool::Orchard,
            DustOutputPolicy::default(),
            SplitPolicy::with_min_output_value(
                NonZeroUsize::new(4).expect("4 is nonzero"),
                Zatoshis::const_from_u64(1000_0000),
            ),
        ),
        GreedyInputSelector::new(),
    )
}

//
// Utility functions
//

fn parse_protocol(code: i32) -> anyhow::Result<ShieldedPool> {
    // The codes below must follow zcash_client_sqlite's own pool-type encoding:
    // https://github.com/zcash/librustzcash/blob/main/zcash_client_sqlite/src/wallet/encoding.rs#L42
    match code {
        2 => Ok(ShieldedPool::Sapling),
        3 => Ok(ShieldedPool::Orchard),
        4 => Ok(ShieldedPool::Ironwood),
        _ => Err(anyhow!("Shielded protocol not recognized: {code}")),
    }
}

fn parse_network(value: u32) -> anyhow::Result<Network> {
    match value {
        0 => Ok(TestNetwork),
        1 => Ok(MainNetwork),
        _ => Err(anyhow!(
            "Invalid network type: {}. Expected either 0 or 1 for Testnet or Mainnet, respectively.",
            value
        )),
    }
}

fn parse_optional_height(value: i64) -> anyhow::Result<Option<BlockHeight>> {
    Ok(match value {
        -1 => None,
        _ => Some(BlockHeight::try_from(value)?),
    })
}

struct UnifiedAddressParser((NetworkType, UnifiedAddress));

impl zcash_address::TryFromAddress for UnifiedAddressParser {
    type Error = anyhow::Error;

    fn try_from_unified(
        net: NetworkType,
        data: zcash_address::unified::Address,
    ) -> Result<Self, zcash_address::ConversionError<Self::Error>> {
        data.try_into()
            .map(|ua| (net, ua))
            .map(UnifiedAddressParser)
            .map_err(|e| anyhow!("Invalid Unified Address: {}", e).into())
    }
}

fn parse_tor_dormant_mode(value: u32) -> anyhow::Result<DormantMode> {
    match value {
        0 => Ok(DormantMode::Normal),
        1 => Ok(DormantMode::Soft),
        _ => Err(anyhow!(
            "Invalid Tor dormant mode: {value}. Expected either 0 for Normal or 1 for Soft."
        )),
    }
}
