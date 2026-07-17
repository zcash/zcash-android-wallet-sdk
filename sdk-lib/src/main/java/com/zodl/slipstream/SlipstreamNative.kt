package com.zodl.slipstream

import com.zodl.slipstream.model.SlipstreamEvent
import com.zodl.slipstream.model.SlipstreamRestoreAnchor
import com.zodl.slipstream.model.SlipstreamSnapshot
import com.zodl.slipstream.model.SlipstreamWalletSummary

/**
 * Raw JNI surface over the Slipstream engine. One-to-one binding of the Slipstream C ABI
 * (`HOSTING.md` section 4, `FFI_JNI_CONTRACT.md` section 2). Every method that takes `handle`
 * MUST be called on the single Slipstream dispatcher
 * (see [com.zodl.slipstream.internal.SlipstreamDispatchers]) - never concurrently. All failures
 * surface as `java.lang.RuntimeException` (see the error mapping contract, `FFI_JNI_CONTRACT.md`
 * section 6).
 */
internal object SlipstreamNative {
    /** Contract version this binding implements (`HOSTING.md` as of engine v0.6.0). */
    const val CONTRACT_VERSION: String = "0.6.0"

    private var loaded = false

    /**
     * Loads libslipstream.so and runs process-global native init (logging, panic hook, rayon
     * pool). Idempotent and thread-safe; every entry point of the adapter calls this first.
     * logLevel: "error" | "warn" | "info" | "debug" | "trace" | "off".
     */
    @Synchronized
    fun ensureLoaded(logLevel: String = "warn") {
        if (!loaded) {
            System.loadLibrary("slipstream")
            initOnLoad(logLevel)
            loaded = true
        }
    }

    @JvmStatic
    private external fun initOnLoad(logLevel: String)

    /** Build stamp: "slipstream-android <aar-version> (engine <tag>)". */
    @JvmStatic
    external fun version(): String

    @JvmStatic
    external fun open(
        dbPath: String,
        serverHost: String,
        serverPort: Int,
        useTls: Boolean,
        networkId: Int,
        totalMemoryBytes: Long
    ): Long

    // v0.7 fast-follow (forward-compat; see FFI_JNI_CONTRACT.md section 3.4 / 9.1) - NOT part of
    // the v0.6.0 surface, and the matching native export is unbound at v0.6.0. Present from AAR
    // 0.7.x, absent from 0.6.x:
    // @JvmStatic external fun setAlternateServers(handle: Long, urisNewlineSeparated: String?): Boolean

    @JvmStatic
    external fun start(
        handle: Long,
        ufvk: String?,
        birthdayHeight: Long,
        torDir: String?
    ): Boolean

    @JvmStatic
    external fun stop(handle: Long): Boolean

    @JvmStatic
    external fun snapshot(handle: Long): SlipstreamSnapshot

    @JvmStatic
    external fun drainEvents(handle: Long): Array<SlipstreamEvent>

    @JvmStatic
    external fun walletSummary(
        handle: Long,
        trustedConfirmations: Int,
        untrustedConfirmations: Int,
        allowZeroConfShielding: Boolean
    ): SlipstreamWalletSummary?

    @JvmStatic
    external fun notifyTxChange(handle: Long): Boolean

    @JvmStatic
    external fun restoreAnchor(
        serverHost: String,
        serverPort: Int,
        useTls: Boolean,
        networkId: Int,
        intent: Int,
        birthdayHeight: Long,
        fallbackCheckpointHeight: Long,
        torDir: String?
    ): SlipstreamRestoreAnchor

    @JvmStatic
    external fun free(handle: Long)
}
