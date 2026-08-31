package com.sieve.transcode.catalog

import com.sieve.transcode.model.PresetCategory

/**
 * The category rail shown down the left of the Transcode screen.
 *
 * `all` and `custom` are pseudo-categories with no [PresetCategory] member: `all` shows every
 * preset, `custom` holds user-imported presets (0 in the built-in catalog). Every count is
 * **computed from the catalog**, never hardcoded — the desktop's stale "26 presets" copy came
 * from a hardcoded count drifting away from the real list (which is 52).
 */
object PresetRail {

    data class RailEntry(val key: String, val label: String, val icon: String, val count: Int)

    /** Rail entries in display order, counts derived from [TranscodePresets.all]. */
    val entries: List<RailEntry> = buildList {
        add(RailEntry("all", "All", "archive", TranscodePresets.all.size))
        for (cat in PresetCategory.entries) {
            add(RailEntry(cat.name.lowercase(), cat.label, cat.icon, countIn(cat)))
        }
        add(RailEntry("custom", "Custom", "wand", 0))
    }

    private fun countIn(cat: PresetCategory): Int = TranscodePresets.all.count { it.category == cat }

    fun count(cat: PresetCategory): Int = countIn(cat)
}
