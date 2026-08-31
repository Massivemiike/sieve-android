package com.sieve.storage.service

import com.sieve.queue.core.JobSpec
import com.sieve.queue.core.OutputRequest
import com.sieve.queue.core.QueueJob

/** Small builder matching :queue's real QueueJob/OutputRequest/JobSpec.Download (com.sieve.queue.core). */
object QueueJobFixtures {
    fun download(
        id: String,
        url: String = "https://example/$id",
        title: String = "Title $id",
        outputDirLabel: String = "",
        outputTemplate: String = "",
    ): QueueJob = QueueJob(
        id = id,
        spec = JobSpec.Download(url = url, engineArgs = emptyList()),
        output = OutputRequest(outputDirLabel = outputDirLabel, outputTemplate = outputTemplate),
        title = title,
    )
}
