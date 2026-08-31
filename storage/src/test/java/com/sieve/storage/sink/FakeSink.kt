package com.sieve.storage.sink

import java.io.InputStream

class FakeSink(
    override val rootLabel: String = "FakeRoot",
    private val failOnName: String? = null,
) : DestinationSink {

    private data class Entry(val bytes: String, var pending: Boolean)

    // dirLabel ("" for null) -> name -> entry
    private val store = linkedMapOf<String, LinkedHashMap<String, Entry>>()
    val calls = mutableListOf<String>()

    private fun key(dirLabel: String?) = dirLabel ?: ""
    private fun dir(dirLabel: String?) = store.getOrPut(key(dirLabel)) { LinkedHashMap() }

    override suspend fun existingNames(dirLabel: String?): Set<String> {
        calls += "existingNames(${key(dirLabel)})"
        return dir(dirLabel).keys.toSet()
    }

    override suspend fun write(dirLabel: String?, name: String, mime: String, bytes: InputStream): OutputTarget {
        calls += "write(${key(dirLabel)}/$name)"
        if (name == failOnName) throw RuntimeException("injected")
        val content = bytes.readBytes().toString(Charsets.UTF_8)
        dir(dirLabel)[name] = Entry(content, pending = true)
        val disp = if (dirLabel.isNullOrBlank()) name else "$dirLabel/$name"
        return OutputTarget(name = name, uri = "fake://${key(dirLabel)}/$name", relativeDisplay = disp)
    }

    override suspend fun commit(target: OutputTarget) {
        calls += "commit(${target.name})"
        store.values.forEach { d -> d[target.name]?.let { it.pending = false } }
    }

    override suspend fun deletePending(target: OutputTarget) {
        calls += "deletePending(${target.name})"
        store.values.forEach { it.remove(target.name) }
    }

    fun committedBytes(dirLabel: String?, name: String): String? =
        dir(dirLabel)[name]?.takeIf { !it.pending }?.bytes
}
