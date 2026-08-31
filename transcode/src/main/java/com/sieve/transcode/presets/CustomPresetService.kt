package com.sieve.transcode.presets

import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class CustomPreset(val id: String, val name: String, val category: String, val config: String)

/** Lenient wire shape: every field nullable so one malformed entry can't abort the whole import batch. */
@Serializable
data class RawCustomPreset(
    val id: String? = null,
    val name: String? = null,
    val category: String? = null,
    val config: String? = null,
)

@Serializable
data class PresetBundle(
    val app: String = "sieve",
    val kind: String = "transcode-presets",
    val version: Int = 1,
    val exportedAt: String = "",
    val presets: List<RawCustomPreset>,
)

sealed interface ImportResult {
    data class Ok(val imported: Int) : ImportResult
    data class Err(val error: String) : ImportResult
}

interface CustomPresetStore {
    suspend fun save(p: CustomPreset)
    suspend fun getAll(): List<CustomPreset>
    suspend fun delete(id: String)
}

private fun CustomPreset.toRaw() = RawCustomPreset(id, name, category, config)

/**
 * Import/export of user custom presets.
 *
 * Validation is by [PresetBundle.kind] (not app/version). Each preset's [CustomPreset.config] is an
 * **opaque string** — never re-parsed, round-tripped byte-identical. Entries missing id/name/config
 * are skipped; id collisions are re-keyed `"<id>-imp-<millis>-<index>"`; individual store failures
 * skip just that entry.
 */
class CustomPresetService(
    private val store: CustomPresetStore,
    private val json: Json = Json { prettyPrint = true; ignoreUnknownKeys = true; isLenient = true },
    private val nowIso: () -> String,
    private val nowMillis: () -> Long,
) {

    fun export(presets: List<CustomPreset>): String =
        json.encodeToString(PresetBundle(exportedAt = nowIso(), presets = presets.map { it.toRaw() }))

    suspend fun import(raw: String, existing: List<CustomPreset>): ImportResult {
        val bundle = try {
            json.decodeFromString<PresetBundle>(raw)
        } catch (e: SerializationException) {
            return ImportResult.Err("Not valid JSON: ${e.message}")
        } catch (e: IllegalArgumentException) {
            return ImportResult.Err("Not valid JSON: ${e.message}")
        }
        if (bundle.kind != "transcode-presets") {
            return ImportResult.Err("Unrecognized bundle kind '${bundle.kind}'")
        }

        val takenIds = existing.map { it.id }.toMutableSet()
        var imported = 0
        for (entry in bundle.presets) {
            if (entry.id.isNullOrEmpty() || entry.name.isNullOrEmpty() || entry.config == null) continue
            var id = entry.id
            if (id in takenIds) id = "${entry.id}-imp-${nowMillis()}-$imported"
            val preset = CustomPreset(id, entry.name, entry.category ?: "custom", entry.config)
            try {
                store.save(preset)
                takenIds += id
                imported++
            } catch (e: Exception) {
                // Skip individual store failures; the rest of the batch continues.
            }
        }
        return ImportResult.Ok(imported)
    }
}
