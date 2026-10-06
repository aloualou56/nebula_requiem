package io.github.aloualou56.nebularequiem.platform

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import io.github.aloualou56.nebularequiem.core.SaveStore
import java.io.File
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * The save document on disk: files/saves/nebula_save.json, written atomically (AtomicFile writes
 * a new file and renames it over the old one, so a crash mid-write never leaves half a save), with
 * the previous good version kept in nebula_save.prev.json.
 *
 * Writes run on a background thread and coalesce: the game thread never touches the disk. On the
 * first launch after upgrading from the WebView edition, the save mirror that app kept in
 * SharedPreferences is imported (and migrated by Save).
 */
class FileSaveStore(context: Context) : SaveStore {
    private val app = context.applicationContext
    private val dir = File(app.filesDir, "saves")
    private val main = AtomicFile(File(dir, "nebula_save.json"))
    private val prev = AtomicFile(File(dir, "nebula_save.prev.json"))
    private val io: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "nebula-save").apply { isDaemon = true } }

    private val lock = Any()
    private var latest: String? = null
    private var queued = false
    private var future: Future<*>? = null
    /** The text most recently written successfully (becomes the backup on the next write). */
    private var lastWritten: String? = null
    @Volatile private var lastWriteOk = true

    /** True when read() returned the WebView edition's save rather than a native file. */
    var importedLegacy = false
        private set

    override fun read(): String? {
        val text = readFile(main)
        if (text != null) { lastWritten = text; return text }
        if (!main.baseFile.exists()) {
            val legacy = app.getSharedPreferences(LEGACY_SAVE_PREFS, Context.MODE_PRIVATE).getString(LEGACY_SAVE_KEY, null)
            if (!legacy.isNullOrBlank()) { importedLegacy = true; return legacy }
        }
        return null
    }

    override fun readBackup(): String? = readFile(prev)

    override fun write(text: String) {
        synchronized(lock) {
            latest = text
            if (!queued) { queued = true; future = io.submit { writeLatest() } }
        }
        if (!lastWriteOk) throw IOException("The last save could not be written")
    }

    override fun sync() {
        val f = synchronized(lock) { future } ?: return
        try { f.get(2, TimeUnit.SECONDS) } catch (e: Exception) { Log.w(TAG, "save sync", e) }
    }

    fun close() {
        sync()
        io.shutdown()
    }

    private fun writeLatest() {
        val text: String
        synchronized(lock) { queued = false; text = latest ?: return }
        lastWriteOk = try {
            if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot create $dir")
            writeFile(main, text)
            val old = lastWritten
            if (old != null && old != text) writeFile(prev, old)
            lastWritten = text
            true
        } catch (e: IOException) {
            Log.e(TAG, "save failed", e)
            false
        }
    }

    private fun writeFile(f: AtomicFile, text: String) {
        val out = f.startWrite()
        try {
            out.write(text.toByteArray(Charsets.UTF_8))
            f.finishWrite(out)   // fsync + rename
        } catch (e: IOException) {
            f.failWrite(out)
            throw e
        }
    }

    private fun readFile(f: AtomicFile): String? = try {
        if (f.baseFile.length() > MAX_BYTES) null else f.readFully().toString(Charsets.UTF_8).takeIf { it.isNotBlank() }
    } catch (e: IOException) {
        null   // missing or unreadable
    }

    companion object {
        private const val TAG = "NebulaRequiem"
        private const val MAX_BYTES = 1_000_000L
        const val LEGACY_SAVE_PREFS = "nebula_save"
        const val LEGACY_SAVE_KEY = "save"
    }
}
