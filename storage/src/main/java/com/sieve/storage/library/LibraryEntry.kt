package com.sieve.storage.library

data class LibraryEntry(
    val documentId: String,
    val uri: String,
    val name: String,
    val size: Long,
    val lastModified: Long,
    val isDir: Boolean,
    val ext: String,
)
