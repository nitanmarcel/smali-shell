package jadx.emu.ext.shell

import jadx.plugins.emu.api.EmuExtension
import jadx.plugins.emu.api.EmuExtensionContext
import jadx.plugins.emu.api.EmuExtensionInfo
import jadx.plugins.emu.api.ExtensionMode
import java.nio.file.Files
import java.nio.file.Path

class ShellExtension : EmuExtension {

    override fun info(): EmuExtensionInfo = EmuExtensionInfo(
        id = "smali-shell",
        name = "Smali shell",
        description = "Runs smali against the loaded classes in the emulator",
        mode = ExtensionMode.ON_DEMAND,
        requiredEmuVersion = "0.1.0",
    )

    override fun init(ctx: EmuExtensionContext) {
        val options = ShellOptions()
        ctx.registerOptions(options, perProject = false)
        val session by lazy { Session(ctx.emu) }
        val gui = ctx.gui
        if (gui != null) {
            val window by lazy { ShellWindow(gui.mainFrame, session) }
            gui.addMenuAction("Smali shell") { window.show() }
        }
        if (options.script.isNotBlank()) {
            val path = Path.of(options.script)
            if (!Files.isRegularFile(path)) {
                ctx.log.warn("script not found: {}", path)
                return
            }
            session.trace = options.trace
            val out = StringBuilder()
            Script(session).execute(Files.readString(path), out)
            System.out.print(out)
            System.out.flush()
        }
    }
}
