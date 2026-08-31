package com.sieve.storage.service

import java.io.ByteArrayInputStream
import java.io.InputStream

class FakeWorkDirFs : WorkDirFs {
    val dirs = linkedSetOf<String>()

    // dirPath -> (leafName -> bytes)
    val files = linkedMapOf<String, LinkedHashMap<String, ByteArray>>()
    var deleteCalls = 0
        private set

    override fun mkdirs(path: String) {
        dirs += path
        files.getOrPut(path) { LinkedHashMap() }
    }

    override fun exists(path: String) = path in dirs
    override fun listLeafNames(path: String) = files[path]?.keys?.toList() ?: emptyList()
    override fun openRead(path: String, leaf: String): InputStream =
        ByteArrayInputStream(files[path]?.get(leaf) ?: error("no file $path/$leaf"))

    override fun deleteRecursively(path: String) {
        deleteCalls++
        dirs -= path
        files.remove(path)
    }

    fun putFile(dir: String, leaf: String, bytes: ByteArray) {
        mkdirs(dir)
        files[dir]!![leaf] = bytes
    }
}
