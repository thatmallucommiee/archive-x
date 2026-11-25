package com.archivex.app

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.system.Os
import android.system.OsConstants
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * Conservative first Hatch implementation.
 *
 * It is deliberately transactional at archive level: the original is not modified
 * while extraction is in progress. Every output is written and ZIP CRC-checked first.
 * Only after a second source fingerprint matches does it attempt to truncate the
 * same SAF document. Providers that do not expose a seekable regular read/write fd
 * are rejected before mutation.
 *
 * This is not per-entry progressive reclamation. It is a verified, all-or-nothing
 * reclaim milestone; true per-entry compaction needs a format-aware rewrite planner.
 */
object HatchEngine {
    private const val MAX_ENTRIES = 100_000
    private const val MAX_ENTRY_BYTES = 2L * 1024 * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 16L * 1024 * 1024 * 1024
    private const val BUFFER_SIZE = 64 * 1024

    fun run(context: Context, resolver: ContentResolver, source: Uri, destinationTree: Uri): String {
        if (PS4PkgEngine.isPkg(resolver, source)) {
            return PS4PkgEngine.runHatch(resolver, source, destinationTree)
        }
        val sourceName = queryName(resolver, source)
        val created = mutableListOf<Uri>()
        var entries = 0
        var files = 0
        var written = 0L
        var sourceReclaimed = false

        return try {
            val before = fingerprint(resolver, source)
            require(isZip(resolver, source)) {
                "Hatch currently accepts ZIP/ZIP64 only. Use Standard Mode for TAR or other formats."
            }

            // Capability preflight is read-only. Do not start extraction if this provider
            // cannot open the exact selected document as a seekable regular read/write file.
            verifyTruncationCapability(resolver, source)

            val rootId = DocumentsContract.getTreeDocumentId(destinationTree)
            val root = DocumentsContract.buildDocumentUriUsingTree(destinationTree, rootId)
            val seen = HashSet<String>()

            resolver.openInputStream(source)?.use { raw ->
                ZipInputStream(BufferedInputStream(raw)).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        entries++
                        require(entries <= MAX_ENTRIES) { "Entry limit exceeded ($MAX_ENTRIES)." }

                        val path = safePath(entry.name)
                            ?: error("Unsafe ZIP path rejected: ${entry.name}")
                        require(seen.add(path.lowercase(Locale.ROOT))) {
                            "Duplicate ZIP path rejected: $path"
                        }
                        val parts = path.split('/').filter { it.isNotBlank() }
                        require(parts.isNotEmpty()) { "Empty ZIP entry path." }

                        var parent = root
                        for (part in parts.dropLast(1)) {
                            parent = findOrCreateDirectory(resolver, parent, part)
                        }
                        val leaf = parts.last()
                        if (entry.isDirectory) {
                            findOrCreateDirectory(resolver, parent, leaf)
                            zip.closeEntry()
                            continue
                        }

                        val document = DocumentsContract.createDocument(
                            resolver, parent, mimeFor(leaf), leaf
                        ) ?: error("Could not create '$leaf' in the selected destination.")
                        created += document

                        var entryBytes = 0L
                        resolver.openOutputStream(document, "w")?.use { out ->
                            val buffer = ByteArray(BUFFER_SIZE)
                            while (true) {
                                val count = zip.read(buffer)
                                if (count < 0) break
                                if (count == 0) continue
                                entryBytes = safeAdd(entryBytes, count.toLong(), "Entry size overflow.")
                                written = safeAdd(written, count.toLong(), "Total size overflow.")
                                require(entryBytes <= MAX_ENTRY_BYTES) {
                                    "Entry exceeds the 2 GiB safety limit: $leaf"
                                }
                                require(written <= MAX_TOTAL_BYTES) {
                                    "Extraction exceeds the 16 GiB safety limit."
                                }
                                out.write(buffer, 0, count)
                            }
                            out.flush()
                        } ?: error("Storage provider could not write '$leaf'.")

                        // ZipInputStream validates the entry CRC when it closes the entry.
                        zip.closeEntry()
                        if (entry.size >= 0) require(entryBytes == entry.size) {
                            "Size verification failed for '$leaf'."
                        }
                        if (entry.crc >= 0) require(crcOfDocument(resolver, document) == entry.crc) {
                            "CRC verification failed for '$leaf'."
                        }
                        files++
                    }
                }
            } ?: error("Android could not reopen the selected source archive.")

