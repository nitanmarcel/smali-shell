package jadx.emu.ext.shell

import jadx.api.plugins.options.OptionFlag
import jadx.api.plugins.options.impl.BasePluginOptionsBuilder

internal class ShellOptions : BasePluginOptionsBuilder() {
    var script: String = ""
        private set
    var trace: Int = 0
        private set

    override fun registerOptions() {
        strOption("script")
            .description("Script file to run after the inputs are loaded (output goes to the log)")
            .defaultValue("")
            .flags(OptionFlag.NOT_CHANGING_CODE)
            .setter { script = it }
        intOption("trace")
            .description("Trace depth for the script: 0 off, 1 the entry method, 2 plus the methods it calls, …")
            .defaultValue(0)
            .flags(OptionFlag.NOT_CHANGING_CODE)
            .setter { trace = it }
    }
}
