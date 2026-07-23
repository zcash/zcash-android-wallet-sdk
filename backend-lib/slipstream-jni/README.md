# slipstream-jni

The Rust JNI binding for the Slipstream Zcash sync engine on Android. This crate compiles
to `libslipstream.so` (one per ABI) and is the Rust half of the
`com.zodl.slipstream:slipstream-android` AAR. It exports the JNI symbols
`Java_com_zodl_slipstream_SlipstreamNative_*`, binding the Slipstream C ABI
(`HOSTING.md` §4 in the engine repo) to JNI **function-for-function**, calling
`slipstream_core::ffi_handle` directly in Rust — the same module the C ABI wraps — so the
semantics are one derivation, byte-identical to iOS/macOS.

No `repr(C)` layout ever crosses into Kotlin: every model object is constructed
field-by-field via `env.new_object`. This is what makes the binding immune to a mid-struct
C-ABI break a future engine branch could introduce (the JNI crate is recompiled against the
new engine tag; Kotlin just sees a new nullable field).

## Contract version — v0.6.0

This crate binds the **published v0.6.0 contract surface**: the 9 functions of `HOSTING.md`
§4, the 14-field snapshot (§5), the 64-slot event ring (§6), the two SQL read-views (§7), and
provisioning (§8). Two additive natives — `initOnLoad` (logging/panic init) and `version`
(build stamp) — round out the class.

`setAlternateServers` is an engine **v0.7 fast-follow**, outside the v0.6.0 surface: a
clearly-marked extension stub sits at the end of `src/lib.rs`, and the contract lists it only
under forward-compat. When the AAR is built from an engine tag ≥ v0.7, uncomment the stub, add
the Kotlin `external fun`, and wire the corresponding config-merge in `start_session`.

Ironwood-era summary fields (`SlipstreamAccountBalance.ironwood`,
`SlipstreamWalletSummary.nextIronwoodSubtreeIndex`) are **forward-absorbed**: constructed as
`null` here, and wired from `balance.ironwood_balance()` / the summary index (both marked
inline in `src/summary.rs`) when built from an ironwood engine tag.

## Symbol map (all normative in the JNI binding contract §2)

| JNI export (`Java_com_zodl_slipstream_SlipstreamNative_`) | C ABI | Returns |
|---|---|---|
| `initOnLoad` | `zcashlc_init_on_load` (adapted) | void |
| `version` | *(additive)* | `String` |
| `open` | `zcashlc_slipstream_open` | `jlong` handle (`-1`/throw on failure) |
| `start` | `zcashlc_slipstream_start` | `Boolean` |
| `stop` | `zcashlc_slipstream_stop` | `Boolean` |
| `snapshot` | `zcashlc_slipstream_snapshot` | `SlipstreamSnapshot` |
| `drainEvents` | `zcashlc_slipstream_drain_events` | `SlipstreamEvent[]` (whole ring) |
| `walletSummary` | `zcashlc_slipstream_wallet_summary` | `SlipstreamWalletSummary?` |
| `notifyTxChange` | `zcashlc_slipstream_notify_tx_change` | `Boolean` |
| `restoreAnchor` | `zcashlc_slipstream_restore_anchor` | `SlipstreamRestoreAnchor` |
| `free` | `zcashlc_slipstream_free` | void |

The C memory-management twins (`…_free_restore_anchor`, `…_free_wallet_summary`) and the
last-error accessors are **absorbed**: the treestate crosses as a copied `jbyteArray`, the
summary as constructed Kotlin objects, and error text inside a thrown
`java.lang.RuntimeException`.

## Files

| File | Purpose |
|---|---|
| `Cargo.toml` | Own one-crate workspace; deps; the `[patch.crates-io]` mirror block (below). |
| `build.rs` | The x86_64-`linux-android` `__extenddftf2` SQLite link workaround. No-op on every other target. |
| `src/lib.rs` | The JNI marshalling layer: the handle wrapper, the `catch_unwind`/`unwrap_exc_or` twin, all exports, `initOnLoad`/`version`, and the v0.7 stub. |
| `src/summary.rs` | `walletSummary` object construction (the phase-resolving balance read) + the E-1 summary-cache rationing. |

## Building the AAR (`cargo-ndk` × 4 ABIs)

The full CI/build recipe (pinned tool versions, Gradle wiring, release workflow) ships with
the release tooling. In brief:

```bash
# cargo-ndk 3.5.4 (pinned); NDK r27 with ANDROID_NDK_HOME set.
cargo install cargo-ndk --version 3.5.4 --locked

# 4 ABIs, release, engine defaults, locked graph. RUSTFLAGS carries the mandatory
# 16 KB page-size link flag (Android 15/16 requires 16 KB-aligned .so's; NDK r27 does
# not apply it by default).
RUSTFLAGS="-C link-arg=-Wl,-z,max-page-size=16384" \
cargo ndk \
    -t arm64-v8a -t armeabi-v7a -t x86_64 -t x86 \
    --platform 27 \
    -o ../lib/src/main/jniLibs \
    build --release
```

