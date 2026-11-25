package dev.routinecoach.core.pack

/**
 * Maps JSON paths (in kotlinx.serialization notation, e.g. `$.blocks[0].items[1].exercise`) to 1-based line numbers,
 * so validation errors can point at the exact line of a field.
 */
object JsonLocator {

    fun lines(text: String): Map<String, Int> {
        val out = HashMap<String, Int>()
        val p = Parser(text, out)
        try {
            p.value("$")
        } catch (_: RuntimeException) {
            // Malformed input: return what was mapped so far; the syntax error is reported separately.
        }
        return out
    }

    /** Line of the closest known ancestor of [path]. */
    fun lineFor(lines: Map<String, Int>, path: String): Int? {
        var p = path
        while (true) {
            lines[p]?.let { return it }
            val cut = maxOf(p.lastIndexOf('.'), p.lastIndexOf('['))
            if (cut <= 0) return lines["$"]
            p = p.substring(0, cut)
        }
    }

    fun lineAtOffset(text: String, offset: Int): Int {
        val end = offset.coerceIn(0, text.length)
        var line = 1
        for (i in 0 until end) if (text[i] == '\n') line++
        return line
    }

    private class Parser(val s: String, val out: MutableMap<String, Int>) {
        var i = 0
        var line = 1

        fun ws() {
            while (i < s.length && s[i].isWhitespace()) {
                if (s[i] == '\n') line++
                i++
            }
        }

        fun value(path: String) {
            ws()
            out.putIfAbsent(path, line)
            when (s[i]) {
                '{' -> {
                    i++
                    ws()
                    if (s[i] == '}') { i++; return }
                    while (true) {
                        ws()
                        val keyLine = line
                        val key = string()
                        out.putIfAbsent("$path.$key", keyLine)
                        ws()
                        check(s[i] == ':'); i++
                        value("$path.$key")
                        ws()
                        if (s[i] == ',') { i++; continue }
                        check(s[i] == '}'); i++
                        return
                    }
                }
                '[' -> {
                    i++
                    ws()
                    if (s[i] == ']') { i++; return }
                    var idx = 0
                    while (true) {
                        value("$path[$idx]")
                        idx++
                        ws()
                        if (s[i] == ',') { i++; continue }
                        check(s[i] == ']'); i++
                        return
                    }
                }
                '"' -> string()
                else -> while (i < s.length && s[i] !in ",}] \n\r\t") i++
            }
        }

        fun string(): String {
            check(s[i] == '"')
            i++
            val sb = StringBuilder()
            while (s[i] != '"') {
                if (s[i] == '\\') { sb.append(s[i + 1]); i += 2; continue }
                if (s[i] == '\n') line++
                sb.append(s[i]); i++
            }
            i++
            return sb.toString()
        }
    }
}
