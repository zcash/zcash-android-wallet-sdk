# Module zcash-android-sdk-slipstream

The Slipstream sync engine for the Zcash Android SDK. It backs `SlipstreamSynchronizer`, an
alternative implementation of the SDK's `CloseableSynchronizer` contract that delegates block
scanning and wallet persistence to the Slipstream engine's native surface in
`libzcashwalletsdk.so`.

# Package com.zodl.slipstream

Entry points of the engine: `SlipstreamSynchronizer` and the models describing its state.
