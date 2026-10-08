package jadx.emu.ext.shell

internal object Literal {

    fun parse(text: String): Any? {
        val t = text.trim()
        return when {
            t == "null" -> null
            t == "true" -> true
            t == "false" -> false
            t.length >= 2 && t.startsWith('"') && t.endsWith('"') -> unescape(t.substring(1, t.length - 1))
            t.length >= 3 && t.startsWith('\'') && t.endsWith('\'') -> unescape(t.substring(1, t.length - 1)).single()
            t.endsWith("L") || t.endsWith("l") -> number(t.dropLast(1)).toLong()
            t.endsWith("f") || t.endsWith("F") -> t.dropLast(1).toFloat()
            t.contains('.') || t.contains('e') && !t.startsWith("0x") -> t.toDouble()
            else -> number(t).let { if (it in Int.MIN_VALUE..Int.MAX_VALUE) it.toInt() else it }
        }
    }

    private fun number(t: String): Long {
        val neg = t.startsWith('-')
        val body = t.removePrefix("-")
        val v = when {
            body.startsWith("0x") || body.startsWith("0X") -> java.lang.Long.parseUnsignedLong(body.substring(2), 16)
            else -> body.toLong()
        }
        return if (neg) -v else v
    }

    private fun unescape(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '\\' || i + 1 >= s.length) { sb.append(c); i++; continue }
            when (val e = s[i + 1]) {
                'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r')
                '0' -> sb.append('\u0000'); '\\' -> sb.append('\\'); '"' -> sb.append('"'); '\'' -> sb.append('\'')
                'u' -> { sb.append(s.substring(i + 2, i + 6).toInt(16).toChar()); i += 4 }
                else -> { sb.append('\\'); sb.append(e) }
            }
            i += 2
        }
        return sb.toString()
    }
}
