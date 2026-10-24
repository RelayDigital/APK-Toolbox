package io.github.bl3xand.apkcloner.log

import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * The app log as a ring of files: the whole history within a fixed budget of bytes, kept in
 * the app's own storage where the user sees nothing of it, so a release build can be debugged
 * after the fact. Only the writing thread of [AppLog] touches a ring, so it keeps no locks
 * of its own.
 *
 * The files are `applog.<i>.bin`, `<i>` counting up and wrapping at the segment count; each
 * holds at most [segmentBytes] and together they stay within the budget. A segment is a
 * 16-byte header followed by records, back to back, every number big-endian:
 * - segment header: magic int 0x41504B31, sequence long (a segment created later has a larger
 *   one, so a reader can put the segments in order), spare int;
 * - record: level byte (the [LogLevel] ordinal), timestamp long (epoch milliseconds), length
 *   int (bytes of the message), then the message as UTF-8. A message over the cap is cut at
 *   a byte boundary, which may split a character.
 *
 * The app can be killed mid-write, so the newest segment may end in a torn record: when the
 * ring is opened again, everything after the last complete record is cut away and the
 * writing resumes from there. A write that fails - the disk full, the folder gone - moves on
 * to the oldest segment and tries once more, and never throws.
 */
class RingLog(
    private val directory: File,
    private val segmentBytes: Long = DEFAULT_SEGMENT_BYTES,
    private val segmentCount: Int = DEFAULT_SEGMENT_COUNT,
) {
    private var opened = false
    private var index = 0
    private var sequence = 0L
    private var bytes = 0L
    private var output: FileOutputStream? = null
    private var failures = 0
    private var broken = false

    /** Adds one entry, in the order [append] is called; never throws. */
    fun append(level: LogLevel, timestamp: Long, message: String) {
        if (broken) return
        if (write(level, timestamp, message)) {
            failures = 0
            return
        }
        // The disk may be full or the folder gone: begin again on the oldest segment and try once more.
        failures = if (write(level, timestamp, message, startOver = true)) 0 else failures + 1
        if (failures >= GIVE_UP_AFTER) broken = true
    }

    /**
     * Everything the ring holds, oldest first, torn tails skipped. It reads the whole budget,
     * so it is for pulling the log off a device or for tests, not for the app itself.
     */
    fun read(): List<LogEntry> {
        // The segments in the order they were created: their sequence numbers say which is older.
        val order = (0 until segmentCount)
            .map { it to sequenceOf(it) }
            .filter { it.second >= 0 }
            .sortedBy { it.second }
        val entries = ArrayList<LogEntry>()
        for ((segment, _) in order) {
            runCatching {
                DataInputStream(FileInputStream(file(segment))).use { input ->
                    input.readFully(ByteArray(SEGMENT_HEADER_BYTES))
                    while (true) {
                        // A torn tail ends the stream in the middle of this or the body: the read stops there.
                        val header = ByteArray(RECORD_HEADER_BYTES)
                        input.readFully(header)
                        val level = header[0].toInt() and 0xFF
                        val timestamp = ByteBuffer.wrap(header, 1, 8).long
                        val length = ByteBuffer.wrap(header, 9, 4).int
                        if (level > 3 || length < 0 || length > MAX_MESSAGE_BYTES) return@use
                        val body = ByteArray(length)
                        input.readFully(body)
                        entries.add(
                            LogEntry(LogLevel.entries.getOrElse(level) { LogLevel.INFO }, String(body, Charsets.UTF_8), timestamp),
                        )
                    }
                }
            }
        }
        return entries
    }

    private fun write(level: LogLevel, timestamp: Long, message: String, startOver: Boolean = false): Boolean {
        if (!opened) open()
        if (startOver) rotate()
        val whole = message.toByteArray(Charsets.UTF_8)
        val cap = minOf(MAX_MESSAGE_BYTES, (segmentBytes - SEGMENT_HEADER_BYTES - RECORD_HEADER_BYTES).toInt().coerceAtLeast(1))
        val body = if (whole.size > cap) whole.copyOf(cap) else whole
        return runCatching {
            if (bytes + RECORD_HEADER_BYTES + body.size > segmentBytes) rotate()
            val stream = output ?: error("the segment could not be opened")
            stream.write(recordHeader(level, timestamp, body.size))
            stream.write(body)
            bytes += RECORD_HEADER_BYTES + body.size
        }.isSuccess
    }

    /** Picks up where the last run left off: the newest segment, with any torn record cut away. */
    private fun open() {
        opened = true
        var newest = -1
        var newestSequence = -1L
        for (i in 0 until segmentCount) {
            val found = sequenceOf(i)
            if (found > newestSequence) {
                newestSequence = found
                newest = i
            }
        }
        if (newest < 0) {
            // No readable segment: a fresh ring begins at the first one.
            index = 0
            sequence = 0
            startSegment()
            return
        }
        index = newest
        sequence = newestSequence
        bytes = cutTornRecord(file(index))
        if (bytes < SEGMENT_HEADER_BYTES) {
            // The newest segment cannot be read as records: begin it anew rather than trust its tail.
            sequence++
            startSegment()
            return
        }
        output = runCatching { FileOutputStream(file(index), true) }.getOrNull()
    }

    /** Starts the current segment over: truncated to nothing but its header. */
    private fun startSegment() {
        output = runCatching {
            directory.mkdirs()
            FileOutputStream(file(index)).apply { write(segmentHeader(sequence)) }
        }.getOrNull()
        bytes = if (output == null) 0L else SEGMENT_HEADER_BYTES.toLong()
    }

    /** Moves on to the oldest segment - the next one in the ring - and starts it over. */
    private fun rotate() {
        runCatching { output?.close() }
        index = (index + 1) % segmentCount
        sequence++
        startSegment()
    }

    /**
     * Where the last complete record of the segment ends, with anything after it - the record
     * a kill may have torn - cut away; -1 if the file cannot be read at all.
     */
    private fun cutTornRecord(file: File): Long = runCatching {
        RandomAccessFile(file, "rw").use { segment ->
            val length = segment.length()
            var at = SEGMENT_HEADER_BYTES.toLong()
            while (at + RECORD_HEADER_BYTES <= length) {
                segment.seek(at)
                val level = segment.readByte().toInt()
                segment.readLong()
                val bodyLength = segment.readInt()
                if (level < 0 || level > 3 || bodyLength < 0 || at + RECORD_HEADER_BYTES + bodyLength > length) break
                at += RECORD_HEADER_BYTES + bodyLength
            }
            if (at < length) segment.setLength(at.toLong())
            at
        }
    }.getOrDefault(-1L)

    /** The creation order of a segment, from its header; -1 if it is missing or not ours. */
    private fun sequenceOf(index: Int): Long {
        val segment = file(index)
        if (!segment.isFile || segment.length() < SEGMENT_HEADER_BYTES) return -1L
        return runCatching {
            RandomAccessFile(segment, "r").use {
                if (it.readInt() == MAGIC) it.readLong() else -1L
            }
        }.getOrDefault(-1L)
    }

    private fun file(index: Int) = File(directory, "applog.$index.bin")

    private fun segmentHeader(sequence: Long) =
        ByteBuffer.allocate(SEGMENT_HEADER_BYTES).putInt(MAGIC).putLong(sequence).putInt(0).array()

    private fun recordHeader(level: LogLevel, timestamp: Long, length: Int) =
        ByteBuffer.allocate(RECORD_HEADER_BYTES).put(level.ordinal.toByte()).putLong(timestamp).putInt(length).array()

    private companion object {
        /** 8 segments of 37.5 MB: 300 MB of history in all. */
        const val DEFAULT_SEGMENT_COUNT = 8
        const val DEFAULT_SEGMENT_BYTES = 37_500_000L

        /** "APK1": a file that is not one of ours is ignored. */
        const val MAGIC = 0x41504B31
        const val SEGMENT_HEADER_BYTES = 16

        /** A level byte, a timestamp long and a length int. */
        const val RECORD_HEADER_BYTES = 13

        /** Messages beyond this are cut; the crash trace, the longest there is, is far shorter. */
        const val MAX_MESSAGE_BYTES = 65_535

        /** After this many entries in a row could not be written at all, the ring stops trying. */
        const val GIVE_UP_AFTER = 100
    }
}