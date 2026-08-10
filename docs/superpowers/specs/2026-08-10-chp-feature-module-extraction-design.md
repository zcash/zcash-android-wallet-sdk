# CHP (Coinholder Polling / shielded voting) feature-module extraction — design

This is a pointer doc. The full design (covering both `zcash-android-wallet-sdk` and
`zodl-android`) lives in `zodl-android`'s
`docs/superpowers/specs/2026-08-10-chp-feature-module-extraction-design.md`, written the same day.
That is the single source of truth — this file exists only so the design is discoverable from
this repo too.

## SDK-side summary (revised 2026-08-10 after Fable adversarial review)

Add a `VotingSdk` public interface (in `cash.z.ecc.android.sdk`) implemented by a new
`VotingSdkImpl`, in the spirit of the existing `MigrationSdk`/`OrchardMigrationSdkImpl` layering —
**but not a mechanical copy of it**: voting is stateful (handle registry, two-step
open/setWalletId protocol) where migration is stateless per-call, so `VotingSdk` exposes an
object-handle session (`VotingSdk.openDb(...): VotingDbSession`, name TBD) instead of porting the
existing `Long`-handle protocol. `VotingSdkImpl` delegates to `TypesafeVotingBackend`/`Impl`,
which is **not unchanged** — it gains `delegationPhases`/`resetVotingSessionState`, currently only
present on the raw JNI `VotingRustBackend.VotingDb`, plus test coverage for both.

`VotingSdk` exposes typed public SDK models distinct from the app's own `ui-lib` model types — the
app-side JSON storage schema, UI-facing `RoundPhase` collapsing, and the `BALLOT_DIVISOR_ZATOSHI`
governance constant stay app-side in a thin adapter, not absorbed into the SDK's public API.

Enforcement that `backend-lib`'s `VotingRustBackend`/`Jni*` classes are reachable from exactly
`TypesafeVotingBackendImpl` is **not** a Kotlin `internal` visibility change (impossible across
Gradle modules — `sdk-lib` and `backend-lib` are different modules, no friend-modules in Kotlin).
Confirmed with the user: enforcement is dropping the `libs.zcash.sdk.backend` JNI dependency from
the app's `feature-voting`/`ui-lib` classpath once fully migrated, making an out-of-bounds import
a compile error rather than a convention.

No new Gradle module on this side (considered and rejected — see the full design for why), no
change to the Rust `voting/` crate itself.

See the full design doc in `zodl-android` for the app-side module extraction, the full file
inventory, decisions record, testing plan, and phasing (including why the SDK work has to land
and release/be locally overridden before the app-side move can start).
