package io.github.bl3xand.apkcloner.sources.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The log of the Sources tab: what was checked, downloaded and installed, and what failed. */
object SourcesLog {
    enum class Level { DEBUG, INFO, WARNING, ERROR }

    class Entry(val level: Level, val message: String, val timestamp: Long) {
        override fun toString(): String =
            "${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date(timestamp))}: " +
                "${level.name.lowercase()}: $message"
    }

    private const val TAG = "Sources"
    private const val TABLE = "logs"
    private const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000

    @Volatile
    private var helper: SQLiteOpenHelper? = null

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

    fun debug(message: String) = log(Level.DEBUG, message)
    fun info(message: String) = log(Level.INFO, message)
    fun warn(message: String) = log(Level.WARNING, message)
    fun error(message: String, error: Throwable? = null) =
        log(Level.ERROR, if (error != null) "$message: ${error.message ?: error}" else message)

    private fun log(level: Level, message: String) {
        Log.println(
            when (level) {
                Level.DEBUG -> Log.DEBUG
                Level.INFO -> Log.INFO
                Level.WARNING -> Log.WARN
                Level.ERROR -> Log.ERROR
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

    fun query(after: Long? = null): List<Entry> {
        val db = helper?.readableDatabase ?: return emptyList()
        val entries = ArrayList<Entry>()
        db.query(
            TABLE, arrayOf("level", "message", "timestamp"),
            if (after != null) "timestamp > ?" else null,
            if (after != null) arrayOf(after.toString()) else null,
            null, null, "timestamp",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                entries.add(
                    Entry(
                        Level.entries.getOrElse(cursor.getInt(0)) { Level.INFO }, cursor.getString(1), cursor.getLong(2),
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
