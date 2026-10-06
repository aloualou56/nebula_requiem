package io.github.aloualou56.nebularequiem.platform

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps a crash on a player's phone from going unexplained. An uncaught exception on any thread is
 * written to a file before the process dies, and the next launch offers it (with the build and the
 * device) in a dialog the player can copy from. Native crashes (a GPU driver, the render thread)
 * never reach the JVM handler; for those the next launch reads Android's own record of how the
 * process ended ([ApplicationExitInfo]), including the readable parts of the tombstone.
 */
object CrashReport {
    private const val CRASH_FILE = "last-crash.txt"
    /**
     * "versionCode:timestamp" of the newest exit record already reported, so each crash is shown
     * once, and exits from earlier builds (whose bugs may be fixed) are not shown at all.
     */
    private const val SEEN_FILE = "last-exit-seen.txt"

    fun install(context: Context) {
        val app = context.applicationContext
        val file = File(app.filesDir, CRASH_FILE)
        val header = header(app)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            try {
                val sw = StringWriter()
                PrintWriter(sw).use { pw ->
                    pw.println(header)
                    pw.println("When: ${stamp(System.currentTimeMillis())}")
                    pw.println("Thread: ${thread.name}")
                    e.printStackTrace(pw)
                }
                file.writeText(sw.toString())
            } catch (_: Throwable) {}
            previous?.uncaughtException(thread, e)
        }
    }

    /** The previous run's crash report, if it crashed and hasn't been shown yet; null otherwise. */
    fun takePending(context: Context): String? {
        val app = context.applicationContext
        val file = File(app.filesDir, CRASH_FILE)
        val java = try { if (file.isFile) file.readText().takeIf { it.isNotBlank() } else null } catch (_: Exception) { null }
        file.delete()
        val exit = try { newestExit(app, javaReported = java != null) } catch (_: Exception) { null }
        return when {
            java != null && exit != null -> "$java\n\n$exit"
            java != null -> java
            exit != null -> header(app) + "\n" + exit
            else -> null
        }
    }

    /**
     * The newest abnormal exit Android recorded for this app that hasn't been reported yet: a
     * native crash, a hang (ANR), or a Java crash this handler didn't get to write down (for
     * example one in a build without it).
     */
    private fun newestExit(app: Context, javaReported: Boolean): String? {
        val am = app.getSystemService(ActivityManager::class.java) ?: return null
        val exits = am.getHistoricalProcessExitReasons(app.packageName, 0, 5)
        val seenFile = File(app.filesDir, SEEN_FILE)
        val build = try { app.packageManager.getPackageInfo(app.packageName, 0).longVersionCode } catch (_: Exception) { 0L }
        val (seenBuild, seenAt) = try {
            seenFile.readText().trim().split(':').let { it[0].toLong() to it[1].toLong() }
        } catch (_: Exception) { -1L to 0L }
        val newest = exits.maxOfOrNull { it.timestamp } ?: 0L
        if (seenBuild != build || newest > seenAt) try { seenFile.writeText("$build:${maxOf(newest, seenAt)}") } catch (_: Exception) {}
        if (seenBuild != build) return null   // first run of this build: earlier builds' exits are history
        val seen = seenAt
        val e = exits.firstOrNull {
            it.timestamp > seen && (it.reason == ApplicationExitInfo.REASON_CRASH_NATIVE || it.reason == ApplicationExitInfo.REASON_ANR ||
                (it.reason == ApplicationExitInfo.REASON_CRASH && !javaReported))
        } ?: return null
        val kind = when (e.reason) {
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "Native crash"
            ApplicationExitInfo.REASON_ANR -> "Not responding (ANR)"
            else -> "Crash"
        }
        val sb = StringBuilder()
        sb.append(kind).append(" at ").append(stamp(e.timestamp)).append('\n')
        e.description?.let { sb.append("Description: ").append(it).append('\n') }
        sb.append("Status: ").append(e.status).append('\n')
        when (e.reason) {
            ApplicationExitInfo.REASON_CRASH_NATIVE -> tombstoneText(e)?.let { sb.append("Tombstone (readable parts):\n").append(it) }
            ApplicationExitInfo.REASON_ANR -> anrThreads(e)?.let { sb.append(it) }
            else -> {}
        }
        return sb.toString()
    }

    /** From an ANR's thread dump, the stacks of the main thread and the game thread. */
    private fun anrThreads(e: ApplicationExitInfo): String? {
        val text = try { e.traceInputStream?.bufferedReader()?.use { it.readText() } } catch (_: Exception) { null } ?: return null
        val out = StringBuilder()
        var keep = false
        var lines = 0
        for (line in text.lineSequence()) {
            if (line.startsWith("\"")) keep = line.startsWith("\"main\"") || line.startsWith("\"nebula-")
            if (keep && lines < 120) { out.append(line).append('\n'); lines++ }
        }
        return out.toString().ifBlank { null }
    }

    /**
     * The tombstone is a protobuf; rather than decode it, keep its printable runs that name the
     * signal, the faulting thread and the backtrace's libraries and functions.
     */
    private fun tombstoneText(e: ApplicationExitInfo): String? {
        val bytes = try { e.traceInputStream?.use { it.readBytes() } } catch (_: Exception) { null } ?: return null
        val runs = ArrayList<String>()
        val cur = StringBuilder()
        fun flush() { if (cur.length >= 6) runs.add(cur.toString()); cur.setLength(0) }
        for (b in bytes) { val c = b.toInt() and 0xFF; if (c in 0x20..0x7E) cur.append(c.toChar()) else flush() }
        flush()
        val keep = runs.filter { r -> KEYWORDS.any { r.contains(it) } }.distinct().take(80)
        return if (keep.isEmpty()) null else keep.joinToString("\n")
    }

    private val KEYWORDS = listOf("SIG", ".so", "nebula", "Nebula", "::", "Abort", "abort", "Check failed", "Thread", "java.", "android.", "art::", "hwui", "Skia", "Sk")

    private fun header(app: Context): String {
        val version = try { app.packageManager.getPackageInfo(app.packageName, 0).let { "${it.versionName} (${it.longVersionCode})" } } catch (_: Exception) { "?" }
        return "Nebula Requiem $version · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.FINGERPRINT}"
    }

    private fun stamp(ms: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date(ms))
}
