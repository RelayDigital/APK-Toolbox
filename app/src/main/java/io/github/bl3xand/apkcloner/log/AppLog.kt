package io.github.bl3xand.apkcloner.log

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import io.github.bl3xand.apkcloner.BuildConfig
import java.io.File
import java.util.Locale

/**
 * The app's log: what was checked, downloaded, cloned, merged and installed, and what failed.
 * Every tool writes here, so one place tells what happened and when.
 *
 * Every entry goes to two places, both written by one background thread, so a caller - on the
 * UI thread or anywhere else - only ever hands the entry over: the SQLite store the log sheet
 * reads, and the [RingLog] ring of files that keeps the whole history within a fixed budget
 * for debugging a release build. The ring is never shown to the user.
 */
object AppLog {
    private const val TAG = "ApkToolbox"
    private const val TABLE = "logs"
    /** How long an entry is kept. */
    const val KEPT_DAYS = 30
    private const val MAX_AGE_MS = KEPT_DAYS * 24L * 60 * 60 * 1000
    private const val LOGS_DIR = "logs"

    @Volatile
    private var helper: SQLiteOpenHelper? = null

    @Volatile
    private var ring: RingLog? = null

    @Volatile
    private var writer: Handler? = null

    @Synchronized
    fun init(context: Context) {
        if (helper != null) return
        helper = object : SQLiteOpenHelper(context.applicationContext, "sources_logs.db", null, 1) {
            override fun onCreate(db: SQLiteDatabase) {
                db.execSQL(
                    "create table if not exists $TABLE (_id integer primary key autoincrement, " +
                        "level integer not null, message text not null, timestamp integer not null)",
                )
                db.execSQL("create index if not exists logs_timestamp_idx on $TABLE (timestamp)")
            }

            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        ring = RingLog(File(context.applicationContext.filesDir, LOGS_DIR))
        // One thread writes both stores, in the order the entries came in.
        val thread = HandlerThread("app-log").apply { start() }
        writer = Handler(thread.looper)
        runCatching { clear(before = System.currentTimeMillis() - MAX_AGE_MS) }
    }

    /**
     * Puts the log back to a never-initialized state. For the instrumentation tests, whose
     * data wipe (BaseUiTest) removes the database file while this process holds it open: a
     * handle on a deleted file turns every later write into a silent SQLITE_READONLY_DBMOVED,
     * so the tests' assertions on the log would read a store nothing landed in. Called from
     * [io.github.bl3xand.apkcloner.testing.TestHooks.resetAppState] after the wipe, together
     * with the rest of the fresh-state reset; the next [init] rebuilds everything.
     */
    @Synchronized
    fun resetForTesting() {
        val handler = writer
        writer = null
        (handler?.looper?.thread as? HandlerThread)?.quit()
        runCatching { helper?.close() }
        helper = null
        ring = null
    }

    fun debug(message: String) = log(LogLevel.DEBUG, message)
    fun info(message: String) = log(LogLevel.INFO, message)
    fun warn(message: String) = log(LogLevel.WARNING, message)
    fun error(message: String, error: Throwable? = null) =
        log(LogLevel.ERROR, if (error != null) "$message: ${error.message ?: error}" else message)

    /**
     * An error with its whole stack: the message line at ERROR, the stack itself at DEBUG, so
     * the sheet's error filter stays readable while the saved log holds everything there is
     * to know about where it came from.
     */
    fun errorWithStack(message: String, error: Throwable) {
        error(message, error)
        debug("$message: stack:\n${error.stackTraceToString().take(MAX_STACK_CHARS)}")
    }

    /**
     * The lines a saved log starts with: everything about the device and build there is to
     * know before a single entry, so a log a user sends tells what it came from.
     */
    fun header(): List<String> = listOf(
        "APK Toolbox ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
        "Device: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT})",
        "Locale: ${Locale.getDefault().toLanguageTag()}, ring log format 1",
    )

    private fun log(level: LogLevel, message: String) {
        val safe = redact(message)
        Log.println(
            when (level) {
                LogLevel.DEBUG -> Log.DEBUG
                LogLevel.INFO -> Log.INFO
                LogLevel.WARNING -> Log.WARN
                LogLevel.ERROR -> Log.ERROR
            },
            TAG, safe,
        )
        val handler = writer ?: return
        val posted = handler.post { write(level, safe) }
        // The writer thread is gone only when the process is on its way out; the entry still
        // went to logcat, and logging must never break what is being logged.
        if (!posted) write(level, safe)
    }

    /**
     * A log a user attaches to a public issue must not carry their secrets: URLs name their
     * query with a token, key or password often enough. Every such parameter's value is
     * replaced before the entry is stored or printed, wherever in the line it appears.
     */
    private fun redact(message: String): String {
        if (!message.contains("://")) return message
        return REDACT_QUERY.replace(message) { m ->
            val key = m.groupValues[1].lowercase()
            if (key in SECRET_QUERY) "${m.groupValues[0].substringBefore('=')}=<redacted>" else m.value
        }
    }

    /** Runs on the writer thread (or the caller's, as a last resort) and never throws. */
    private fun write(level: LogLevel, message: String) {
        val now = System.currentTimeMillis()
        runCatching {
            helper?.writableDatabase?.insert(
                TABLE, null,
                ContentValues().apply {
                    put("level", level.ordinal)
                    put("message", message)
                    put("timestamp", now)
                },
            )
        }
        runCatching { ring?.append(level, now, message) }
    }

    /**
     * Waits until every entry handed over so far is written to file and SQLite. For the crash
     * handler, which must land its entry before the process goes down; anywhere else the
     * queue drains on its own.
     */
    fun flush() {
        val handler = writer ?: return
        val latch = java.util.concurrent.CountDownLatch(1)
        if (!handler.post { latch.countDown() }) return
        runCatching { latch.await(FLUSH_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS) }
    }

    fun query(after: Long? = null): List<LogEntry> {
        val db = helper?.readableDatabase ?: return emptyList()
        val entries = ArrayList<LogEntry>()
        db.query(
            TABLE, arrayOf("level", "message", "timestamp"),
            if (after != null) "timestamp > ?" else null,
            if (after != null) arrayOf(after.toString()) else null,
            null, null, "timestamp",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                entries.add(
                    LogEntry(
                        LogLevel.entries.getOrElse(cursor.getInt(0)) { LogLevel.INFO }, cursor.getString(1), cursor.getLong(2),
                    ),
                )
            }
        }
        return entries
    }

