use super::*;

// Must match FFI_ROUND_PHASE_* constants in FfiVotingModels.kt.
const PHASE_INITIALIZED: u32 = 0;
const PHASE_HOTKEY_GENERATED: u32 = 1;
const PHASE_DELEGATION_CONSTRUCTED: u32 = 2;
const PHASE_DELEGATION_PROVED: u32 = 3;
const PHASE_VOTE_READY: u32 = 4;

#[derive(Serialize)]
pub(super) struct JsonRoundSummary {
    pub(super) round_id: String,
    pub(super) phase: u32,
    pub(super) snapshot_height: u64,
    pub(super) created_at: u64,
}

impl From<RoundSummary> for JsonRoundSummary {
    fn from(round: RoundSummary) -> Self {
        JsonRoundSummary {
            round_id: round.round_id,
            phase: round_phase_to_u32(round.phase),
            snapshot_height: round.snapshot_height,
            created_at: round.created_at,
        }
    }
}

pub(super) fn round_phase_to_u32(phase: RoundPhase) -> u32 {
    match phase {
        RoundPhase::Initialized => PHASE_INITIALIZED,
        RoundPhase::HotkeyGenerated => PHASE_HOTKEY_GENERATED,
        RoundPhase::DelegationConstructed => PHASE_DELEGATION_CONSTRUCTED,
        RoundPhase::DelegationProved => PHASE_DELEGATION_PROVED,
        RoundPhase::VoteReady => PHASE_VOTE_READY,
    }
}

#[derive(Serialize)]
pub(super) struct JsonVoteRecord {
    pub(super) proposal_id: u32,
    pub(super) bundle_index: u32,
    pub(super) choice: u32,
    pub(super) submitted: bool,
}

impl From<VoteRecord> for JsonVoteRecord {
    fn from(record: VoteRecord) -> Self {
        JsonVoteRecord {
            proposal_id: record.proposal_id,
            bundle_index: record.bundle_index,
            choice: record.choice,
            submitted: record.submitted,
        }
    }
}

pub(super) fn json_to_jstring<T: Serialize>(
    env: &mut JNIEnv<'_>,
    value: &T,
) -> anyhow::Result<jstring> {
    let s = serde_json::to_string(value).map_err(|e| anyhow!("JSON serialization error: {}", e))?;
    Ok(env.new_string(s)?.into_raw())
}
