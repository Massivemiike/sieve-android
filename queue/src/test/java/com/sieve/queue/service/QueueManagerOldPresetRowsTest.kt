package com.sieve.queue.service

import com.sieve.data.db.DownloadTaskEntity
import com.sieve.engine.repo.EngineEvent
import com.sieve.queue.core.DownloadStatus
import com.sieve.queue.core.InMemoryRestoreHoldStore
import com.sieve.queue.core.JobSpec
import com.sieve.queue.core.QueueState
import com.sieve.queue.core.RestoreHold
import com.sieve.queue.persist.TaskMapping
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Download rows saved by v1.0.3 and earlier carry the OLD preset arguments in their stored `args`: a 720p row the
 * `[height<=720]` selector, an MP3 row `--audio-quality 0` (LAME V0), an Opus row a bare `-x`. A row saved by the first 1.0.4
 * release candidate (419d299) carries its H.264-first MP4 selector and `-S` sort, which the final keep-resolution presets
 * replaced. The 1.0.4 preset fix changes what NEW rows are built with and nothing else: there is no database migration and
 * nothing rewrites a stored row on restore or at spawn, so every old row (restored, resumed or retried) runs exactly as it
 * was saved. The rows here go through the real storage mapping and the real queue, and the port records what yt-dlp would be
 * started with.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QueueManagerOldPresetRowsTest {
    private val old720 =
        "bestvideo[height<=720][vcodec^=avc1]+bestaudio[ext=m4a]/bestvideo[height<=720][ext=mp4]+bestaudio[ext=m4a]/" +
            "bestvideo[height<=720]+bestaudio/best[height<=720]/best"
    private val old1080 =
        "bestvideo[height<=1080][vcodec^=avc1]+bestaudio[ext=m4a]/bestvideo[height<=1080][ext=mp4]+bestaudio[ext=m4a]/" +
            "bestvideo[height<=1080]+bestaudio/best[height<=1080]/best"

    /** The first release candidate's MP4 selector (H.264 first, anything last) and sort: replaced by keep-resolution before 1.0.4 shipped. */
    private val rc1Format =
        "bv[vcodec~='^(avc|h264)']+ba/b[vcodec~='^(avc|h264)']/" +
            "b[format_id=hd][ext=mp4][url~='[?&]tag=(hd|dash_h264[a-z0-9_-]*)(&|\$)']/b[format_id=sd][ext=mp4]/bv*+ba/b"
    private fun rc1Sort(shortSide: Int) = "res:$shortSide,vcodec:h264,acodec:aac,ext:mp4:m4a"

    /** The `args` column as the 1.0.3 download screen wrote it (`YtdlpArgs.build`: -f, -o, -P, the preset's extras, the toggles, -N). */
    private fun stored(format: String, vararg extras: String) = listOf(
        "-f", format, "-o", "%(title).150B [%(id)s].%(ext)s", "-P", "~/Videos/yt-dlp", *extras,
        "--embed-metadata", "--embed-thumbnail", "-N", "4",
    )

    private val oldRows = linkedMapOf(
        "old-1080" to stored(old1080),
        "old-720" to stored(old720),
        "old-mp3" to stored("bestaudio/best", "-x", "--audio-format", "mp3", "--audio-quality", "0"),
        "old-opus" to stored("bestaudio[ext=webm]/bestaudio/best", "-x"),
        "rc1-1080" to stored(rc1Format, "-S", rc1Sort(1080), "--merge-output-format", "mp4"),
        "rc1-720" to stored(rc1Format, "-S", rc1Sort(720), "--merge-output-format", "mp4"),
    )

    /**
     * What yt-dlp is started with for a stored row: `-c`, the stored args minus their own -P/-o, then the job's work dir and
     * template, and the title copy that template reads (every row gets the same two, whatever preset it was saved with).
     */
    private fun spawned(id: String, args: List<String>): List<String> {
        val kept = ArrayList<String>()
        var i = 0
        while (i < args.size) {
            if (args[i] == "-o" || args[i] == "-P") i += 2 else kept += args[i++]
        }
        // FakeOutputProvider's work dir and template; its unbounded %(title)s is cut to 150 bytes at spawn, byte-exactly (the cut reads
        // the sanitized title copy that --parse-metadata makes).
        return listOf("-c") + kept + listOf("-P", "/work/$id", "-o", "%(__sieve_title,title).150B.%(ext)s", "--parse-metadata", "%(title)S:%(__sieve_title)s")
    }

    private fun entity(id: String, position: Long, status: DownloadStatus, args: List<String>) = DownloadTaskEntity(
        id = id, position = position, url = "https://example.com/$id", kind = "DOWNLOAD", status = status.name,
        title = "Title $id", site = "Example", format = id, args = args, outputDirLabel = "Videos", outputTemplate = "%(title).150B [%(id)s].%(ext)s",
    )

    private fun persisted(status: DownloadStatus) = InMemoryPersistence().also { p ->
        p.store.value = oldRows.entries.mapIndexed { i, (id, args) -> TaskMapping.toDomain(entity(id, i + 1L, status, args)) }
            .associateBy { it.id }
    }

    /** Records the argv of every start, and finishes each download at once. */
    private class RecordingPort : DownloadPort {
        val started = LinkedHashMap<String, List<String>>()
        override fun download(id: String, url: String, args: List<String>): Flow<EngineEvent> {
            started[id] = args
            return flow { emit(EngineEvent.Completed(0)) }
        }
        override fun cancel(id: String) = true
    }

    private fun manager(persistence: InMemoryPersistence, port: RecordingPort, hold: RestoreHold) = QueueManager(
        JobDriver(port, FakeTranscodePort()), port, FakeTranscodePort(), persistence, FakeOutputProvider(), FakeClock(),
        initial = QueueState(maxDownloads = 4), restoreStore = InMemoryRestoreHoldStore(hold),
    )

    private fun assertRanAsStored(port: RecordingPort) {
        assertEquals(oldRows.keys.toList(), port.started.keys.toList())
        for ((id, args) in oldRows) assertEquals(id, spawned(id, args), port.started[id])
        // The old selectors and quality flags are all still there, and none of the 1.0.4 preset flags crept in.
        assertTrue(old720 in port.started.getValue("old-720"))
        assertTrue(old1080 in port.started.getValue("old-1080"))
        val mp3 = port.started.getValue("old-mp3")
        assertEquals("0", mp3[mp3.indexOf("--audio-quality") + 1])
        val opus = port.started.getValue("old-opus")
        assertFalse("--audio-format" in opus)
        // The release candidate rows keep THEIR sort (no `proto` key) and selector (a codec-first tier, no size guard).
        for (cap in listOf(1080, 720)) {
            val rc1 = port.started.getValue("rc1-$cap")
            assertTrue(rc1Format in rc1)
            assertEquals(rc1Sort(cap), rc1[rc1.indexOf("-S") + 1])
        }
        for ((id, argv) in port.started) {
            // only a release candidate row has a sort and a merge flag: the 1.0.3 rows never get one
            assertEquals(id, id.startsWith("rc1-"), "-S" in argv || "--merge-output-format" in argv)
            assertFalse(id, "320K" in argv || "128K" in argv || "opus" in argv)
            assertFalse(id, argv.any { "proto" in it || "[width>" in it })
        }
    }

    @Test fun `old rows restored on an ordinary launch start with exactly their stored arguments`() = runTest {
        val port = RecordingPort()
        val m = manager(persisted(DownloadStatus.QUEUED), port, RestoreHold.SETTLED)
        m.rehydrate()
        m.start(backgroundScope)

        m.state.first { s -> s.jobs.size == oldRows.size && s.jobs.all { it.status == DownloadStatus.COMPLETED } }

        assertRanAsStored(port)
    }

    @Test fun `old rows held by the one-time restore run as stored once the user resumes them`() = runTest {
        val port = RecordingPort()
        val m = manager(persisted(DownloadStatus.QUEUED), port, RestoreHold()) // no marker yet: the upgrade from 1.0.3
        m.rehydrate()
        m.start(backgroundScope)
        assertTrue(m.state.value.jobs.all { it.status == DownloadStatus.PAUSED })
        assertTrue("nothing starts by itself", port.started.isEmpty())

        m.resumeHeld()
        m.state.first { s -> s.jobs.all { it.status == DownloadStatus.COMPLETED } }

        assertRanAsStored(port)
    }

    @Test fun `retrying an old failed row gives the same arguments, not the new preset's`() = runTest {
        val port = RecordingPort()
        val m = manager(persisted(DownloadStatus.FAILED), port, RestoreHold.SETTLED)
        m.rehydrate()
        m.start(backgroundScope)
        assertTrue(port.started.isEmpty())

        for (id in oldRows.keys) m.retry(id)
        m.state.first { s -> s.jobs.all { it.status == DownloadStatus.COMPLETED } }

        assertRanAsStored(port)
    }

    @Test fun `the stored args are never rewritten by the database mapping or the restore`() = runTest {
        val persistence = persisted(DownloadStatus.QUEUED)
        for ((id, args) in oldRows) {
            val e = entity(id, 1, DownloadStatus.QUEUED, args)
            assertEquals(id, args, TaskMapping.toEntity(TaskMapping.toDomain(e)).args)
        }
        val m = manager(persistence, RecordingPort(), RestoreHold.SETTLED)
        m.rehydrate()
        for ((id, args) in oldRows) {
            assertEquals(id, args, (m.state.value.job(id)!!.spec as JobSpec.Download).engineArgs)
            assertEquals(id, args, persistence.loadAll().first { it.id == id }.let { (it.spec as JobSpec.Download).engineArgs })
        }
    }
}
