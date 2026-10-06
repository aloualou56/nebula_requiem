package io.github.aloualou56.nebularequiem.core

/**
 * A small, strict JSON reader/writer for the save document. Parsed values are Map<String, Any?>,
 * List<Any?>, String, Double, Boolean or null. Malformed input throws [JsonException]; callers treat
 * that as a corrupted save. Pure Kotlin so it runs in JVM unit tests without Android stubs.
 */
class JsonException(msg: String) : Exception(msg)

object Json {
    private const val MAX_DEPTH = 64

    fun parse(text: String): Any? {
        val p = Parser(text)
        p.ws()
        val v = p.value(0)
        p.ws()
        if (p.i != text.length) throw JsonException("trailing data at ${p.i}")
        return v
    }

    private class Parser(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && (s[i] == ' ' || s[i] == '\n' || s[i] == '\r' || s[i] == '\t')) i++ }
        fun value(depth: Int): Any? {
            if (depth > MAX_DEPTH) throw JsonException("too deep")
            if (i >= s.length) throw JsonException("unexpected end")
            return when (val c = s[i]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c in '0'..'9') num() else throw JsonException("unexpected '$c' at $i")
            }
        }
        fun lit(w: String, v: Any?): Any? {
            if (!s.startsWith(w, i)) throw JsonException("bad literal at $i")
            i += w.length
            return v
        }
        fun num(): Double {
            val start = i
            if (s[i] == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == 'e' || s[i] == 'E' || s[i] == '+' || s[i] == '-')) i++
            return s.substring(start, i).toDoubleOrNull() ?: throw JsonException("bad number at $start")
        }
        fun str(): String {
            i++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) throw JsonException("unterminated string")
                val c = s[i++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        if (i >= s.length) throw JsonException("bad escape")
                        when (val e = s[i++]) {
                            '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000c'); 'n' -> sb.append('\n')
                            'r' -> sb.append('\r'); 't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) throw JsonException("bad unicode escape")
                                sb.append(s.substring(i, i + 4).toIntOrNull(16)?.toChar() ?: throw JsonException("bad unicode escape"))
                                i += 4
                            }
                            else -> throw JsonException("bad escape '$e'")
                        }
                    }
                    c < ' ' -> throw JsonException("control character in string")
                    else -> sb.append(c)
                }
            }
        }
        fun arr(depth: Int): List<Any?> {
            i++
            val out = ArrayList<Any?>()
            ws()
            if (i < s.length && s[i] == ']') { i++; return out }
            while (true) {
                ws(); out.add(value(depth + 1)); ws()
                if (i >= s.length) throw JsonException("unterminated array")
                when (s[i++]) { ',' -> continue; ']' -> return out; else -> throw JsonException("expected , or ] at ${i - 1}") }
            }
        }
        fun obj(depth: Int): Map<String, Any?> {
            i++
            val out = LinkedHashMap<String, Any?>()
            ws()
            if (i < s.length && s[i] == '}') { i++; return out }
            while (true) {
                ws()
                if (i >= s.length || s[i] != '"') throw JsonException("expected key at $i")
                val k = str()
                ws()
                if (i >= s.length || s[i++] != ':') throw JsonException("expected : at ${i - 1}")
                ws()
                out[k] = value(depth + 1)
                ws()
                if (i >= s.length) throw JsonException("unterminated object")
                when (s[i++]) { ',' -> continue; '}' -> return out; else -> throw JsonException("expected , or } at ${i - 1}") }
            }
        }
    }

    fun write(v: Any?): String { val sb = StringBuilder(); write(sb, v); return sb.toString() }

    private fun write(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is Boolean -> sb.append(if (v) "true" else "false")
            is Number -> {
                val d = v.toDouble()
                if (d.isNaN() || d.isInfinite()) sb.append("0")
                else if (d == Math.rint(d) && kotlin.math.abs(d) < 1e15) sb.append(d.toLong())
                else sb.append(d)
            }
            is String -> {
                sb.append('"')
                for (c in v) {
                    when {
                        c == '"' -> sb.append("\\\""); c == '\\' -> sb.append("\\\\")
                        c == '\n' -> sb.append("\\n"); c == '\r' -> sb.append("\\r"); c == '\t' -> sb.append("\\t")
                        c < ' ' -> sb.append(String.format("\\u%04x", c.code))
                        else -> sb.append(c)
                    }
                }
                sb.append('"')
            }
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, x) in v) {
                    if (!first) sb.append(',')
                    first = false
                    write(sb, k.toString()); sb.append(':'); write(sb, x)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (x in v) { if (!first) sb.append(','); first = false; write(sb, x) }
                sb.append(']')
            }
            is IntArray -> write(sb, v.toList())
            is DoubleArray -> write(sb, v.toList())
            else -> write(sb, v.toString())
        }
    }
}
