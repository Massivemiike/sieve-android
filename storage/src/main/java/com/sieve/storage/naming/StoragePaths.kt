package com.sieve.storage.naming

object StoragePaths {

    private const val BASE = "Download/Sieve"

    fun workDir(filesDirPath: String, jobId: String): String {
        val base = filesDirPath.trimEnd('/')
        val id = sanitizeLabelSegment(jobId)
        return "$base/work/$id"
    }

    /** One flat, safe path segment: no separators, no traversal. */
    fun sanitizeLabelSegment(label: String): String {
        val flattened = label.replace('/', '_').replace('\\', '_')
        return FilenameSanitizer.sanitize(flattened, maxBytes = 100)
    }

    fun outputRelativePath(dirLabel: String?): String {
        val label = dirLabel?.takeIf { it.isNotBlank() } ?: return BASE
        return "$BASE/${sanitizeLabelSegment(label)}"
    }
}
