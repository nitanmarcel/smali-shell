package jadx.emu.ext.shell

import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.writer.builder.DexBuilder
import com.android.tools.smali.dexlib2.writer.io.MemoryDataStore
import com.android.tools.smali.smali.smaliFlexLexer
import com.android.tools.smali.smali.smaliParser
import com.android.tools.smali.smali.smaliTreeWalker
import org.antlr.runtime.CommonTokenStream
import org.antlr.runtime.TokenStream
import org.antlr.runtime.tree.CommonTreeNodeStream
import org.antlr.runtime.tree.TreeNodeStream
import java.io.StringReader

internal class SmaliError(message: String) : RuntimeException(message)

internal object SmaliAssembler {

    fun assemble(source: String, apiLevel: Int = 33): ByteArray {
        val errors = StringBuilder()
        val lexer = smaliFlexLexer(StringReader(source), apiLevel)
        val tokens = CommonTokenStream(lexer)
        val parser = Parser(tokens, errors).apply {
            setVerboseErrors(true)
            setAllowOdex(false)
            setApiLevel(apiLevel)
        }
        val result = parser.smali_file()
        if (parser.numberOfSyntaxErrors > 0 || lexer.numberOfSyntaxErrors > 0) throw SmaliError("parse error:$errors")
        val treeStream = CommonTreeNodeStream(result.tree).apply { tokenStream = tokens }
        val builder = DexBuilder(Opcodes.forApi(apiLevel))
        val walker = Walker(treeStream, errors).apply {
            setApiLevel(apiLevel)
            setVerboseErrors(true)
            setDexBuilder(builder)
        }
        walker.smali_file()
        if (walker.numberOfSyntaxErrors > 0) throw SmaliError("compile error:$errors")
        val store = MemoryDataStore()
        builder.writeTo(store)
        return store.data
    }

    private class Parser(input: TokenStream, private val errors: StringBuilder) : smaliParser(input) {
        override fun emitErrorMessage(msg: String) { errors.append('\n').append(msg) }
    }

    private class Walker(input: TreeNodeStream, private val errors: StringBuilder) : smaliTreeWalker(input) {
        override fun emitErrorMessage(msg: String) { errors.append('\n').append(msg) }
    }
}
