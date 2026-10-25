package io.github.bl3xand.apkcloner.log

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import java.io.File

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

    private fun log(level: LogLevel, message: String) {
        Log.println(
            when (level) {
                LogLevel.DEBUG -> Log.DEBUG
                LogLevel.INFO -> Log.INFO
                LogLevel.WARNING -> Log.WARN
                LogLevel.ERROR -> Log.ERROR
            },
            TAG, message,
        )
        val handler = writer ?: return
        val posted = handler.post { write(level, message) }
        // The writer thread is gone only when the process is on its way out; the entry still
        // went to logcat, and logging must never break what is being logged.
        if (!posted) write(level, message)
    }

    /** Runs on the writer thread (or the caller's, as a last resort) and never throws. */
    private fun write(level: LogLevel, message: String) {
        runCatching {
            helper?.writableDatabase?.insert(
                TABLE, null,
                ContentValues().apply {
                    put("level", level.ordinal)
                    put("message", message)
                    put("timestamp", System.currentTimeMillis())
                },
            )
        }
        runCatching { ring?.append(level, System.currentTimeMillis(), message) }
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

    /** A crash must not be held up for long by a full queue; logcat already has the entry. */
    private const val FLUSH_TIMEOUT_MS = 2_000L
}