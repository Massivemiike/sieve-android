package com.sieve.engine.args

/**
 * Totally models the JS `Boolean | String` a toggle-drawer row emits.
 * `On` = boolean true (single flag token); `Text` = non-empty string (flag + value);
 * `Off` = false/empty (ignored). Numbers never occur.
 */
sealed interface ToggleValue {
    data object On : ToggleValue
    data class Text(val value: String) : ToggleValue
    data object Off : ToggleValue
}
