package io.github.bl3xand.apkcloner.log

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

/**
 * The app's log: what was checked, downloaded, cloned, merged and installed, and what failed.
 * Every tool writes here, so one place tells what happened and when.
 */
object AppLog {
    private const val TAG = "ApkToolbox"
    private const val TABLE = "logs"
    /** How long an entry is kept. */
    const val KEPT_DAYS = 30
    private const val MAX_AGE_MS = KEPT_DAYS * 24L * 60 * 60 * 1000

    @Volatile
    private var helper: SQLiteOpenHelper? = null

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
        runCatching { clear(before = System.currentTimeMillis() - MAX_AGE_MS) }
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
        // Logging must never break what is being logged.
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
}
