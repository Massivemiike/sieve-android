package com.sieve.storage.service

import com.sieve.queue.core.QueueJob
import com.sieve.storage.sink.DestinationSink

/** Precedence: a valid granted SAF tree → MediaStore Downloads → app files. Factories injected → JVM-testable. */
class DefaultSinkSelector(
    private val treeSinkFactory: () -> DestinationSink?,   // null when no valid granted tree
    private val mediaStoreFactory: () -> DestinationSink?, // null pre-29 / disabled
    private val appFilesFactory: () -> DestinationSink,
) : SinkSelector {
    override suspend fun select(job: QueueJob): DestinationSink =
        treeSinkFactory() ?: mediaStoreFactory() ?: appFilesFactory()
}
