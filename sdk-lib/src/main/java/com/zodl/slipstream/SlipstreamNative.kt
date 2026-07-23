package com.zodl.slipstream

import cash.z.ecc.android.sdk.internal.jni.RustBackend
import com.zodl.slipstream.model.SlipstreamEvent
import com.zodl.slipstream.model.SlipstreamRawTransaction
import com.zodl.slipstream.model.SlipstreamResubmissionRow
import com.zodl.slipstream.model.SlipstreamRestoreAnchor
import com.zodl.slipstream.model.SlipstreamSnapshot
import com.zodl.slipstream.model.SlipstreamTransactionRow
import com.zodl.slipstream.model.SlipstreamTxOutputRow
import com.zodl.slipstream.model.SlipstreamWalletSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Raw JNI surface over the Slipstream engine. One-to-one binding of the Slipstream C ABI
 * (`HOSTING.md` section 4, `FFI_JNI_CONTRACT.md` section 2). Every method that takes `handle`
 * MUST be called on the single Slipstream dispatcher
 * (see [com.zodl.slipstream.internal.SlipstreamDispatchers]) - never concurrently. All failures
 * surface as `java.lang.RuntimeException` (see the error mapping contract, `FFI_JNI_CONTRACT.md`
 * section 6).
 *
 * `setAlternateServers` is a v0.7 fast-follow (`FFI_JNI_CONTRACT.md` section 3.4 / 9.1), not
 * part of the v0.6.0 surface this object binds.
 */
internal object SlipstreamNative {
    /** Contract version this binding implements (`HOSTING.md` as of engine v0.6.0). */
    const val CONTRACT_VERSION: String = "0.6.0"

    private var loaded = false
    private val loadMutex = Mutex()

    /**
     * Loads the merged native library via [RustBackend.loadLibrary] (the one process-wide
     * `System.loadLibrary` call), then runs Slipstream's own `initOnLoad` exactly once.
     * Idempotent and thread-safe; every entry point of the adapter calls this first. A plain
     * double-checked lock replaces `@Synchronized` because a suspend function must not suspend
     * while holding a JVM monitor. logLevel: "error" | "warn" | "info" | "debug" | "trace" | "off".
     */
    suspend fun ensureLoaded(logLevel: String = "warn") {
        if (!loaded) {
            withContext(Dispatchers.IO) {
                loadMutex.withLock {
                    if (!loaded) {
                        RustBackend.loadLibrary()
                        initOnLoad(logLevel)
                        loaded = true
                    }
                }
            }
        }
    }

    @JvmStatic
    @Throws(RuntimeException::class)
    private external fun initOnLoad(logLevel: String)

    /** Build stamp: "slipstream-android <aar-version> (engine <tag>)". */
    @JvmStatic
    @Throws(RuntimeException::class)
    external fun version(): String

    /** `open` (`HOSTING.md` section 4 row 1): allocates the handle. Returns the handle as a `jlong`. */
    @JvmStatic
    @Throws(RuntimeException::class)
    external fun open(
        dbPath: String,
        serverHost: String,
        serverPort: Int,
        useTls: Boolean,
        networkId: Int,
        totalMemoryBytes: Long
    ): Long

    /** `start` (`HOSTING.md` section 4 row 2): `ufvk` null = keyless, non-null = view-only import on the first pass. */
    @JvmStatic
    @Throws(RuntimeException::class)
    external fun start(
        handle: Long,
        ufvk: String?,
        birthdayHeight: Long,
        torDir: String?
    ): Boolean

    /** `stop`: cancels the sync task and performs the bounded join + writer-drain. */
    @JvmStatic
    @Throws(RuntimeException::class)
    external fun stop(handle: Long): Boolean

    /** `snapshot` (`HOSTING.md` section 5): the poll read - cheap, non-blocking, call every tick. */
    @JvmStatic
    @Throws(RuntimeException::class)
    external fun snapshot(handle: Long): SlipstreamSnapshot

    /** `drainEvents`: atomically drains the 64-slot event ring - MUST be called every tick even if ignored. */
    @JvmStatic
    @Throws(RuntimeException::class)
    external fun drainEvents(handle: Long): Array<SlipstreamEvent>

    /**
     * `walletSummary` (`HOSTING.md` section 7.2): the phase-resolving balance read, or `null`
     * for "no balance data yet".
     */
    @JvmStatic
    @Throws(RuntimeException::class)
    external fun walletSummary(
        handle: Long,
        trustedConfirmations: Int,
        untrustedConfirmations: Int,
        allowZeroConfShielding: Boolean
    ): SlipstreamWalletSummary?

    /** `notifyTxChange`: host poke after the host stored a just-broadcast transaction. */
    @JvmStatic
    @Throws(RuntimeException::class)
    external fun notifyTxChange(handle: Long): Boolean

    /** `restoreAnchor` (`HOSTING.md` section 8): handle-less wallet-provisioning facts for `intent` restore/new. */
    @JvmStatic
    @Throws(RuntimeException::class)
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

    /** `free`: cancels everything and drops the runtime; the handle is dangling after this returns. */
    @JvmStatic
    @Throws(RuntimeException::class)
    external fun free(handle: Long)

    /** First of 5 typed host-read exports; production visible-transactions read, optionally account-scoped. Not part of [CONTRACT_VERSION]. */
    @JvmStatic
    @Throws(RuntimeException::class)
    external fun listTransactions(
        dbPath: String,
        isRecovering: Boolean,
        accountUuid: ByteArray?
    ): Array<SlipstreamTransactionRow>

    /** `getTransactionRaw`: raw bytes + expiry height for one txid, or `null` if not stored. */
    @JvmStatic
    @Throws(RuntimeException::class)
    external fun getTransactionRaw(
        dbPath: String,
        txid: ByteArray
    ): SlipstreamRawTransaction?

    /** `listTransactionOutputs`: non-change outputs for one txid, or - when [txid] is `null` - every account's. */
    @JvmStatic
    @Throws(RuntimeException::class)
    external fun listTransactionOutputs(
        dbPath: String,
        txid: ByteArray?
    ): Array<SlipstreamTxOutputRow>

    /** `findTransactionsByMemo`: txids whose memo contains [substring] (case-insensitive; wildcarding is native-side). */
    @JvmStatic
    @Throws(RuntimeException::class)
    external fun findTransactionsByMemo(
        dbPath: String,
        substring: String
    ): Array<ByteArray>

    /** `listResubmissionCandidates`: unmined, unexpired, outgoing transactions as of [chainTip]. */
    @JvmStatic
    @Throws(RuntimeException::class)
    external fun listResubmissionCandidates(
        dbPath: String,
        chainTip: Long
    ): Array<SlipstreamResubmissionRow>

    /**
     * DEBUG-ONLY host utility backing `Synchronizer.debugQuery`; not part of [CONTRACT_VERSION].
     * Runs [sql] as a single read-only query on the engine's bundled SQLite instance. At most
     * one of [blobParam]/[textParam] may be non-null; it binds as the statement's sole `?1`
     * parameter - callers MUST bind, never concatenate, any user-influenced text into [sql].
     * Returns the rows as a JSON array of arrays, or `null` on error.
     */
    @JvmStatic
    @Throws(RuntimeException::class)
    external fun readQuery(
        dbPath: String,
        sql: String,
        blobParam: ByteArray?,
        textParam: String?
    ): String?
}
