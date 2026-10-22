package dev.proprio.protocol

import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Streaming writer for a Raw Format v1 session directory (watch side).
 *
 * `meta.json` is written immediately with `complete=false`, so a crashed session still has a
 * readable header; [finish] rewrites it with `complete=true`, the end anchor and the row counts.
 * CSV rows are buffered and flushed to disk at least every [flushIntervalMs] by a time check made
 * on every write (no timer thread), and segment marks are flushed immediately.
 *
 * Thread-safe: all public methods are synchronized, so the sensor-queue drain coroutine and a UI
 * thread adding segment marks may share one instance.
 *
 * @param monotonicMs injectable millisecond clock (tests); defaults to `System.nanoTime()`.
 */
class SessionWriter(
    private val dir: File,
    initialMeta: Meta,
    private val flushIntervalMs: Long = DEFAULT_FLUSH_INTERVAL_MS,
    private val monotonicMs: () -> Long = { System.nanoTime() / 1_000_000L },
) : Closeable {
    private var meta: Meta = initialMeta.copy(complete = false, clockAnchorEnd = null, sampleCounts = null, endedAtUtc = null)
    private val accOut = open(FILE_ACC, CSV_HEADER)
    private val gyrOut = open(FILE_GYR, CSV_HEADER)
    private val segOut = open(FILE_SEGMENTS, SEGMENTS_HEADER)
    private var accRows = 0L
    private var gyrRows = 0L
    private var lastFlushMs = monotonicMs()
    private var closed = false

    init {
        writeMeta(meta)
        accOut.flush()
        gyrOut.flush()
        segOut.flush()
    }

    private fun open(name: String, header: String): BufferedWriter {
        dir.mkdirs()
        val w = BufferedWriter(
            OutputStreamWriter(FileOutputStream(File(dir, name)), StandardCharsets.UTF_8),
            BUFFER_CHARS,
        )
        w.write(header)
        w.write("\n")
        return w
    }

    @Synchronized
    fun writeAcc(tNs: Long, x: Float, y: Float, z: Float) {
        writeRow(accOut, tNs, x, y, z)
        accRows++
        flushIfDue()
    }

    @Synchronized
    fun writeGyr(tNs: Long, x: Float, y: Float, z: Float) {
        writeRow(gyrOut, tNs, x, y, z)
        gyrRows++
        flushIfDue()
    }

    /** Appends a completed segment mark and flushes it to disk. */
    @Synchronized
    fun addSegment(mark: SegmentMark) {
        check(!closed) { "writer is closed" }
        segOut.write(
            "${mark.segmentId},${mark.tStartNs},${mark.tEndNs}," +
                "${mark.source.csvName()},${mark.step.orEmpty()}\n",
        )
        segOut.flush()
    }

    /** Flushes buffers if [flushIntervalMs] has elapsed since the last flush. */
    @Synchronized
    fun flushIfDue() {
        val now = monotonicMs()
        if (now - lastFlushMs >= flushIntervalMs) flush()
    }

    @Synchronized
    fun flush() {
        if (closed) return
        accOut.flush()
        gyrOut.flush()
        segOut.flush()
        lastFlushMs = monotonicMs()
    }

    /** Closes the CSV files and writes the final `meta.json` (`complete=true`). Returns it. */
    @Synchronized
    fun finish(clockAnchorEnd: ClockAnchor, endedAtUtc: String): Meta {
        check(!closed) { "writer is closed" }
        closeStreams()
        meta = meta.copy(
            complete = true,
            clockAnchorEnd = clockAnchorEnd,
            sampleCounts = SampleCounts(accRows, gyrRows),
            endedAtUtc = endedAtUtc,
        )
        writeMeta(meta)
        return meta
    }

    /** Aborts without finalizing; `meta.json` stays `complete=false`. Safe after [finish]. */
    @Synchronized
    override fun close() {
        if (!closed) closeStreams()
    }

    private fun closeStreams() {
        closed = true
        accOut.close()
        gyrOut.close()
        segOut.close()
    }

    private fun writeMeta(m: Meta) {
        val tmp = File(dir, "$FILE_META.tmp")
        tmp.writeText(m.toJson(), StandardCharsets.UTF_8)
        Files.move(tmp.toPath(), File(dir, FILE_META).toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    private fun writeRow(w: BufferedWriter, tNs: Long, x: Float, y: Float, z: Float) {
        check(!closed) { "writer is closed" }
        require(x.isFinite() && y.isFinite() && z.isFinite()) { "non-finite sample at t=$tNs" }
        w.write(tNs.toString())
        w.write(",")
        w.write(x.toString())
        w.write(",")
        w.write(y.toString())
        w.write(",")
        w.write(z.toString())
        w.write("\n")
    }

    companion object {
        const val DEFAULT_FLUSH_INTERVAL_MS = 2_000L
        const val CSV_HEADER = "t_ns,x,y,z"
        const val SEGMENTS_HEADER = "segment_id,t_start_ns,t_end_ns,source,step"
        private const val BUFFER_CHARS = 64 * 1024
    }
}

internal fun SegmentSource.csvName(): String = when (this) {
    SegmentSource.WATCH -> "watch"
    SegmentSource.PHONE -> "phone"
    SegmentSource.AUTO -> "auto"
}
