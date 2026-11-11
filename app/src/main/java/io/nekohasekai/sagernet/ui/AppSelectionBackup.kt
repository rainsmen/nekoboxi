package io.nekohasekai.sagernet.ui

/** Validate the entire clipboard backup before changing either mode or selection. */
internal data class AppSelectionBackup(val bypass: Boolean, val packages: String) {
    companion object {
        fun parse(text: String): AppSelectionBackup {
            require(text.length <= 1024 * 1024)
            val lines = text.replace("\r\n", "\n").split('\n')
            require(lines.size >= 2)
            val bypass = when (lines.first()) {
                "true" -> true
                "false" -> false
                else -> throw IllegalArgumentException("Invalid application selection mode")
            }
            val packages = lines.drop(1).filter { it.isNotEmpty() }
            val packageName = Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*")
            require(packages.all { it.length <= 255 && packageName.matches(it) })
            return AppSelectionBackup(bypass, packages.distinct().joinToString("\n"))
        }
    }
}
