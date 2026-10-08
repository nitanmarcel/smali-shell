package jadx.emu.ext.shell

import jadx.plugins.emu.api.EmuContext
import jadx.plugins.emu.exec.AndroidStubs
import jadx.plugins.emu.exec.Diagnostic
import jadx.plugins.emu.exec.DiagnosticSink
import jadx.plugins.emu.exec.DvmClass
import jadx.plugins.emu.exec.EngineContext
import jadx.plugins.emu.exec.ExecHook
import jadx.plugins.emu.exec.ExecLimits
import jadx.plugins.emu.exec.Frame
import jadx.plugins.emu.exec.HookRegistry
import jadx.plugins.emu.exec.NotHandled
import jadx.plugins.emu.exec.Vm
import jadx.plugins.emu.exec.VmAbort
import jadx.plugins.emu.exec.model.DalvikInsn
import jadx.plugins.emu.exec.model.DexMethod
import jadx.plugins.emu.exec.model.MethodRef
import jadx.plugins.emu.exec.runtime.DvmObject
import jadx.plugins.emu.exec.runtime.DvmThrowable
import jadx.plugins.emu.exec.runtime.UnknownVal
import jadx.plugins.emu.exec.runtime.WideHigh

internal class Session(private val emu: EmuContext) {

    private val source = ScratchSource(emu.source)
    private val stubs = AndroidStubs(emu.engine.android.env)
    private val engine = EngineContext(source, emu.limits, emu.engine.host, stubs)
    private var vm: Vm? = null
    private var traceOut: Appendable? = null
    private var traceDepth = 0
    private var runOut: Appendable? = null
    private val reported = HashSet<String>()

    private val hooks = HookRegistry()
    var limits: ExecLimits = emu.limits
        set(value) { field = value; vm = null }
    var trace = 0

    fun reset() { vm = null }

    fun clearReplacements() {
        for (id in hooks.list().map { it.first }) hooks.remove(id)
        stubs.clear()
    }

    fun hook(sig: String, value: String) {
        val f = replacement(value)
        hooks.add(sig) { call -> f(call.receiver, call.args).let { if (it !== PASS) call.replace(it) } }
    }

    fun stub(sig: String, value: String) {
        val cls = sig.substringBefore("->")
        val name = sig.substringAfter("->").substringBefore('(')
        val f = replacement(value)
        stubs.registerMethod(cls, name) { receiver, args -> f(receiver, args).let { if (it === PASS) NotHandled else it } }
    }

    private fun replacement(value: String): (Any?, List<Any?>) -> Any? {
        if (METHOD_SIG.matches(value)) {
            val cls = value.substringBefore("->")
            val shortId = value.substringAfter("->")
            if (source.method(cls, shortId) == null) throw IllegalArgumentException("no method $value")
            var active = false
            return { receiver, args ->
                if (active) PASS
                else {
                    val target = source.method(cls, shortId) ?: throw IllegalStateException("no method $value")
                    active = true
                    try {
                        val all = if (receiver != null) listOf(receiver) + args else args
                        vm().call(target, all, null)
                    } finally {
                        active = false
                    }
                }
            }
        }
        val v = Literal.parse(value)
        return { _, _ -> v }
    }

    fun setStatic(sig: String, value: Any?) {
        val (cls, key) = fieldKey(sig)
        vm().ensureClinit(cls)
        vm().staticsOf(cls)[key] = value
    }

    fun getStatic(sig: String): Any? {
        val (cls, key) = fieldKey(sig)
        vm().ensureClinit(cls)
        val statics = vm().staticsOf(cls)
        return if (statics.containsKey(key)) statics[key] else UnknownVal(sig.substringAfterLast(':', "Ljava/lang/Object;"))
    }

    private fun fieldKey(sig: String): Pair<String, String> {
        val cls = sig.substringBefore("->")
        val name = sig.substringAfter("->").substringBefore(':')
        require(cls.startsWith("L") && cls.endsWith(";") && name.isNotEmpty()) { "expected Lcls;->name:T" }
        return cls to "$cls.$name"
    }

    private fun vm(): Vm = vm ?: engine.newVm(
        limits,
        hook = Tracer(),
        hooks = hooks,
        androidEnvUnknown = false,
        diagnostics = engine.diagnostics + DiagnosticSink { report(it) },
    ).also { vm = it }

    private fun report(d: Diagnostic) {
        val out = runOut ?: return
        if (d.kind == Diagnostic.Kind.HOST_UNKNOWN_INPUT || d.caller === noopMethod) return
        val line = d.message
        if (reported.add(line)) out.appendLine(line)
    }

    private var entry: DexMethod? = null
    private var loaded: List<String> = emptyList()

    fun load(smali: String, out: Appendable): Boolean {
        val dex = try {
            SmaliAssembler.assemble(smali)
        } catch (e: SmaliError) {
            out.appendLine(e.message ?: "assembly failed")
            return false
        }
        val layer = source.add(dex)
        loaded = layer.allMethods().map { it.declClass }.distinct()
        entry = loaded.firstNotNullOfOrNull { c -> layer.method(c, "run()Ljava/lang/Object;") }
            ?: loaded.firstNotNullOfOrNull { c -> layer.methodsByName(c, "run").firstOrNull { it.isStatic && it.ref.argTypes.isEmpty() } }
            ?: loaded.firstNotNullOfOrNull { c -> layer.method(c, "main([Ljava/lang/String;)V") }
        return true
    }

