package com.sieve.app.ui.common

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Hands a Library file off to the Transcode screen as a source. */
object SharedTranscodeSource {
    data class Src(val uri: String, val name: String)

    private val _src = MutableStateFlow<Src?>(null)
    val src: StateFlow<Src?> = _src.asStateFlow()

    fun publish(uri: String, name: String) { _src.value = Src(uri, name) }
    fun consume() { _src.value = null }
}
