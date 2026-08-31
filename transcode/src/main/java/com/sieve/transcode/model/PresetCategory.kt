package com.sieve.transcode.model

/**
 * The seven real preset categories that drive the category rail and its counts.
 *
 * `all` and `custom` are rail-only pseudo-categories (see [com.sieve.transcode.catalog.PresetRail])
 * and are deliberately NOT members here — no catalog preset carries them.
 * `label`/`icon` are ported verbatim from the desktop transcodeStore rail.
 */
enum class PresetCategory(val label: String, val icon: String) {
    WEB("Web", "film"),
    EDIT("Edit", "sliders"),
    SOCIAL("Social", "sparkle"),
    AUDIO("Audio", "music"),
    DEVICES("Devices", "chip"),
    LEGACY("Legacy", "history"),
    IMAGE("Image", "film"),
}
