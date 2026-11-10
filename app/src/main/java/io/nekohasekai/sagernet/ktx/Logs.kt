package io.nekohasekai.sagernet.ktx

import libcore.Libcore
import java.io.InputStream
import java.io.OutputStream

object Logs {

    private fun write(message: String) = Libcore.nekoLogPrintln(LogRedactor.redact(message))

    private fun mkTag(): String {
        val stackTrace = Thread.currentThread().stackTrace
        return stackTrace[4].className.substringAfterLast(".")
    }

    // level int use logrus.go

    fun d(message: String) {
        write("[Debug] [${mkTag()}] $message")
    }

    fun d(message: String, exception: Throwable) {
        write("[Debug] [${mkTag()}] $message" + "\n" + exception.stackTraceToString())
    }

    fun i(message: String) {
        write("[Info] [${mkTag()}] $message")
    }

    fun i(message: String, exception: Throwable) {
        write("[Info] [${mkTag()}] $message" + "\n" + exception.stackTraceToString())
    }

    fun w(message: String) {
        write("[Warning] [${mkTag()}] $message")
    }

    fun w(message: String, exception: Throwable) {
        write("[Warning] [${mkTag()}] $message" + "\n" + exception.stackTraceToString())
    }

    fun w(exception: Throwable) {
        write("[Warning] [${mkTag()}] " + exception.stackTraceToString())
    }

    fun e(message: String) {
        write("[Error] [${mkTag()}] $message")
    }

    fun e(message: String, exception: Throwable) {
        write("[Error] [${mkTag()}] $message" + "\n" + exception.stackTraceToString())
    }

    fun e(exception: Throwable) {
        write("[Error] [${mkTag()}] " + exception.stackTraceToString())
    }

}

fun InputStream.use(out: OutputStream) {
    use { input ->
        out.use { output ->
            input.copyTo(output)
        }
    }
}