            require(entries > 0) { "The selected ZIP contains no entries." }

            // Refuse to consume the source if it changed while extraction was running.
            val after = fingerprint(resolver, source)
            require(before == after) {
                "The source archive changed during extraction. Original preserved; review destination files."
            }

            // Persist intent before the destructive operation; failure aborts safely.
            writeJournal(context, sourceName, before, "ready_to_reclaim")

            // Re-check the exact descriptor immediately before the destructive operation.
            truncateOriginal(resolver, source)
            sourceReclaimed = true

            // Never let a journal refresh failure delete already-verified outputs.
            runCatching { writeJournal(context, sourceName, before, "reclaimed") }
            "HATCH COMPLETE\n\nArchive: $sourceName\nEntries checked: $entries\nFiles verified: $files\nData extracted: ${human(written)}\nOriginal archive: reclaimed (truncated to 0 bytes)\n\nAll outputs were written and verified before the original was modified."
        } catch (e: Exception) {
            // Before the final truncate, the source remains intact. Remove this run's
            // outputs where the provider permits it so failed attempts do not look complete.
            if (!sourceReclaimed) {
                created.asReversed().forEach { uri ->
                    runCatching { DocumentsContract.deleteDocument(resolver, uri) }
                }
            }
            if (sourceReclaimed) "HATCH RECLAIMED — JOURNAL WARNING\n\nArchive: $sourceName\nEntries checked: $entries\nFiles verified: $files\nData extracted: ${human(written)}\n\nThe source was truncated after verification. Keep the extracted destination intact." else
            "HATCH STOPPED — ORIGINAL PRESERVED\n\nArchive: $sourceName\nEntries reached: $entries\nFiles verified: $files\nData streamed: ${human(written)}\n\nReason: ${e.javaClass.simpleName}: ${e.message ?: "No additional details"}\n\nNo source reclamation was confirmed. Any incomplete outputs created by this attempt were removed where the provider allowed it."
        }
    }

    private fun verifyTruncationCapability(resolver: ContentResolver, uri: Uri) {
        val pfd = resolver.openFileDescriptor(uri, "rw")
            ?: error("This storage provider does not grant read/write access. Hatch is unavailable for this archive.")
        pfd.use {
            val stat = Os.fstat(it.fileDescriptor)
            require((stat.st_mode and OsConstants.S_IFMT) == OsConstants.S_IFREG) {
                "This provider exposes a non-regular document (for example, a cloud stream). Hatch requires a local seekable file."
            }
            runCatching { Os.lseek(it.fileDescriptor, 0L, OsConstants.SEEK_CUR) }
                .getOrElse { error("The source is not seekable; Hatch cannot safely reclaim it.") }
        }
    }

    private fun truncateOriginal(resolver: ContentResolver, uri: Uri) {
        val pfd = resolver.openFileDescriptor(uri, "rw")
            ?: error("Read/write access disappeared before reclamation. Original preserved.")
        var committed = false
        try {
            val fd = pfd.fileDescriptor
            val stat = Os.fstat(fd)
            require((stat.st_mode and OsConstants.S_IFMT) == OsConstants.S_IFREG) {
                "Source is no longer a regular file. Original preserved."
            }
            Os.lseek(fd, 0L, OsConstants.SEEK_SET)
            Os.ftruncate(fd, 0L)
            committed = true
            // ftruncate is the commit point. Sync/close failures must not cause
            // verified outputs to be deleted after the source has been consumed.
            runCatching { Os.fsync(fd) }
        } finally {
            runCatching { pfd.close() }
        }
        check(committed) { "Source reclamation did not commit." }
    }

    private fun fingerprint(resolver: ContentResolver, uri: Uri): String {
        val digest = MessageDigest.getInstance("SHA-256")
        resolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                if (n > 0) digest.update(buffer, 0, n)
            }
        } ?: error("Cannot read source archive for integrity check.")
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun isZip(resolver: ContentResolver, uri: Uri): Boolean {
        resolver.openInputStream(uri)?.use { input ->
            val header = ByteArray(4)
            var offset = 0
            while (offset < header.size) {
                val n = input.read(header, offset, header.size - offset)
                if (n < 0) break
                offset += n
            }
            return offset == 4 && header[0] == 0x50.toByte() && header[1] == 0x4b.toByte() &&
                ((header[2] == 0x03.toByte() && header[3] == 0x04.toByte()) ||
                 (header[2] == 0x05.toByte() && header[3] == 0x06.toByte()) ||
                 (header[2] == 0x07.toByte() && header[3] == 0x08.toByte()))
        }
        return false
    }

    private fun safePath(raw: String): String? {
        if (raw.isBlank() || raw.indexOf('\u0000') >= 0) return null
        val normalized = raw.replace('\\', '/')
        if (normalized.startsWith('/') || Regex("^[A-Za-z]:").containsMatchIn(normalized) ||
            normalized.startsWith("~")) return null
        val parts = normalized.split('/')
        if (parts.any { it == "." || it == ".." }) return null
        return parts.filter { it.isNotBlank() }.joinToString("/")
    }

    private fun findOrCreateDirectory(resolver: ContentResolver, parent: Uri, name: String): Uri {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(
            parent, DocumentsContract.getDocumentId(parent)
        )
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
                if (cursor.getString(1) == name) error("Destination collision at '$name'.")
            }
        }
        return DocumentsContract.createDocument(
            resolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, name
        ) ?: error("Cannot create destination directory '$name'.")
    }

    private fun crcOfDocument(resolver: ContentResolver, uri: Uri): Long {
        val crc = java.util.zip.CRC32()
        resolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                if (n > 0) crc.update(buffer, 0, n)
            }
        } ?: error("Cannot reopen extracted file for CRC verification.")
        return crc.value
    }

    private fun queryName(resolver: ContentResolver, uri: Uri): String =
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            val index = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (c.moveToFirst() && index >= 0) c.getString(index) ?: "Selected archive"
            else "Selected archive"
        } ?: "Selected archive"

    private fun mimeFor(name: String): String = when (
        name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    ) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "pdf" -> "application/pdf"
        "txt" -> "text/plain"
        "json" -> "application/json"
        "apk" -> "application/vnd.android.package-archive"
        else -> "application/octet-stream"
    }

    private fun safeAdd(a: Long, b: Long, message: String): Long {
        require(b >= 0 && a <= Long.MAX_VALUE - b) { message }
        return a + b
    }

    private fun writeJournal(context: Context, name: String, hash: String, state: String) {
        val target = File(context.filesDir, "hatch-last-session.txt")
        val temp = File(context.filesDir, "hatch-last-session.tmp")
        FileOutputStream(temp).use { out ->
            out.write("archive=$name\nsha256=$hash\nstate=$state\n".toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        if (target.exists()) target.delete()
        check(temp.renameTo(target)) { "Could not persist Hatch completion journal." }
    }

    private fun human(bytes: Long): String = when {
        bytes >= (1L shl 30) -> "%.2f GB".format(bytes / (1L shl 30).toDouble())
        bytes >= (1L shl 20) -> "%.1f MB".format(bytes / (1L shl 20).toDouble())
        bytes >= (1L shl 10) -> "%.1f KB".format(bytes / (1L shl 10).toDouble())
        else -> "$bytes B"
    }
}