    fun clear(before: Long? = null): Int = helper?.writableDatabase?.delete(
        TABLE, if (before != null) "timestamp < ?" else null, if (before != null) arrayOf(before.toString()) else null,
    ) ?: 0

    /**
     * Writes the ring's ENTIRE log, oldest first, into [out] as lines, on the writer thread: the
     * only thread that appends to the ring, so the copy is consistent without a lock. Whatever
     * the log sheet's period and level chips happen to select, this is everything there is.
     * The device header goes first: a log a user sends tells what it came from.
     */
    fun writeRingLogTo(out: java.io.OutputStream) {
        val head = header()
        val handler = writer
        val done = java.util.concurrent.CountDownLatch(1)
        var failure: Throwable? = null
        // Posted to the writer thread when it exists; run inline as a last resort, like write().
        val copy = {
            runCatching { ring?.writeLogTo(out, head) }.onFailure { failure = it }
            done.countDown()
        }
        if (handler == null || !handler.post(copy)) copy()
        runCatching { done.await() }
        failure?.let { throw it }
    }

    /** A crash must not be held up for long by a full queue; logcat already has the entry. */
    private const val FLUSH_TIMEOUT_MS = 2_000L

    /** Stacks are cut here: the longest there is - a binder chain - is far shorter. */
    private const val MAX_STACK_CHARS = 24_000

    /** A URL query parameter with any of these names has its value replaced outright. */
    private val SECRET_QUERY = setOf("token", "key", "api_key", "apikey", "password", "pass", "secret", "auth", "signature", "sig")

    /** Any `name=value` pair in a query, however the URL is embedded in the line. */
    private val REDACT_QUERY = Regex("([A-Za-z0-9_-]+)=([^&\\s]+)")

    /** The redaction step alone, for the tests. */
    internal fun redactForTest(message: String): String = redact(message)
}