package jadx.emu.ext.shell

import jadx.plugins.emu.exec.ExecLimits

internal class Script(private val session: Session) {

    fun execute(text: String, out: Appendable) {
        val smali = StringBuilder()
        val directives = ArrayList<Pair<String, String>>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            val cmd = line.substringBefore(' ')
            if (cmd in DIRECTIVES) directives.add(cmd.substring(1) to line.substringAfter(' ', "").trim())
            else smali.appendLine(raw)
        }
        if (smali.isNotBlank() && !session.load(smali.toString(), out)) return
        session.clearReplacements()
        for ((cmd, rest) in directives) directive(cmd, rest, out)
        if (smali.isNotBlank()) session.runEntry(out)
    }

    private fun directive(cmd: String, rest: String, out: Appendable) {
        runCatching {
            when (cmd) {
                "limits" -> {
                    val p = rest.split(Regex("\\s+"))
                    session.limits = ExecLimits(
                        maxSteps = p.getOrNull(0)?.toInt() ?: session.limits.maxSteps,
                        maxMillis = p.getOrNull(1)?.toLong() ?: session.limits.maxMillis,
                        maxDepth = p.getOrNull(2)?.toInt() ?: session.limits.maxDepth,
                    )
                }
                "hook" -> {
                    val (sig, value) = assignment(rest)
                    session.hook(sig, value)
                }
                "stub" -> {
                    val (sig, value) = assignment(rest)
                    session.stub(sig, value)
                }
                "sput" -> {
                    val (sig, value) = assignment(rest)
                    session.setStatic(sig, Literal.parse(value))
                }
                "sget" -> out.appendLine(session.render(session.getStatic(rest)))
            }
        }.onFailure { out.appendLine("error: ${it.message ?: it.toString()}") }
    }

    private fun assignment(s: String): Pair<String, String> {
        val i = s.indexOf('=')
        require(i > 0) { "expected <target> = <value>" }
        return s.substring(0, i).trim() to s.substring(i + 1).trim()
    }

    companion object {
        val DIRECTIVES = setOf(".limits", ".hook", ".stub", ".sput", ".sget")

        val HELP = """
            .hook Lcls;->m(args)ret = value   every call to m returns value
            .stub Lcls;->name = value         framework method returns value
            .sput Lcls;->f:T = value          write a static field
            .sget Lcls;->f:T                  read a static field
            .limits <steps> [ms] [depth]      emulation limits
        """.trimIndent() + "\n"
    }
}
