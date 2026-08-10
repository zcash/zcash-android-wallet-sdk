# CHP (Coinholder Polling / shielded voting) feature-module extraction — design

This is a pointer doc. The full design (covering both `zcash-android-wallet-sdk` and
`zodl-android`) lives in `zodl-android`'s
`docs/superpowers/specs/2026-08-10-chp-feature-module-extraction-design.md`, written the same day.
That is the single source of truth — this file exists only so the design is discoverable from
this repo too.

## SDK-side summary

Add a `VotingSdk` public interface (in `cash.z.ecc.android.sdk`, `VotingSdk.new(...)` factory)
implemented by a new `VotingSdkImpl`, mirroring the existing `MigrationSdk` /
`OrchardMigrationSdkImpl` three-layer shape. `VotingSdkImpl` delegates to the existing
`TypesafeVotingBackend`/`Impl` (unchanged, stays `internal`). `backend-lib`'s `VotingRustBackend`
becomes reachable from exactly one place — `TypesafeVotingBackendImpl` — closing the current
layering violation where the app reaches straight into it, bypassing the SDK's public API
entirely.

No new Gradle module on this side (considered and rejected — see the full design for why), no
change to the Rust `voting/` crate itself.

See the full design doc in `zodl-android` for the app-side module extraction, decisions record,
testing plan, and phasing.
