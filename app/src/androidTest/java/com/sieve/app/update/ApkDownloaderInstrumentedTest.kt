package com.sieve.app.update

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@RunWith(AndroidJUnit4::class)
class ApkDownloaderInstrumentedTest {

    private val ctx = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun downloadsBytesToFile() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("FAKE-APK-BYTES"))
        server.start()
        val url = server.url("/sieve.apk").toString()

        val file = ApkDownloader(ctx).download(url, versionCode = 9)

        server.shutdown()
        assertNotNull(file)
        assertEquals("FAKE-APK-BYTES", file!!.readText())
        file.delete()
        Unit
    }
}