Output lands in `android/lib/src/main/jniLibs/<abi>/libslipstream.so` (AGP's default
jniLibs source set — Gradle needs zero native config). `build.rs` supplies the x86_64
`clang_rt.builtins` link so the x86_64 ABI resolves `__extenddftf2`. The
`[lib] name = "slipstream"` makes the artifact `libslipstream.so` for
`System.loadLibrary("slipstream")`.

## Status — what is and isn't proven

- **Host `cargo check`: PASSES.** `cargo check --lib` completes with **exit 0, 0 errors,
  0 warnings** on `src/lib.rs`/`src/summary.rs`/`build.rs`, checked against the pinned engine
  (`slipstream-core 0.6.0` at `../../core`) and PUBLISHED librustzcash (`zcash_client_backend
  0.23` / `zcash_client_sqlite 0.21` / `zcash_protocol 0.9`) plus the engine's two vendored
  performance forks (`orchard 0.14`, `zcash_note_encryption 0.4.1`). `cargo metadata` resolves
  the full graph cleanly.
- **NOT built via cargo-ndk for any Android ABI, and never run on a device or emulator.**
  Every JNI descriptor string (the `com/zodl/slipstream/model/...` class paths + constructor
  signatures in `src/lib.rs`/`src/summary.rs`) is hand-derived against the JNI binding contract
  §4.2 — **not** `javap`-verified against the compiled Kotlin models. No JNI call has executed;
  the threading numbers in the contract are measured on iOS/macOS. The Kotlin model classes and
  `SlipstreamNative` declarations this crate constructs/serves live in the sibling adapter
  module; a CI `javap -s` cross-check of every descriptor row is the gate before the first
  device build (the JNI binding contract §11).

## Dependency & `[patch.crates-io]` model — READ before touching `Cargo.toml`

This crate is its **own** Cargo workspace (it must not join the monorepo workspace that hosts
the engine crates). It depends on `slipstream-core = { path = "../../core" }`, whose
`workspace = true` deps resolve against the **engine's own workspace root** — *not* against
this crate.

Because a `[patch]` only applies from the workspace root being built, the engine root's patch
set does **not** apply here transitively. The `[patch.crates-io]` block in `Cargo.toml`
therefore **mirrors the engine root's** two vendored performance-fork patches
(`orchard`, `zcash_note_encryption`), re-based to `../../vendor`. Everything else — the
librustzcash family — comes from crates.io at the published versions the engine locks
(`zcash_client_backend 0.23`, `zcash_client_sqlite 0.21`, `zcash_protocol 0.9`). **Any change
to the engine root's `[patch.crates-io]` must be mirrored here in the same commit** — a drift
is a silent wrong-source build (for example: resolving the note-encryption dependency to a
published patch release instead of the vendored fork, which the pinned `Cargo.lock` prevents).

The engine's C-ABI CONTRACT is v0.6.0 regardless of the librustzcash generation; the
generation is an orthogonal build axis governed by the schema-lockstep pin rule (this AAR and
the shipping Android SDK lock the same `zcash_client_sqlite 0.21` generation, so a wallet DB
is byte-compatible between the two engines).

## Deferred ports and deviations from the iOS/macOS reference (all honest gaps)

1. **Tor `dangerously_trust_everyone = false`.** The iOS/macOS C layer sets it via
   `cfg!(target_os = "ios")` (false on Android regardless); this binding sets it explicitly
   false in `start_session` and `restoreAnchor`. Confirm the flag's exact meaning with the
   engine owner before the first Tor-enabled release (the JNI binding contract §11).
2. **JNI local-reference table.** `walletSummary` constructs ~5 local refs per account; a
   production port should wrap each account iteration in `env.with_local_frame` for
   many-account wallets (`src/summary.rs` has the note).

The `walletSummary` E-1 rationing **IS** ported (`src/summary.rs`): the expensive
`get_wallet_summary` walk is served from a handle-owned cache and re-run only on a range
boundary / state change / 2 s idle TTL, so hosts may call `walletSummary` every tick. The
engine's internal performance tuning is owned by the engine and not exposed here.

## Threading (the one hard rule)

Never pass one handle to two native calls concurrently. The Kotlin side enforces this with a
single dedicated dispatcher thread (`SlipstreamDispatchers.SLIPSTREAM_IO`, the JNI binding
contract §5). `open` creates a 4-worker tokio runtime inside the handle and scanning uses a
rayon pool, so the single control thread never bottlenecks sync. `start` and `stop` are
**bounded real waits** (task-join + writer-drain, worst ~20 s) — call them off the Android
main thread.

## Cross-references

- The **JNI binding contract** (shipped with each artifact release) — the normative binding
  spec (symbol table, descriptors, Kotlin models, error mapping). **The authority for every
  signature this crate constructs.**
- `HOSTING.md` (shipped with each artifact release) — the behavioral C-ABI contract this binds.
