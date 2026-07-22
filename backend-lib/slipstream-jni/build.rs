//! Build script for `slipstream-jni`.
//!
//! Its ONLY job is the x86_64-`linux-android` `__extenddftf2` link workaround (below). It is
//! a NO-OP on every other target — including the host `cargo check` the orchestrator runs —
//! because it returns early unless `target_arch == "x86_64" && target_os == "android"`.
//!
//! Rationale: this is the published Android SDK backend-lib's production `build.rs` pattern,
//! behavior-identical, so the crate builds under both cargo-ndk and the mozilla
//! rust-android-gradle plugin.

use std::env;
use std::ffi::OsStr;
use std::path::PathBuf;
use std::process::Command;

/// Clang major shipped by NDK 27.0.12077973 (same fallback as the ASDK backend-lib build.rs).
const ANDROID_NDK_CLANG_VERSION: &str = "17";

fn main() {
    setup_x86_64_android_workaround();
}

/// Statically link `clang_rt.builtins` on `x86_64-linux-android` to provide `__extenddftf2`
/// (rust-lang/rust#109717; mozilla/application-services#5442 — ASDK ships this pattern).
///
/// Why: Slipstream pins the same `rusqlite 0.37` / `libsqlite3-sys 0.35` as the upstream
/// backend, so the x86_64 link hits the same missing builtin their `backend-lib/build.rs`
/// solves — NDK r23+ dropped `libgcc`; Rust's `compiler-builtins` omits `__extenddftf2` on
/// `x86_64-linux-android` (aarch64 has it); SQLite needs it for `LONGDOUBLE_TYPE`. Symptom if
/// absent: an x86_64-only `undefined reference to '__extenddftf2'` at link time.
fn setup_x86_64_android_workaround() {
    let target_os = env::var("CARGO_CFG_TARGET_OS").expect("CARGO_CFG_TARGET_OS not set");
    let target_arch = env::var("CARGO_CFG_TARGET_ARCH").expect("CARGO_CFG_TARGET_ARCH not set");
    if !(target_arch == "x86_64" && target_os == "android") {
        return;
    }

    let cc = if let Some(cc) = env::var_os("RUST_ANDROID_GRADLE_CC") {
        PathBuf::from(cc) // driven by the mozilla rust-android-gradle plugin
    } else {
        // cargo-ndk / CLI path: locate NDK clang via ANDROID_NDK_HOME.
        let ndk = env::var_os("ANDROID_NDK_HOME").expect("ANDROID_NDK_HOME not set");
        let host = match env::consts::OS {
            "linux" => "linux",
            "macos" => "darwin",
            "windows" => "windows",
            _ => panic!("unsupported build host"),
        };
        let mut cc = PathBuf::from(ndk);
        cc.extend(["toolchains", "llvm", "prebuilt"]);
        cc.push(format!("{host}-x86_64"));
        cc.push("bin");
        cc.push("clang");
        cc.set_extension(env::consts::EXE_EXTENSION);
        cc
    };

    let mut link_path = cc.ancestors().nth(2).expect("known path shape").join("lib");
    link_path.extend(["clang".into(), get_clang_version(&cc), "lib".into(), "linux".into()]);
    if link_path.exists() {
        println!("cargo:rustc-link-search={}", link_path.display());
        println!("cargo:rustc-link-lib=static=clang_rt.builtins-x86_64-android");
    } else {
        panic!("clang_rt path {} does not exist", link_path.display());
    }
}

fn get_clang_version(cc: impl AsRef<OsStr>) -> String {
    Command::new(cc)
        .arg("--version")
        .output()
        .ok()
        .and_then(|o| String::from_utf8(o.stdout).ok())
        .as_deref()
        .and_then(|s| s.split_once("clang version "))
        .and_then(|(_, s)| s.split_once('.'))
        .map(|(major, _)| major.to_string())
        .unwrap_or_else(|| ANDROID_NDK_CLANG_VERSION.into())
}
