package com.zodl.slipstream

import cash.z.ecc.android.sdk.internal.jni.RustBackend
import com.zodl.slipstream.model.SlipstreamEvent
import com.zodl.slipstream.model.SlipstreamRestoreAnchor
import com.zodl.slipstream.model.SlipstreamSnapshot
import com.zodl.slipstream.model.SlipstreamWalletSummary
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    private val loadMutex = Mutex()

    /**
     * Loads the merged native library (`libzcashwalletsdk.so`, which now also carries every
     * `Java_com_zodl_slipstream_*` export alongside the RustBackend JNI surface - see
     * `backend-lib/Cargo.toml`'s `slipstream-jni` path dependency) via [RustBackend.loadLibrary],
     * then runs Slipstream's own process-global native init (logging, panic hook, rayon pool)
     * exactly once. [RustBackend.loadLibrary] owns the ONE `System.loadLibrary` call for the
     * whole process and, inside its own once-guard, always runs backend's `initOnLoad` first -
     * regardless of whether TorClient, DerivationTool, or this adapter is the caller that gets
     * there first - so backend's `tracing` subscriber is always installed before Slipstream's own
     * `initOnLoad` tries to install one (Slipstream's install is a no-op against an
     * already-installed global subscriber). This method's own [loaded]/[loadMutex] guard only
     * sequences Slipstream's `initOnLoad` to run after that, exactly once; it does not duplicate
     * [RustBackend.loadLibrary]'s own guard, which is why a plain double-checked lock (matching
     * `NativeLibraryLoader`'s own idiom) replaces the previous `@Synchronized` - a suspend
     * function must not suspend while holding a JVM monitor. Idempotent and thread-safe; every
     * entry point of the adapter calls this first. logLevel: "error" | "warn" | "info" | "debug" |
     * "trace" | "off".
     */
    suspend fun ensureLoaded(logLevel: String = "warn") {
        if (!loaded) {
            loadMutex.withLock {
                if (!loaded) {
                    RustBackend.loadLibrary()
                    initOnLoad(logLevel)
                    loaded = true
                }
            }
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

    /**
     * EMERGENCY host-utility export, added while the engine owner is away - NOT part of the
     * [CONTRACT_VERSION] surface (no C-ABI twin, no handle, no engine state). Runs [sql] as a
     * single read-only query against [dbPath] on the SAME bundled SQLite instance the engine's
     * own writer uses, closing the dual-SQLite-instance hazard the Android-framework
     * `SlipstreamWalletDb`/`SlipstreamTransactionReader` reader used to carry (see
     * `worklog/08-engine-sigbus-android.md`: two independent SQLite library instances against
     * one `data.sqlite3` in one process corrupt each other's fcntl locks on every framework
     * `close()`, destroying the engine's WAL/`-shm` index). At most one of [blobParam]/
     * [textParam] may be non-null; it binds as the statement's sole `?1` parameter - callers
     * MUST bind, never concatenate, any user-influenced text into [sql]. Returns the rows as a
     * JSON array of arrays (columns: INTEGER/REAL as a JSON number, TEXT as a JSON string,
     * BLOB as a lowercase-hex JSON string, NULL as JSON null), or `null` on error (a
     * `RuntimeException` is also thrown).
     */
    @JvmStatic
    external fun readQuery(
        dbPath: String,
        sql: String,
        blobParam: ByteArray?,
        textParam: String?
    ): String?
}
