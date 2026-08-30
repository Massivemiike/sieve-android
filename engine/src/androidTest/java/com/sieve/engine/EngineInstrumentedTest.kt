package com.sieve.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sieve.engine.repo.YoutubeDLClientImpl
import com.yausername.youtubedl_android.YoutubeDL
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Task 16: on-device smoke. The Phase-0 spike already validated init + version +
 * execute(download) + updateYoutubeDL on the 16KB Android 15 emulator; this locks
 * the engine module's real seam against the library on-device. Run via
 * `:engine:connectedDebugAndroidTest` once an emulator/device is attached.
 */
@RunWith(AndroidJUnit4::class)
class EngineInstrumentedTest {
    @Test
    fun engineInitDoesNotThrowAndClientConstructs() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        YoutubeDL.getInstance().init(ctx)
        // Constructs the real seam; version() may be null until the first payload update.
        val client = YoutubeDLClientImpl(ctx)
        client.version()
    }
}
