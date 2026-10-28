package com.rfsat.pocketgnss.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors

/**
 * The app's own log (Tools > App log): errors, warnings and information about connections, corrections, the phone
 * RTK engine, the app's lifecycle and crashes, to find out afterwards what happened in the field. Kept in memory for
 * the screen (last [MAX_ENTRIES]) and in files/logs/app.log, which is rotated at [MAX_BYTES] (one older file kept),
 * so it survives a crash and restarts. Nothing in it leaves the phone unless the user shares it; no passwords are
 * ever written to it.
 */
object AppLog {
    enum class Level(val letter: Char) { ERROR('E'), WARN('W'), INFO('I') }

    data class Entry(val timeMs: Long, val level: Level, val tag: String, val message: String) {
        fun line(): String = "${stamp(timeMs)} ${level.letter}/$tag: $message"
    }

    const val MAX_ENTRIES = 2000
    const val MAX_BYTES = 1_000_000L

    /** Recording on (Tools > App log); off records nothing new except crashes. */
    @Volatile var enabled = true

    private val buf = ArrayDeque<Entry>()
    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()
    @Volatile private var file: File? = null
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "app-log").apply { isDaemon = true } }
    /** Last time each throttled key was logged. */
    private val throttle = HashMap<String, Long>()

    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    fun stamp(ms: Long): String = synchronized(fmt) { fmt.format(Date(ms)) }

    /** Opens the log in [dir] and reloads its last entries for the screen. */
    fun init(dir: File) {
        dir.mkdirs()
        val f = File(dir, "app.log"); file = f
        val old = try { if (f.isFile) f.readLines().takeLast(MAX_ENTRIES) else emptyList() } catch (_: Exception) { emptyList() }
        synchronized(buf) {
            buf.clear()
            old.mapNotNull { parse(it) }.forEach { buf.addLast(it) }
            _entries.value = buf.toList()
        }
    }

    fun e(tag: String, message: String, t: Throwable? = null) = add(Level.ERROR, tag, message + (t?.let { "\n" + trace(it) } ?: ""))
    fun w(tag: String, message: String) = add(Level.WARN, tag, message)
    fun i(tag: String, message: String) = add(Level.INFO, tag, message)

    /** As [add], but at most once per [everyMs] for the same [key] (for conditions that repeat every epoch). */
    fun throttled(key: String, everyMs: Long, level: Level, tag: String, message: String, now: Long = System.currentTimeMillis()) {
        synchronized(throttle) {
            val last = throttle[key]
            if (last != null && now - last < everyMs) return
            throttle[key] = now
        }
        add(level, tag, message, now)
    }

    fun add(level: Level, tag: String, message: String, now: Long = System.currentTimeMillis()) {
        if (!enabled) return
        val entry = Entry(now, level, tag, message)
        synchronized(buf) {
            buf.addLast(entry)
            while (buf.size > MAX_ENTRIES) buf.removeFirst()
            _entries.value = buf.toList()
        }
        try { android.util.Log.println(when (level) { Level.ERROR -> 6; Level.WARN -> 5; Level.INFO -> 4 }, "PocketGNSS/$tag", message) } catch (_: Throwable) {}
        val f = file ?: return
        try { io.execute { append(f, entry.line()) } } catch (_: Exception) {}
    }

    /** A crash: written at once, on the crashing thread, because the process is about to end. */
    fun crash(thread: Thread, t: Throwable) {
        val entry = Entry(System.currentTimeMillis(), Level.ERROR, "Crash", "Uncaught exception in thread \"${thread.name}\"\n" + trace(t))
        synchronized(buf) { buf.addLast(entry) }
        file?.let { append(it, entry.line()) }
    }

    @Synchronized private fun append(f: File, line: String) {
        try {
            if (f.length() > MAX_BYTES) {
                val older = File(f.parentFile, "app.1.log"); older.delete(); f.renameTo(older)
            }
            f.appendText(line.replace("\n", "\n    ") + "\n")
        } catch (_: Exception) {}
    }

    /** The whole log, older file first, for sharing. */
    fun text(): String {
        val f = file ?: return synchronized(buf) { buf.joinToString("\n") { it.line() } }
        val older = File(f.parentFile, "app.1.log")
        return try { (if (older.isFile) older.readText() else "") + (if (f.isFile) f.readText() else "") } catch (_: Exception) { "" }
    }

    fun clear() {
        synchronized(buf) { buf.clear(); _entries.value = emptyList() }
        file?.let { f -> io.execute { f.delete(); File(f.parentFile, "app.1.log").delete() } }
    }

    fun trace(t: Throwable): String = StringWriter().also { t.printStackTrace(PrintWriter(it)) }.toString().trimEnd().lines().take(40).joinToString("\n")

    private val lineRe = Regex("""^(\d{4}-\d\d-\d\d \d\d:\d\d:\d\d\.\d{3}) ([EWI])/([^:]+): (.*)$""")

    /** Reads back one line of the file (continuation lines of a stack trace are dropped from the screen copy). */
    fun parse(line: String): Entry? {
        val m = lineRe.find(line) ?: return null
        val t = try { synchronized(fmt) { fmt.parse(m.groupValues[1])?.time } } catch (_: Exception) { null } ?: return null
        val level = Level.entries.first { it.letter == m.groupValues[2][0] }
        return Entry(t, level, m.groupValues[3], m.groupValues[4])
    }
}
