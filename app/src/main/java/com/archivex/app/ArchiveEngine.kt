package com.archivex.app

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import org.apache.commons.compress.archivers.ArchiveStreamFactory
import org.apache.commons.compress.archivers.ArchiveEntry
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream

object ArchiveEngine {
    private const val MAX_ENTRIES = 100_000
    private const val MAX_ENTRY_BYTES = 2L * 1024 * 1024 * 1024

    fun inspect(resolver: ContentResolver, uri: Uri): String {
        val name = queryName(resolver, uri)
        if (isPs4Pkg(resolver, uri)) return "Selected: $name\nFormat: PlayStation 4 PKG\nStatus: Recognised package signature.\n\nARCHIVE X currently reports the format only. PKG entry-table parsing and extraction are not yet implemented. Encrypted package contents will not be decrypted or security controls bypassed."
        return try {
            val lines = mutableListOf("Selected: $name", "Format: archive stream", "")
            resolver.openInputStream(uri).use { raw ->
                requireNotNull(raw) { "Cannot open file" }
                val input = BufferedInputStream(raw)
                val factory = ArchiveStreamFactory()
                val archive = factory.createArchiveInputStream(input)
                var count = 0
                var total = 0L
                while (true) {
                    val entry = archive.nextEntry ?: break
                    count++
                    if (count > MAX_ENTRIES) error("Entry limit exceeded")
                    val size = entry.size.coerceAtLeast(0)
                    total += size
                    lines += "${if (entry.isDirectory) "[DIR] " else "[FILE] "}${entry.name}  ${if (size > 0) human(size) else ""}"
                    if (lines.size > 250) { lines += "… listing limited to first 250 entries"; break }
                }
                lines.add(1, "Entries: $count · Declared size: ${human(total)}")
            }
            lines.joinToString("\n")
        } catch (e: Exception) {
            "Selected: $name\n\nThis format could not be inspected by the current build.\n${e.message ?: e.javaClass.simpleName}\n\nSupported stream formats depend on Apache Commons Compress (ZIP, TAR, GZIP, BZIP2, XZ and related formats). 7z/RAR support is not included in this first build."
        }
    }

    fun extract(resolver: ContentResolver, uri: Uri, tree: Uri): String {
        if (isPs4Pkg(resolver, uri)) return "PKG extraction is not available yet. This build recognises the PS4 PKG signature but does not parse or extract its contents."
        var count = 0
        var written = 0L
        return try {
            val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            resolver.openInputStream(uri).use { raw ->
                requireNotNull(raw) { "Cannot open archive" }
                val archive = ArchiveStreamFactory().createArchiveInputStream(BufferedInputStream(raw))
                while (true) {
                    val entry = archive.nextEntry ?: break
                    count++
                    require(count <= MAX_ENTRIES) { "Entry limit exceeded" }
                    val safeName = safePath(entry.name) ?: continue
                    val parts = safeName.split('/').filter { it.isNotBlank() }
                    var parent = root
                    for (part in parts.dropLast(1)) parent = findOrCreateDirectory(resolver, parent, part)
                    if (entry.isDirectory) {
                        if (parts.isNotEmpty()) findOrCreateDirectory(resolver, parent, parts.last())
                        continue
                    }
                    val fileName = parts.lastOrNull() ?: continue
                    val doc = DocumentsContract.createDocument(resolver, parent, "application/octet-stream", fileName) ?: continue
                    resolver.openOutputStream(doc, "w").use { out ->
                        requireNotNull(out) { "Cannot write $fileName" }
                        val buffer = ByteArray(64 * 1024)
                        var entryBytes = 0L
                        while (true) {
                            val n = archive.read(buffer)
                            if (n < 0) break
                            entryBytes += n
                            written += n
                            require(entryBytes <= MAX_ENTRY_BYTES) { "File too large: $fileName" }
                            out.write(buffer, 0, n)
                        }
                    }
                }
            }
            "Extraction complete.\nFiles processed: $count\nData written: ${human(written)}"
        } catch (e: Exception) {
            "Extraction stopped.\nFiles processed: $count\nData written: ${human(written)}\nReason: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    private fun isPs4Pkg(resolver: ContentResolver, uri: Uri): Boolean = try {
        resolver.openInputStream(uri).use { it?.let { input -> ByteArray(4).also { input.read(it) }.contentEquals(byteArrayOf(0x7f, 0x43, 0x4e, 0x54)) } } ?: false
    } catch (_: Exception) { false }

    private fun queryName(resolver: ContentResolver, uri: Uri): String = try {
        resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) ?: "Unknown file" else "Unknown file" } ?: "Unknown file"
    } catch (_: Exception) { "Unknown file" }

    private fun safePath(path: String): String? {
        val normalized = path.replace('\\', '/').trimStart('/')
        if (normalized.isBlank() || normalized.split('/').any { it == ".." }) return null
        if (normalized.startsWith("~")) return null
        return normalized
    }

    private fun findOrCreateDirectory(resolver: ContentResolver, parent: Uri, name: String): Uri {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(parent, DocumentsContract.getDocumentId(parent))
        resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { c ->
            while (c.moveToNext()) if (c.getString(1) == name && c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) return DocumentsContract.buildDocumentUriUsingTree(parent, c.getString(0))
        }
        return DocumentsContract.createDocument(resolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, name) ?: error("Cannot create directory $name")
    }

    private fun human(bytes: Long): String = when { bytes >= 1L shl 30 -> "%.2f GB".format(bytes / (1L shl 30).toDouble()); bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1L shl 20).toDouble()); bytes >= 1L shl 10 -> "%.1f KB".format(bytes / (1L shl 10).toDouble()); else -> "$bytes B" }
}
