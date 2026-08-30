package com.sieve.engine.parse

import kotlinx.serialization.json.Json

/** Lenient decoder for `yt-dlp -J` output (100+ real keys we don't model). */
val analyzeJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
}
