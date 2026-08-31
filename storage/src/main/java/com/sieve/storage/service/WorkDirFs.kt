package com.sieve.storage.service

import java.io.File
import java.io.InputStream

/** The work-dir filesystem behind an interface, so the provider is JVM-testable without real I/O. */
interface WorkDirFs {
    fun mkdirs(path: String)
    fun exists(path: String): Boolean
    fun listLeafNames(path: String): List<String>
    fun openRead(path: String, leaf: String): InputStream
    fun deleteRecursively(path: String)
}

class JavaWorkDirFs : WorkDirFs {
    override fun mkdirs(path: String) { File(path).mkdirs() }
    override fun exists(path: String) = File(path).exists()
    override fun listLeafNames(path: String): List<String> {
        val root = File(path)
        if (!root.exists()) return emptyList()
        return root.walkTopDown().filter { it.isFile }.map { it.name }.toList()
    }
    override fun openRead(path: String, leaf: String): InputStream {
        val direct = File(path, leaf)
        val f = if (direct.isFile) direct else File(path).walkTopDown().first { it.isFile && it.name == leaf }
        return f.inputStream()
    }
    override fun deleteRecursively(path: String) { File(path).deleteRecursively() }
}
