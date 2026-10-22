package dev.proprio.protocol

import java.io.File

/** One sensor after sorting and de-duplication. [xyz] is interleaved x,y,z (3 floats per sample). */
class SensorTrack(val tNs: LongArray, val xyz: FloatArray) {
    val size: Int get() = tNs.size

    /** Index range [from, to) of samples with `start <= t < end`. */
    fun indexRange(start: Long, end: Long): IntRange {
        val from = lowerBound(start)
        val to = lowerBound(end)
        return from until to
    }

    private fun lowerBound(t: Long): Int {
        var lo = 0
        var hi = tNs.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (tNs[mid] < t) lo = mid + 1 else hi = mid
        }
        return lo
    }
}

/** What the reader had to repair. */
data class ReadReport(
    val accRows: Int,
    val gyrRows: Int,
    val accDuplicates: Int,
    val gyrDuplicates: Int,
    val accOutOfOrder: Int,
    val gyrOutOfOrder: Int,
    /** Incomplete trailing rows dropped (file did not end in a newline). */
    val droppedTruncatedRows: Int,
)

data class RecordedSession(
    val meta: Meta,
    val acc: SensorTrack,
    val gyr: SensorTrack,
    val segments: List<SegmentMark>,
    val labels: Labels?,
    val report: ReadReport,
) {
    /** Segments with label overrides applied and implicit rest added (spec section 3.2). */
    fun resolvedSegments(): List<ResolvedSegment> = SegmentResolver.resolve(meta.kind, segments, labels)
}

class UnsupportedFormatException(message: String) : RuntimeException(message)

/** Reads Raw Format v1: sorts, de-duplicates and recovers truncated CSV files. */
object SessionReader {
    fun readZip(zip: File): RecordedSession = fromEntries(SessionZip.read(zip))

    fun readDir(dir: File): RecordedSession {
        val entries = LinkedHashMap<String, ByteArray>()
        for (name in SessionZip.ENTRY_ORDER) {
            val f = File(dir, name)
            if (f.isFile) entries[name] = f.readBytes()
        }
        return fromEntries(entries)
    }

    fun fromEntries(entries: Map<String, ByteArray>): RecordedSession {
        val metaBytes = entries[FILE_META] ?: throw IllegalArgumentException("$FILE_META missing")
        val meta = ProtocolJson.decodeFromString(Meta.serializer(), metaBytes.decodeToString())
        if (meta.rawFormat != RAW_FORMAT_VERSION) {
            throw UnsupportedFormatException("raw_format ${meta.rawFormat} is not supported (expected $RAW_FORMAT_VERSION)")
        }
        val labels = entries[FILE_LABELS]?.let {
            val l = ProtocolJson.decodeFromString(Labels.serializer(), it.decodeToString())
            if (l.labelsFormat != LABELS_FORMAT_VERSION) {
                throw UnsupportedFormatException("labels_format ${l.labelsFormat} is not supported")
            }
            require(l.sessionId == meta.sessionId) { "labels.json session_id does not match meta.json" }
            l
        }
        val acc = parseSensor(entries[FILE_ACC], FILE_ACC)
        val gyr = parseSensor(entries[FILE_GYR], FILE_GYR)
        val segs = parseSegments(entries[FILE_SEGMENTS])
        val dropped = acc.dropped + gyr.dropped + segs.second
        return RecordedSession(
            meta = meta,
            acc = acc.track,
            gyr = gyr.track,
            segments = segs.first,
            labels = labels,
            report = ReadReport(
                accRows = acc.rows,
                gyrRows = gyr.rows,
                accDuplicates = acc.duplicates,
                gyrDuplicates = gyr.duplicates,
                accOutOfOrder = acc.outOfOrder,
                gyrOutOfOrder = gyr.outOfOrder,
                droppedTruncatedRows = dropped,
            ),
        )
    }

    private class Parsed(
        val track: SensorTrack,
        val rows: Int,
        val duplicates: Int,
        val outOfOrder: Int,
        val dropped: Int,
    )

    /** Splits into complete lines. A final line without a newline is a torn write and is dropped. */
    private fun completeLines(bytes: ByteArray?, header: String, name: String): Pair<List<String>, Int> {
        if (bytes == null || bytes.isEmpty()) return emptyList<String>() to 0
        val text = bytes.decodeToString()
        val endsClean = text.endsWith("\n")
        val lines = text.split('\n').let { if (endsClean) it.dropLast(1) else it }
        var dropped = 0
        val kept = if (!endsClean && lines.isNotEmpty()) {
            dropped = 1
            lines.dropLast(1)
        } else {
            lines
        }
        if (kept.isEmpty()) return emptyList<String>() to dropped
        require(kept[0].trimEnd('\r') == header) { "$name: unexpected header '${kept[0]}'" }
        return kept.drop(1) to dropped
    }

    private fun parseSensor(bytes: ByteArray?, name: String): Parsed {
        val (lines, dropped) = completeLines(bytes, SessionWriter.CSV_HEADER, name)
        val n = lines.size
        val t = LongArray(n)
        val v = FloatArray(n * 3)
        for ((i, line) in lines.withIndex()) {
            val p = line.trimEnd('\r').split(',')
            require(p.size == 4) { "$name row ${i + 2}: expected 4 fields, got ${p.size}" }
            t[i] = p[0].toLong()
            v[3 * i] = p[1].toFloat()
            v[3 * i + 1] = p[2].toFloat()
            v[3 * i + 2] = p[3].toFloat()
        }
        var outOfOrder = 0
        for (i in 1 until n) if (t[i] < t[i - 1]) outOfOrder++
        // Stable sort by timestamp keeps arrival order among equal timestamps; keep the first.
        val order = (0 until n).sortedBy { t[it] }
        val tt = LongArray(n)
        val vv = FloatArray(n * 3)
        var m = 0
        for (idx in order) {
            if (m > 0 && tt[m - 1] == t[idx]) continue
            tt[m] = t[idx]
            vv[3 * m] = v[3 * idx]
            vv[3 * m + 1] = v[3 * idx + 1]
            vv[3 * m + 2] = v[3 * idx + 2]
            m++
        }
        return Parsed(
            track = SensorTrack(tt.copyOf(m), vv.copyOf(3 * m)),
            rows = n,
            duplicates = n - m,
            outOfOrder = outOfOrder,
            dropped = dropped,
        )
    }

    private fun parseSegments(bytes: ByteArray?): Pair<List<SegmentMark>, Int> {
        val (lines, dropped) = completeLines(bytes, SessionWriter.SEGMENTS_HEADER, FILE_SEGMENTS)
        val marks = lines.mapIndexed { i, line ->
            val p = line.trimEnd('\r').split(',')
            require(p.size == 5) { "$FILE_SEGMENTS row ${i + 2}: expected 5 fields, got ${p.size}" }
            SegmentMark(
                segmentId = p[0].toInt(),
                tStartNs = p[1].toLong(),
                tEndNs = p[2].toLong(),
                source = when (p[3]) {
                    "watch" -> SegmentSource.WATCH
                    "phone" -> SegmentSource.PHONE
                    "auto" -> SegmentSource.AUTO
                    else -> throw IllegalArgumentException("$FILE_SEGMENTS row ${i + 2}: bad source '${p[3]}'")
                },
                step = p[4].ifEmpty { null },
            )
        }
        return marks to dropped
    }
}
