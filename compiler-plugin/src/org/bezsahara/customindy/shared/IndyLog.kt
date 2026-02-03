package org.bezsahara.customindy.shared

import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector

object IndyLog {
    val enabled: Boolean = System.getProperty("bezsahara.ci.debug")?.toBoolean() ?: false

    @Volatile
    var collector: MessageCollector? = null

    inline fun warn(block: () -> String) {
        if (enabled) {
            warn(block())
        }
    }

    fun warn(message: String) {
        val target = collector
        if (target != null) {
            target.report(CompilerMessageSeverity.WARNING, "[customindy] $message")
        } else {
            System.err.println("[customindy][WARN] $message")
        }
    }
}
