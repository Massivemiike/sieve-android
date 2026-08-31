package com.sieve.queue.service

import com.sieve.engine.repo.EngineEvent
import com.sieve.engine.repo.YtDlpEngine
import kotlinx.coroutines.flow.Flow

/** Production [DownloadPort] over the real [YtDlpEngine]. */
class RealDownloadPort(private val engine: YtDlpEngine) : DownloadPort {
    override fun download(id: String, url: String, args: List<String>): Flow<EngineEvent> =
        engine.download(id, url, args)
    override fun cancel(id: String): Boolean = engine.cancel(id)
}