    fun runEntry(out: Appendable) {
        val entry = entry
        if (entry == null) {
            out.appendLine("loaded ${loaded.joinToString()}; no run() or main(String[]) to execute")
            return
        }
        traceOut = if (trace > 0) out else null
        traceDepth = 0
        runOut = out
        reported.clear()
        val result = try {
            vm().invokeOrThrow(entry)
        } catch (t: DvmThrowable) {
            out.appendLine("threw ${render(t)}")
            return
        } finally {
            traceOut = null
            runOut = null
        }
        if (entry.ref.returnType != "V") out.appendLine(render(result))
    }

    private fun Vm.invokeOrThrow(method: DexMethod): Any? {
        invoke(noop())
        ensureClinit(method.declClass)
        val args = method.ref.argTypes.map { if (it == "[Ljava/lang/String;") arrayOfNulls<String>(0) else null }
        return try {
            call(method, args, null)
        } catch (e: VmAbort) {
            runOut?.appendLine("aborted: ${e.message}")
            UnknownVal(method.ref.returnType)
        }
    }

    private var noopMethod: DexMethod? = null

    private fun noop(): DexMethod = noopMethod ?: run {
        val cls = "${SCRATCH_PREFIX}Noop;"
        source.add(SmaliAssembler.assemble(".class public final $cls\n.super Ljava/lang/Object;\n.method public static run()V\n    .registers 1\n    return-void\n.end method\n"))
        source.method(cls, "run()V")!!.also { noopMethod = it }
    }

    fun render(v: Any?): String = render(v, Int.MAX_VALUE)

    private fun render(v: Any?, limit: Int): String = when (v) {
        null -> "null"
        is UnknownVal -> "unknown" + (v.type?.let { " ($it)" } ?: "")
        is String -> quote(v, limit)
        is Char -> "'$v'"
        is Long -> "${v}L"
        is Float -> "${v}f"
        is Boolean, is Int, is Short, is Byte, is Double -> v.toString()
        is DvmClass -> "class ${v.desc}"
        is DvmObject -> v.type + v.fields.entries.joinToString(", ", " {", "}", limit) { "${it.key.substringAfterLast('.')}=${render(it.value, limit)}" }
        is DvmThrowable -> v.type + (v.message?.let { ": $it" } ?: "")
        is ByteArray -> v.joinToString(", ", "byte[", "]", limit)
        is IntArray -> v.joinToString(", ", "int[", "]", limit)
        is CharArray -> quote(String(v), limit) + " (char[])"
        is LongArray -> v.joinToString(", ", "long[", "]", limit)
        is BooleanArray -> v.joinToString(", ", "boolean[", "]", limit)
        is ShortArray -> v.joinToString(", ", "short[", "]", limit)
        is Array<*> -> v.joinToString(", ", "[", "]", limit) { render(it, limit) }
        else -> v.toString()
    }

    private fun quote(s: String, limit: Int): String {
        val escaped = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        return if (escaped.length <= limit) "\"$escaped\"" else "\"${escaped.take(limit)}…\" (${s.length} chars)"
    }

    private inner class Tracer : ExecHook {
        override fun onEnter(method: DexMethod, frame: Frame) {
            if (method !== noopMethod) traceDepth++
        }

        override fun onExit(method: DexMethod) {
            if (method !== noopMethod) traceDepth--
        }

        override fun onStep(method: DexMethod, insn: DalvikInsn, frame: Frame, pc: Int) {
            val out = traceOut ?: return
            if (method === noopMethod || traceDepth > trace) return
            val regs = insn.regs.filter { frame.get(it) !== WideHigh }.joinToString(", ") { "v$it=" + render(frame.get(it), TRACE_VALUE_LIMIT) }
            val where = method.declClass.substringAfterLast('/').removeSuffix(";") + "->" + method.ref.name
            val ref = insn.ref?.let { " " + renderRef(it) } ?: ""
            val indent = "  ".repeat(traceDepth)
            out.appendLine("$indent$where @${Integer.toHexString(insn.offset)} ${insn.opcode.name.lowercase().replace('_', '-')}$ref $regs".trimEnd())
        }
    }

    private fun renderRef(ref: Any): String = when (ref) {
        is MethodRef -> "${ref.declClass}->${ref.shortId}"
        is jadx.plugins.emu.exec.model.FieldRef -> "${ref.declClass}->${ref.name}:${ref.type}"
        is jadx.plugins.emu.exec.model.TypeRef -> ref.desc
        is jadx.plugins.emu.exec.model.StringRef -> render(ref.value, TRACE_VALUE_LIMIT)
        else -> ref.toString()
    }

    companion object {
        val PASS = Any()
        private const val SCRATCH_PREFIX = "Lemu/shell/S"
        private const val TRACE_VALUE_LIMIT = 64
        private val METHOD_SIG = Regex("""L[\w/$]+;->[\w$<>]+\(.*\).+""")
    }
}
