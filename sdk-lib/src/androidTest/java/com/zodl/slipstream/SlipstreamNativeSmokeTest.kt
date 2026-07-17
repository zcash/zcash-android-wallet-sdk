package com.zodl.slipstream

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
        SlipstreamNative.ensureLoaded()
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
}
