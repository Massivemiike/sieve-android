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

    // A single file (the per-job download archive lives next to the work dir, not in it) is addressed
    // as <dir>/<leaf>, exactly like a real path.
    private fun isFile(path: String) = files[path.substringBeforeLast('/', "")]?.containsKey(path.substringAfterLast('/')) == true

    override fun exists(path: String) = path in dirs || isFile(path)
    override fun listLeafNames(path: String) = files[path]?.keys?.toList() ?: emptyList()
    override fun openRead(path: String, leaf: String): InputStream =
        ByteArrayInputStream(files[path]?.get(leaf) ?: error("no file $path/$leaf"))

    override fun deleteRecursively(path: String) {
        deleteCalls++
        dirs -= path
        files.remove(path)
        files[path.substringBeforeLast('/', "")]?.remove(path.substringAfterLast('/'))
    }

    fun putFile(dir: String, leaf: String, bytes: ByteArray) {
        mkdirs(dir)
        files[dir]!![leaf] = bytes
    }
}
