package jadx.emu.ext.shell

import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea
import org.fife.ui.rsyntaxtextarea.Token
import org.fife.ui.rtextarea.RTextScrollPane
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import javax.swing.AbstractAction
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JDialog
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSpinner
import javax.swing.JSplitPane
import javax.swing.JTextArea
import javax.swing.KeyStroke
import javax.swing.SpinnerNumberModel
import javax.swing.SwingUtilities

internal class ShellWindow(private val owner: JFrame, private val session: Session) {

    private val script = Script(session)
    private val dialog = JDialog(owner, "Smali shell", false)
    private val input = RSyntaxTextArea(14, 90)
    private val output = JTextArea()
    private val trace = JSpinner(SpinnerNumberModel(0, 0, 99, 1))
    private var busy = false

    init {
        runCatching { Class.forName("jadx.gui.ui.codearea.AbstractCodeArea", true, owner.javaClass.classLoader) }
        applyTheme(input)
        input.syntaxEditingStyle = SMALI
        input.tabSize = 4
        input.isCodeFoldingEnabled = true
        input.text = TEMPLATE
        output.font = input.font
        output.isEditable = false
        output.lineWrap = true
        output.background = input.background
        output.foreground = input.syntaxScheme.getStyle(Token.IDENTIFIER).foreground ?: input.foreground
        output.caretColor = output.foreground

        val run = JButton("Run (Ctrl+Enter)").apply { addActionListener { runInput() } }
        val clear = JButton("Clear output").apply { addActionListener { output.text = "" } }
        val reset = JButton("Reset state").apply { addActionListener { session.reset(); append("state reset\n") } }
        val help = JButton("Help").apply { addActionListener { append(Script.HELP) } }
        trace.addChangeListener { session.trace = trace.value as Int }
        trace.maximumSize = trace.preferredSize
        trace.toolTipText = "Trace depth: 0 off, 1 the entry method, 2 plus the methods it calls, …"

        val bar = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            border = BorderFactory.createEmptyBorder(4, 4, 4, 4)
            add(run); add(Box.createHorizontalStrut(10)); add(JLabel("Trace ")); add(trace); add(Box.createHorizontalGlue())
            add(reset); add(Box.createHorizontalStrut(6)); add(clear); add(Box.createHorizontalStrut(6)); add(help)
        }
        input.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, KeyEvent.CTRL_DOWN_MASK), "run")
        input.actionMap.put("run", object : AbstractAction() { override fun actionPerformed(e: ActionEvent) { runInput() } })
        input.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_L, KeyEvent.CTRL_DOWN_MASK), "clear")
        input.actionMap.put("clear", object : AbstractAction() { override fun actionPerformed(e: ActionEvent) { output.text = "" } })

        val split = JSplitPane(JSplitPane.VERTICAL_SPLIT, RTextScrollPane(input), JScrollPane(output)).apply { resizeWeight = 0.55 }
        dialog.contentPane.apply {
            layout = BorderLayout()
            add(bar, BorderLayout.NORTH)
            add(split, BorderLayout.CENTER)
        }
        dialog.preferredSize = Dimension(960, 720)
        dialog.pack()
        dialog.setLocationRelativeTo(owner)
    }

    fun show() {
        dialog.isVisible = true
        dialog.toFront()
        input.requestFocusInWindow()
    }

    private fun applyTheme(area: RSyntaxTextArea) {
        runCatching {
            val manager = owner.javaClass.getMethod("getEditorThemeManager").invoke(owner)
            manager.javaClass.getMethod("apply", RSyntaxTextArea::class.java).invoke(manager, area)
        }
    }

    private fun runInput() {
        if (busy) return
        val text = input.text
        if (text.isBlank()) return
        busy = true
        val out = TailOutput(output.text)
        Thread({
            runCatching { script.execute(text, out) }.onFailure { out.appendLine("error: $it") }
            out.appendLine()
            out.publish()
            SwingUtilities.invokeLater { busy = false }
        }, "smali-shell").apply { isDaemon = true }.start()
    }

    private inner class TailOutput(existing: String) : Appendable {
        private val buf = StringBuilder(existing)
        private var dropped = false
        private var lastPublish = System.nanoTime()

        override fun append(csq: CharSequence?): Appendable { buf.append(csq); trim(); return this }
        override fun append(csq: CharSequence?, start: Int, end: Int): Appendable { buf.append(csq, start, end); trim(); return this }
        override fun append(c: Char): Appendable { buf.append(c); trim(); return this }

        private fun trim() {
            if (buf.length > OUTPUT_LIMIT) {
                buf.delete(0, buf.length - OUTPUT_LIMIT)
                dropped = true
            }
            if (System.nanoTime() - lastPublish > 250_000_000L) publish()
        }

        fun publish() {
            val text = (if (dropped) DROPPED else "") + buf
            lastPublish = System.nanoTime()
            SwingUtilities.invokeLater {
                output.text = text
                output.caretPosition = output.document.length
            }
        }
    }

    private fun append(s: String) {
        output.append(s)
        output.caretPosition = output.document.length
    }

    private companion object {
        const val SMALI = "text/smali"
        const val OUTPUT_LIMIT = 256 * 1024
        const val DROPPED = "… earlier output dropped …\n"
        val TEMPLATE = """
            .class public Lemu/shell/Main;
            .super Ljava/lang/Object;

            .method public static run()Ljava/lang/Object;
                .registers 4
                const-string v0, "hello"
                return-object v0
            .end method
        """.trimIndent() + "\n"
    }
}
