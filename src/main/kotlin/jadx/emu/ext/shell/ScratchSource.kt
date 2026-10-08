package jadx.emu.ext.shell

import jadx.plugins.emu.exec.ClassInfo
import jadx.plugins.emu.exec.MethodSource
import jadx.plugins.emu.exec.input.DexInputSource
import jadx.plugins.emu.exec.model.DexMethod
import java.nio.file.Files

internal class ScratchSource(private val base: MethodSource) : MethodSource {

    private val layers = ArrayList<DexInputSource>()

    fun add(dex: ByteArray): DexInputSource {
        val tmp = Files.createTempFile("jadx-emu-shell", ".dex")
        try {
            Files.write(tmp, dex)
            val src = DexInputSource.load(tmp.toFile())
            layers.add(0, src)
            return src
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    fun remove(src: DexInputSource) { layers.remove(src) }

    private fun owner(classDesc: String): MethodSource = layers.firstOrNull { it.classInfo(classDesc) != null } ?: base

    override fun method(classDesc: String, shortId: String): DexMethod? = owner(classDesc).method(classDesc, shortId)

    override fun classInfo(classDesc: String): ClassInfo? = owner(classDesc).classInfo(classDesc)

    override fun methodsByName(classDesc: String, name: String): List<DexMethod> = owner(classDesc).methodsByName(classDesc, name)

    override fun methodsOf(classDesc: String): List<DexMethod> = owner(classDesc).methodsOf(classDesc)

    override fun allMethods(): List<DexMethod> = layers.flatMap { it.allMethods() } + base.allMethods()

    override fun isNative(classDesc: String, shortId: String): Boolean = owner(classDesc).isNative(classDesc, shortId)
}
