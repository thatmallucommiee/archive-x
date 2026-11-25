package com.archivex.app

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import org.apache.commons.compress.archivers.ArchiveEntry
import org.apache.commons.compress.archivers.ArchiveInputStream
import org.apache.commons.compress.archivers.ArchiveStreamFactory
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import java.io.BufferedInputStream
import java.io.InputStream
import java.util.Locale

/**
 * Streaming archive engine. It deliberately fails closed for formats that cannot be
 * identified or decoded instead of creating empty-looking output.
 */
object ArchiveEngine {
    private const val MAX_ENTRIES = 100_000
    private const val MAX_ENTRY_BYTES = 2L * 1024 * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 16L * 1024 * 1024 * 1024
    private const val BUFFER_SIZE = 64 * 1024

    fun inspect(resolver: ContentResolver, uri: Uri): String {
        val name = queryName(resolver, uri)
        if (PS4PkgEngine.isPkg(resolver, uri)) {
            return PS4PkgEngine.inspect(resolver, uri)
        }
        return try {
            val lines = mutableListOf("Selected: $name", "", "Reading archive directory…")
            openArchive(resolver, uri).use { archive ->
                var count = 0
                var declared = 0L
                while (true) {
                    val entry = archive.nextEntry ?: break
                    count++
                    require(count <= MAX_ENTRIES) { "Archive contains more than $MAX_ENTRIES entries." }
                    if (entry.size > 0) declared = safeAdd(declared, entry.size, "Declared size overflow.")
                    if (lines.size < 252) {
                        lines += "${if (entry.isDirectory) "[DIR] " else "[FILE] "}${entry.name}  ${if (entry.size >= 0) human(entry.size) else "size unknown"}"
                    }
                }
                val shown = minOf(count, 250)
                lines[1] = "Format: ${detectFormat(resolver, uri)} · Entries: $count · Declared: ${human(declared)}"
                if (count > shown) lines += "… first 250 entries shown"
            }
            lines.joinToString("\n")
        } catch (e: Exception) {
            "Selected: $name\n\nInspection failed.\n${diagnostic(e)}\n\nSupported in this build: ZIP/ZIP64 and TAR. Other formats are reported as unsupported rather than guessed."
        }
    }

    fun extract(resolver: ContentResolver, uri: Uri, tree: Uri): String {
        val name = queryName(resolver, uri)
        if (PS4PkgEngine.isPkg(resolver, uri)) {
            return PS4PkgEngine.extract(resolver, uri, tree)
        }
        var entries = 0
        var files = 0
        var written = 0L
        val created = mutableListOf<Uri>()
        return try {
            val rootId = DocumentsContract.getTreeDocumentId(tree)
            val root = DocumentsContract.buildDocumentUriUsingTree(tree, rootId)
            openArchive(resolver, uri).use { archive ->
                while (true) {
                    val entry = archive.nextEntry ?: break
                    entries++
                    require(entries <= MAX_ENTRIES) { "Entry limit exceeded ($MAX_ENTRIES)." }
                    val path = safePath(entry.name)
                        ?: error("Unsafe archive path rejected: ${entry.name}")
                    val parts = path.split('/').filter { it.isNotBlank() }
                    if (parts.isEmpty()) continue

                    var parent = root
                    for (part in parts.dropLast(1)) {
                        parent = findOrCreateDirectory(resolver, parent, part)
                    }
                    val leaf = parts.last()
                    if (entry.isDirectory) {
                        findOrCreateDirectory(resolver, parent, leaf)
                        continue
                    }

                    val outputName = uniqueName(resolver, parent, leaf)
                    val mime = mimeFor(outputName)
                    val document = DocumentsContract.createDocument(resolver, parent, mime, outputName)
                        ?: error("Android storage provider refused to create '$outputName'. Check destination permissions and free space.")
                    created += document

                    resolver.openOutputStream(document, "w")?.use { out ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var entryBytes = 0L
                        while (true) {
                            val n = archive.read(buffer)
                            if (n < 0) break
                            if (n == 0) continue
                            entryBytes = safeAdd(entryBytes, n.toLong(), "File size overflow.")
                            written = safeAdd(written, n.toLong(), "Total extracted size overflow.")
                            require(entryBytes <= MAX_ENTRY_BYTES) { "Entry exceeds 2 GiB limit: $outputName" }
                            require(written <= MAX_TOTAL_BYTES) { "Extraction exceeds 16 GiB safety limit." }
                            out.write(buffer, 0, n)
                        }
                        out.flush()
                        files++
                    } ?: error("Android storage provider could not open '$outputName' for writing.")
                }
            }
            "Extraction complete.\n\nArchive: $name\nEntries processed: $entries\nFiles written: $files\nData written: ${human(written)}\n\nOriginal archive preserved.\nDestination: selected folder"
        } catch (e: Exception) {
            // Remove incomplete outputs from this run. Previously existing user files are untouched.
            created.asReversed().forEach { document ->
                runCatching { DocumentsContract.deleteDocument(resolver, document) }
            }
            "Extraction failed.\n\nArchive: $name\nEntries reached: $entries\nFiles completed: $files\nData streamed: ${human(written)}\n\nCause: ${diagnostic(e)}\n\nAny incomplete files created by this attempt were removed where the storage provider allowed it. The original archive was not modified."
        }
    }

