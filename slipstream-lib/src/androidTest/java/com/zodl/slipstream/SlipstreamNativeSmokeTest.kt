package com.zodl.slipstream

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The first proof the JNI binding is real: open -> snapshot -> drainEvents -> free on a real
 * device/emulator `.so`. Written and compiled as part of the Kotlin natives work
 * (`SDK_ADAPTER_PLAN.md` T3); NOT run by this pass - `libslipstream.so` has not been built yet
 * (Phase 1 A0 is blocked pending sign-off; see `docs/slipstream/android/worklog/01-rust-build.md`).
 */
@RunWith(AndroidJUnit4::class)
class SlipstreamNativeSmokeTest {
    @Test
    fun open_snapshot_drain_free() {
        runBlocking { SlipstreamNative.ensureLoaded() }
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val db = File(ctx.filesDir, "smoke_data.sqlite3").also { it.delete() }

        val handle = SlipstreamNative.open(db.absolutePath, "zec.rocks", 443, true, 1, 0L)
        assertTrue("open() must return a non-zero handle", handle != 0L)
        try {
            val snap = SlipstreamNative.snapshot(handle)
            // Truthful-from-open on a fresh wallet (HOSTING.md section 5): idle, cold, not recovering.
            assertEquals(0, snap.state)
            assertEquals(false, snap.isRecovering)
            assertTrue("permille within contract range", snap.progressPermille in 0..1000)
            assertTrue("ring drains empty pre-start", SlipstreamNative.drainEvents(handle).isEmpty())
        } finally {
            SlipstreamNative.free(handle)
        }
    }

    /**
     * The `readQuery` host-utility export, DEBUG LANE ONLY (`Synchronizer.debugQuery`; worklog
     * `08-engine-sigbus-android.md`): a trivial `SELECT 1` over the just-opened engine DB, on
     * the SAME bundled SQLite instance `open` created the handle against - proof the binding
     * round-trips a real connection + JSON row encoding, not just that it links. NOT run by this
     * pass, same as [open_snapshot_drain_free].
     */
    @Test
    fun readQuery_smoke() {
        runBlocking { SlipstreamNative.ensureLoaded() }
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val db = File(ctx.filesDir, "smoke_readquery.sqlite3").also { it.delete() }

        val handle = SlipstreamNative.open(db.absolutePath, "zec.rocks", 443, true, 1, 0L)
        try {
            val json = SlipstreamNative.readQuery(db.absolutePath, "SELECT 1", null, null)
            assertEquals("[[1]]", json)
        } finally {
            SlipstreamNative.free(handle)
        }
    }

    /**
     * The 5 typed host-read exports (`FFI_JNI_CONTRACT.md` section 9.3) that replaced
     * `readQuery` as the production read path - one existence-level smoke per export, same
     * "NOT run by this pass" caveat as [open_snapshot_drain_free]: proof each export links and
     * round-trips a real connection against the freshly-migrated, still-account-less views
     * `open` installs (compile-gate only, not a behavior test).
     */
    @Test
    fun listTransactions_smoke() {
        runBlocking { SlipstreamNative.ensureLoaded() }
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val db = File(ctx.filesDir, "smoke_list_transactions.sqlite3").also { it.delete() }

        val handle = SlipstreamNative.open(db.absolutePath, "zec.rocks", 443, true, 1, 0L)
        try {
            val rows = SlipstreamNative.listTransactions(db.absolutePath, false, null)
            assertTrue("no accounts yet -> no rows", rows.isEmpty())
        } finally {
            SlipstreamNative.free(handle)
        }
    }

    @Test
    fun getTransactionRaw_smoke() {
        runBlocking { SlipstreamNative.ensureLoaded() }
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val db = File(ctx.filesDir, "smoke_get_transaction_raw.sqlite3").also { it.delete() }

        val handle = SlipstreamNative.open(db.absolutePath, "zec.rocks", 443, true, 1, 0L)
        try {
            val row = SlipstreamNative.getTransactionRaw(db.absolutePath, ByteArray(32))
            assertEquals(null, row)
        } finally {
            SlipstreamNative.free(handle)
        }
    }

    @Test
    fun listTransactionOutputs_smoke() {
        runBlocking { SlipstreamNative.ensureLoaded() }
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val db = File(ctx.filesDir, "smoke_list_transaction_outputs.sqlite3").also { it.delete() }

        val handle = SlipstreamNative.open(db.absolutePath, "zec.rocks", 443, true, 1, 0L)
        try {
            val rows = SlipstreamNative.listTransactionOutputs(db.absolutePath, null)
            assertTrue("no accounts yet -> no rows", rows.isEmpty())
        } finally {
            SlipstreamNative.free(handle)
        }
    }

    @Test
    fun findTransactionsByMemo_smoke() {
        runBlocking { SlipstreamNative.ensureLoaded() }
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val db = File(ctx.filesDir, "smoke_find_transactions_by_memo.sqlite3").also { it.delete() }

        val handle = SlipstreamNative.open(db.absolutePath, "zec.rocks", 443, true, 1, 0L)
        try {
            val rows = SlipstreamNative.findTransactionsByMemo(db.absolutePath, "memo")
            assertTrue("no accounts yet -> no rows", rows.isEmpty())
        } finally {
            SlipstreamNative.free(handle)
        }
    }

    @Test
    fun listResubmissionCandidates_smoke() {
        runBlocking { SlipstreamNative.ensureLoaded() }
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val db = File(ctx.filesDir, "smoke_list_resubmission_candidates.sqlite3").also { it.delete() }

        val handle = SlipstreamNative.open(db.absolutePath, "zec.rocks", 443, true, 1, 0L)
        try {
            val rows = SlipstreamNative.listResubmissionCandidates(db.absolutePath, 0L)
            assertTrue("no accounts yet -> no rows", rows.isEmpty())
        } finally {
            SlipstreamNative.free(handle)
        }
    }
}
