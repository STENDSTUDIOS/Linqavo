package io.relay.app.core

/** Resource prefilter only; kotlinx.serialization still performs all JSON parsing. */
object BoundedJson {
    fun shallow(text: String, maximumDepth: Int = 16): Boolean {
        var quoted = false
        var escaped = false
        var depth = 0
        for (ch in text) {
            if (quoted) {
                if (escaped) escaped = false
                else if (ch == '\\') escaped = true
                else if (ch == '"') quoted = false
            } else when (ch) {
                '"' -> quoted = true
                '{', '[' -> { depth++; if (depth > maximumDepth) return false }
                '}', ']' -> { depth--; if (depth < 0) return false }
            }
        }
        return true
    }
}
