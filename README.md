[![license](https://img.shields.io/github/license/zcash/zcash-android-wallet-sdk.svg?maxAge=2592000&style=plastic)](https://github.com/zcash/zcash-android-wallet-sdk/blob/master/LICENSE)
![Maven Central](https://img.shields.io/maven-central/v/cash.z.ecc.android/zcash-android-sdk?color=success&style=plastic)

This is a beta build and is currently under active development. Please be advised of the following:

- This code currently is not audited by an external security auditor, use it at your own risk
- The code **has not been subjected to thorough review** by engineers at the Electric Coin Company
- We **are actively changing** the codebase and adding features where/when needed

🔒 Security Warnings

- The Zcash Android Wallet SDK is experimental and a work in progress. Use it at your own risk.
- Developers using this SDK must familiarize themselves with the current [threat
  model](https://zcash.readthedocs.io/en/latest/rtd_pages/wallet_threat_model.html), especially the known weaknesses described there.

---

# Zcash Android SDK
This lightweight SDK connects Android to Zcash, allowing third-party Android apps to send and receive shielded transactions easily, securely and privately.

Different sections of this repository documentation are oriented to different roles, specifically Consumers (you want to use the SDK) and Maintainers (you want to modify the SDK).

Note: This SDK is designed to work with [lightwalletd](https://github.com/zcash-hackworks/lightwalletd).  As either a consumer of the SDK or developer, you'll need a lightwalletd instance to connect to.  These servers are maintained by the Zcash community.

Note: Because we have not deployed a non-beta release of the SDK yet, version numbers currently follow a variation of [semantic versioning](https://semver.org/).  Generally a non-breaking change will increment the beta number while a breaking change will increment the minor number.  1.0.0-beta01 -> 1.0.0-beta02 is non-breaking, while 1.0.0-beta01 -> 1.1.0-beta01 is breaking.  This is subject to change.

# Zcash Networks
"mainnet" (main network) and "testnet" (test network) are terms used in the blockchain ecosystem to describe different blockchain networks.  Mainnet is responsible for executing actual transactions within the network and storing them on the blockchain. In contrast, the testnet provides an alternative environment that mimics the mainnet's functionality to allow developers to build and test projects without needing to facilitate live transactions or the use of cryptocurrencies, for example.

The Zcash testnet is an alternative blockchain that attempts to mimic the mainnet (main Zcash network) for testing purposes. Testnet coins are distinct from actual ZEC and do not have value. Developers and users can experiment with the testnet without having to use valuable currency. The testnet is also used to test network upgrades and their activation before committing to the upgrade on the main Zcash network. For more information on how to add testnet funds visit [Testnet Guide](https://zcash.readthedocs.io/en/latest/rtd_pages/testnet_guide.html) or go right to the [Testnet Faucet](https://faucet.zecpages.com/).

This SDK supports both mainnet and testnet.  Further details on switching networks are covered in the remaining documentation.

# Consumers
If you're a developer consuming this SDK in your own app, see [Consumers.md](docs/Consumers.md) for a discussion of setting up your app to consume the SDK and leverage the public APIs.

A primitive example to exercise the SDK exists in this repo, under [Demo App](demo-app).

[Secant Sample Wallet](https://github.com/zcash/secant-android-wallet) is a more comprehensive sample application.

# Maintainers and Contributors
If you're building the SDK from source or modifying the SDK:
 * [Setup.md](docs/Setup.md) to configure building from source
 * [Architecture.md](docs/Architecture.md) to understand the high level architecture of the code
 * [CI.md](docs/CI.md) to understand the Continuous Integration build scripts
 * [PUBLISHING.md](docs/PUBLISHING.md) to understand our deployment process

Note that we aim for the main branch of this repository to be stable and releasable.  We continuously deploy snapshot builds from `release/**` branches, then manually deploy release builds.  Our continuous deployment of snapshots implies two things:
 * A pull request containing API changes should also bump the version
 * Each pull request should be stable and ready to be consumed, to the best of your knowledge.  Gating unstable functionality behind a flag is perfectly acceptable

## Known Issues
1. Intel-based machines may have trouble building in Android Studio.  The workaround is to add the following line to `~/.gradle/gradle.properties`: `ZCASH_IS_DEPENDENCY_LOCKING_ENABLED=false`
1. During builds, a warning will be printed that says "Unable to detect AGP versions for included builds. All projects in the build should use the same AGP version."  This can be safely ignored.  The version under build-conventions is the same as the version used elsewhere in the application.

## Unstable Features
### Spend-before-Sync compact blocks synchronization algorithm
`CompactBlockProcessor` now processes compact blocks from the lightwalletd server in non-linear order with the 
**Spend-before-Sync** algorithm. This feature speeds up discovering the wallet's spendable balance. Please note that 
this new block synchronization algorithm is still under development.