    private fun openArchive(resolver: ContentResolver, uri: Uri): ArchiveInputStream<out ArchiveEntry> {
        val source = resolver.openInputStream(uri) ?: error("Android could not open the selected archive. Re-select it and check its storage permission.")
        val buffered = BufferedInputStream(source)
        buffered.mark(1024)
        val header = ByteArray(512)
        var read = 0
        while (read < header.size) {
            val n = buffered.read(header, read, header.size - read)
            if (n < 0) break
            read += n
        }
        buffered.reset()
        if (read >= 4 && header[0] == 0x50.toByte() && header[1] == 0x4b.toByte() &&
            ((header[2] == 0x03.toByte() && header[3] == 0x04.toByte()) ||
             (header[2] == 0x05.toByte() && header[3] == 0x06.toByte()) ||
             (header[2] == 0x07.toByte() && header[3] == 0x08.toByte()))) {
            return ZipArchiveInputStream(buffered)
        }
        if (read >= 262 && String(header, 257, 5, Charsets.US_ASCII) == "ustar") {
            return TarArchiveInputStream(buffered)
        }
        return try {
            ArchiveStreamFactory().createArchiveInputStream(buffered)
        } catch (e: Exception) {
            buffered.close()
            throw IllegalArgumentException("Unsupported or damaged archive. This build currently extracts ZIP/ZIP64 and TAR. Detected header: ${header.take(read.coerceAtMost(16)).joinToString("") { "%02X".format(it) }}", e)
        }
    }

    private fun detectFormat(resolver: ContentResolver, uri: Uri): String {
        val input = resolver.openInputStream(uri) ?: return "Unknown"
        input.use {
            val h = ByteArray(512)
            var n = 0
            while (n < h.size) { val r = it.read(h, n, h.size - n); if (r < 0) break; n += r }
            if (n >= 4 && h[0] == 0x50.toByte() && h[1] == 0x4b.toByte()) return "ZIP/ZIP64"
            if (n >= 262 && String(h, 257, 5, Charsets.US_ASCII) == "ustar") return "TAR"
        }
        return "Other / unsupported"
    }

    private fun isPs4Pkg(resolver: ContentResolver, uri: Uri): Boolean = try {
        resolver.openInputStream(uri)?.use { input ->
            val magic = ByteArray(4)
            var offset = 0
            while (offset < 4) { val n = input.read(magic, offset, 4 - offset); if (n < 0) return@use false; offset += n }
            magic.contentEquals(byteArrayOf(0x7f, 0x43, 0x4e, 0x54))
        } ?: false
    } catch (_: Exception) { false }

    private fun safePath(raw: String): String? {
        if (raw.isBlank() || raw.indexOf('\u0000') >= 0) return null
        val normalized = raw.replace('\\', '/')
        if (normalized.startsWith('/') || normalized.startsWith("//") ||
            Regex("^[A-Za-z]:").containsMatchIn(normalized) || normalized.startsWith("~")) return null
        val parts = normalized.split('/')
        if (parts.any { it == ".." || it == "." }) return null
        return parts.filter { it.isNotBlank() }.joinToString("/")
    }

    private fun findOrCreateDirectory(resolver: ContentResolver, parent: Uri, name: String): Uri {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(parent, DocumentsContract.getDocumentId(parent))
        resolver.query(children, arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        ), null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getString(1) == name &&
                    cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                    return DocumentsContract.buildDocumentUriUsingTree(parent, cursor.getString(0))
                }
                if (cursor.getString(1) == name) error("A file/directory collision exists at '$name'.")
            }
        }
        return DocumentsContract.createDocument(resolver, parent,
            DocumentsContract.Document.MIME_TYPE_DIR, name)
            ?: error("Cannot create directory '$name'. Check destination permissions and free space.")
    }

    private fun uniqueName(resolver: ContentResolver, parent: Uri, requested: String): String {
        val names = mutableSetOf<String>()
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(parent, DocumentsContract.getDocumentId(parent))
        resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { c -> while (c.moveToNext()) names += c.getString(0) }
        if (requested !in names) return requested
        val dot = requested.lastIndexOf('.')
        val stem = if (dot > 0) requested.substring(0, dot) else requested
        val ext = if (dot > 0) requested.substring(dot) else ""
        var i = 1
        while ("$stem ($i)$ext" in names) i++
        return "$stem ($i)$ext"
    }

    private fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "apk" -> "application/vnd.android.package-archive"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "mp4" -> "video/mp4"
        "mp3" -> "audio/mpeg"
        "pdf" -> "application/pdf"
        "txt" -> "text/plain"
        "json" -> "application/json"
        else -> "application/octet-stream"
    }

    private fun queryName(resolver: ContentResolver, uri: Uri): String = try {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            val index = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (c.moveToFirst() && index >= 0) c.getString(index) ?: "Unknown file" else "Unknown file"
        } ?: "Unknown file"
    } catch (_: Exception) { "Unknown file" }

    private fun safeAdd(a: Long, b: Long, message: String): Long {
        require(b >= 0 && a <= Long.MAX_VALUE - b) { message }
        return a + b
    }

    private fun diagnostic(e: Throwable): String {
        val detail = e.message?.takeIf { it.isNotBlank() } ?: "No additional message supplied."
        return "${e.javaClass.simpleName}: $detail"
    }

    private fun human(bytes: Long): String = when {
        bytes >= (1L shl 30) -> "%.2f GB".format(bytes / (1L shl 30).toDouble())
        bytes >= (1L shl 20) -> "%.1f MB".format(bytes / (1L shl 20).toDouble())
        bytes >= (1L shl 10) -> "%.1f KB".format(bytes / (1L shl 10).toDouble())
        else -> "$bytes B"
    }
}
