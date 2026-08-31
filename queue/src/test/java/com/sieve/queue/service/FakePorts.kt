package com.sieve.queue.service

import com.sieve.engine.repo.EngineEvent
import com.sieve.transcode.runner.TranscodeEvent
import com.sieve.transcode.runner.TranscodeJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

class FakeDownloadPort(
    val script: (id: String) -> Flow<EngineEvent> = { emptyFlow() },
) : DownloadPort {
    val cancelled = mutableListOf<String>()
    override fun download(id: String, url: String, args: List<String>) = script(id)
    override fun cancel(id: String): Boolean { cancelled += id; return true }
}

class FakeTranscodePort(
    val script: (id: String) -> Flow<TranscodeEvent> = { emptyFlow() },
) : TranscodePort {
    val cancelled = mutableListOf<String>()
    override fun run(id: String, job: TranscodeJob) = script(id)
    override suspend fun cancel(id: String, graceMs: Long) { cancelled += id }
}
