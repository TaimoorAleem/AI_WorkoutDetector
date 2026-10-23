package dev.proprio.protocol

import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDateTime
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Packs and unpacks Raw Format v1 session zips (`proprio-session-<session_id>.zip`).
 * Output is deterministic: fixed entry order and a fixed entry timestamp.
 */
object SessionZip {
    /** Canonical entry order. */
    val ENTRY_ORDER = listOf(FILE_META, FILE_ACC, FILE_GYR, FILE_SEGMENTS, FILE_LABELS)

    private val FIXED_TIME: LocalDateTime = LocalDateTime.of(1980, 1, 1, 0, 0, 0)

    fun fileName(sessionId: String) = "proprio-session-$sessionId.zip"

    /** Zips the known session files found in [dir] into [zip]. */
    fun pack(dir: File, zip: File) {
        val entries = LinkedHashMap<String, ByteArray>()
        for (name in ENTRY_ORDER) {
            val f = File(dir, name)
            if (f.isFile) entries[name] = f.readBytes()
        }
        write(entries, zip)
    }

    /** Writes [entries] (name to bytes) as a zip, known names first in canonical order. */
    fun write(entries: Map<String, ByteArray>, zip: File) {
        zip.absoluteFile.parentFile?.mkdirs()
        val ordered = ENTRY_ORDER.filter { it in entries } + entries.keys.filter { it !in ENTRY_ORDER }.sorted()
        ZipOutputStream(zip.outputStream().buffered()).use { out ->
            for (name in ordered) {
                requireSafeName(name)
                val e = ZipEntry(name)
                e.setTimeLocal(FIXED_TIME)
                out.putNextEntry(e)
                out.write(entries.getValue(name))
                out.closeEntry()
            }
        }
    }

    /** Reads every entry of [zip] into memory. */
    fun read(zip: File): LinkedHashMap<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        ZipFile(zip).use { zf ->
            for (e in zf.entries()) {
                if (e.isDirectory) continue
                requireSafeName(e.name)
                val buf = ByteArrayOutputStream()
                zf.getInputStream(e).use { it.copyTo(buf) }
                out[e.name] = buf.toByteArray()
            }
        }
        return out
    }

    /** Extracts [zip] into [dir] (flat; entry names with path separators are rejected). */
    fun unpack(zip: File, dir: File) {
        dir.mkdirs()
        for ((name, bytes) in read(zip)) File(dir, name).writeBytes(bytes)
    }

    /** Returns a copy of [src] with `labels.json` added or replaced; all other entries are byte-identical. */
    fun withLabels(src: File, labels: Labels, dst: File) {
        val entries = read(src)
        entries[FILE_LABELS] = labels.toJson().toByteArray(Charsets.UTF_8)
        write(entries, dst)
    }

    private fun requireSafeName(name: String) {
        require(name.isNotEmpty() && '/' !in name && '\\' !in name && name != ".." && name != ".") {
            "unsafe zip entry name: $name"
        }
    }
}
