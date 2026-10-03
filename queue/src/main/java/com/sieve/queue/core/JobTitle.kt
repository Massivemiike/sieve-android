package com.sieve.queue.core

/**
 * What a queue row calls itself: its own [QueueJob.title] when it has one, else the link it downloads (the desktop titles a row
 * queued without a reading of its link by that link) or, for a transcode, its input file name. The Download screen writes the link
 * into the title of such a row itself, so a blank title is only left on rows saved by older builds.
 */
val QueueJob.displayTitle: String
    get() = title.ifBlank {
        when (val s = spec) {
            is JobSpec.Download -> s.url.trim()
            is JobSpec.Transcode -> s.inputPath.trim().substringAfterLast('/')
        }
    }.ifBlank { if (kind == JobKind.TRANSCODE) "Transcode" else "Download" }
