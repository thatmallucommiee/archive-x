package com.archivex.app

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.util.Locale

/**
 * Read-only PS4 PKG parser and plaintext metadata extractor.
 *
 * The PS4 PKG entry table describes metadata files; the main game payload is commonly
 * a separate encrypted PFS image. This engine deliberately does not decrypt PFS or
 * encrypted table entries. Hatch reclamation is therefore gated whenever any opaque
 * or encrypted payload remains in the package.
 */
object PS4PkgEngine {
    private const val HEADER_SIZE = 0x1000L
    private const val ENTRY_SIZE = 0x20L
    private const val MAX_ENTRIES = 100_000
    private const val MAX_ENTRY_BYTES = 2L * 1024 * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 16L * 1024 * 1024 * 1024
    private const val BUFFER_SIZE = 64 * 1024
    private const val PKG_MAGIC = 0x7F434E54L

    data class Entry(
        val id: Long,
        val filenameOffset: Long,
        val flags1: Long,
        val flags2: Long,
        val offset: Long,
        val size: Long,
        val name: String
    ) {
        val encrypted: Boolean get() = (flags1 and 0x80000000L) != 0L
    }

    data class Header(
        val packageType: Long,
        val fileCount: Long,
        val entryCount: Long,
        val tableOffset: Long,
        val bodyOffset: Long,
        val bodySize: Long,
        val contentOffset: Long,
        val contentSize: Long,
        val contentId: String,
        val drmType: Long,
        val contentType: Long,
        val contentFlags: Long,
        val pfsImageCount: Long,
        val pfsImageOffset: Long,
        val pfsImageSize: Long,
        val declaredPackageSize: Long
    )

    data class Parsed(val header: Header, val entries: List<Entry>, val actualSize: Long)

    fun isPkg(resolver: ContentResolver, uri: Uri): Boolean = try {
        resolver.openInputStream(uri)?.use { input ->
            val magic = ByteArray(4)
            var read = 0
            while (read < 4) {
                val n = input.read(magic, read, 4 - read)
                if (n < 0) return@use false
                read += n
            }
            read == 4 && ByteBuffer.wrap(magic).order(ByteOrder.BIG_ENDIAN).int.toLong()
                .and(0xffffffffL) == PKG_MAGIC
        } ?: false
    } catch (_: Exception) { false }

    fun inspect(resolver: ContentResolver, uri: Uri): String {
        val name = queryName(resolver, uri)
        return try {
            val parsed = openAndParse(resolver, uri)
            val h = parsed.header
            val encrypted = parsed.entries.count { it.encrypted }
            val plain = parsed.entries.count { !it.encrypted }
            val pfsPresent = h.pfsImageCount > 0 || h.pfsImageSize > 0
            buildString {
                appendLine("Selected: $name")
                appendLine("Format: PlayStation 4 PKG")
                appendLine("Package type: 0x%08X".format(Locale.ROOT, h.packageType))
                appendLine("Content ID: ${h.contentId}")
                appendLine("Content type: 0x%X · DRM type: 0x%X".format(Locale.ROOT, h.contentType, h.drmType))
                appendLine("Declared package size: ${human(h.declaredPackageSize)} · Actual: ${human(parsed.actualSize)}")
                appendLine("Entry table: ${parsed.entries.size} records · Plaintext: $plain · Encrypted: $encrypted")
                appendLine("PFS image: ${if (pfsPresent) "present (${human(h.pfsImageSize)})" else "not declared"}")
                appendLine()
                appendLine("ENTRY TABLE")
                parsed.entries.take(180).forEach { entry ->
                    appendLine("${if (entry.encrypted) "[ENCRYPTED]" else "[PLAIN]"} ${entry.name} · ${human(entry.size)} · id=0x%04X".format(Locale.ROOT, entry.id))
                }
                if (parsed.entries.size > 180) appendLine("… first 180 entries shown")
                appendLine()
                if (pfsPresent || encrypted > 0) {
                    appendLine("HATCH STATUS: BLOCKED")
                    appendLine("This package contains a PFS image and/or encrypted entries. The visible table is not the complete game filesystem. Reclaiming the original now would destroy data that this build cannot extract.")
                } else {
                    appendLine("HATCH STATUS: ELIGIBILITY REVIEW REQUIRED")
                    appendLine("No PFS image or encrypted table entries were detected. Full-package coverage and same-file reclamation still require validation before destructive Hatch can be enabled.")
                }
                appendLine("No decryption, DRM removal, or signature bypass is performed.")
            }
        } catch (e: Exception) {
            "Selected: $name\nFormat: PS4 PKG signature detected\nPKG inspection failed: ${e.javaClass.simpleName}: ${e.message ?: "Invalid or unsupported package structure"}"
        }
    }

    /**
     * Extracts all unencrypted table entries to sce_sys/. Encrypted entries and the
     * PFS game image are skipped and explicitly reported. The source is never changed.
     */
    fun extract(resolver: ContentResolver, source: Uri, destinationTree: Uri): String {
        val sourceName = queryName(resolver, source)
        val created = mutableListOf<Uri>()
        var written = 0L
        var extracted = 0
        var skippedEncrypted = 0
        return try {
            val parsed = openAndParse(resolver, source)
            val rootId = DocumentsContract.getTreeDocumentId(destinationTree)
            val root = DocumentsContract.buildDocumentUriUsingTree(destinationTree, rootId)
            val sceSys = findOrCreateDirectory(resolver, root, "sce_sys")

            withChannel(resolver, source) { channel ->
                parsed.entries.forEach { entry ->
                    if (entry.encrypted) {
                        skippedEncrypted++
                        return@forEach
                    }
                    require(entry.size <= MAX_ENTRY_BYTES) { "Entry exceeds 2 GiB safety limit: ${entry.name}" }
                    written = safeAdd(written, entry.size, "Total extracted size overflow.")
                    require(written <= MAX_TOTAL_BYTES) { "PKG metadata extraction exceeds 16 GiB safety limit." }

                    val relative = safeRelativePath(entry.name)
                    val parts = relative.split('/').filter { it.isNotBlank() }
                    require(parts.isNotEmpty()) { "Invalid PKG entry path: ${entry.name}" }
                    var parent = sceSys
                    for (part in parts.dropLast(1)) parent = findOrCreateDirectory(resolver, parent, part)
                    val leaf = uniqueName(resolver, parent, parts.last())
                    val document = DocumentsContract.createDocument(
                        resolver, parent, mimeFor(leaf), leaf
                    ) ?: error("Cannot create PKG output '$leaf'.")
                    created += document

                    val sourceDigest = copyRangeAndHash(channel, entry.offset, entry.size) { buffer, count ->
                        // The callback is intentionally not used for a second write pass.
                    }
                    writeRange(resolver, channel, entry.offset, entry.size, document)
                    val outputDigest = digestDocument(resolver, document)
                    require(sourceDigest.contentEquals(outputDigest)) {
                        "Output verification failed for ${entry.name}; source package was preserved."
                    }
                    extracted++
                }
            }
            val pfsPresent = parsed.header.pfsImageCount > 0 || parsed.header.pfsImageSize > 0
            "PKG EXTRACTION COMPLETE\n\nPackage: $sourceName\nPlaintext table entries extracted: $extracted\nEncrypted entries skipped: $skippedEncrypted\nData written: ${human(written)}\nPFS image: ${if (pfsPresent) "present — not decrypted or extracted" else "not declared"}\n\nOriginal PKG preserved. Output files were SHA-256 checked against their source ranges."
        } catch (e: Exception) {
            created.asReversed().forEach { runCatching { DocumentsContract.deleteDocument(resolver, it) } }
            "PKG EXTRACTION FAILED\n\nPackage: $sourceName\nCause: ${e.javaClass.simpleName}: ${e.message ?: "Unknown error"}\n\nOriginal package was not modified. Incomplete outputs were removed where the provider allowed it."
        }
    }

    /**
     * PKG-specific Hatch gate. Standard metadata extraction remains available, but a
     * normal PS4 game PKG has an opaque PFS payload. This method refuses to consume it.
     */
    fun runHatch(resolver: ContentResolver, source: Uri, destinationTree: Uri): String {
        val parsed = try { openAndParse(resolver, source) } catch (e: Exception) {
            return "PKG HATCH PREFLIGHT FAILED\n\n${e.javaClass.simpleName}: ${e.message ?: "Invalid PKG"}"
        }
        val encrypted = parsed.entries.count { it.encrypted }
        val pfsPresent = parsed.header.pfsImageCount > 0 || parsed.header.pfsImageSize > 0
        if (pfsPresent || encrypted > 0) {
            return "PKG HATCH BLOCKED — ORIGINAL PRESERVED\n\nPFS image: ${if (pfsPresent) human(parsed.header.pfsImageSize) else "none"}\nEncrypted table entries: $encrypted\n\nThis package still contains payload ARCHIVE X cannot safely extract. Reclaiming or truncating the PKG would destroy unextracted game data. Use Standard PKG extraction to export accessible plaintext metadata; the original remains untouched.\n\nNo decryption, DRM removal, or signature bypass is performed."
        }
        // Even a metadata-only PKG needs a full-coverage and crash-recovery proof before
        // source reclamation. Do not pretend table-entry export is equivalent to that.
        return "PKG HATCH NOT YET ELIGIBLE — ORIGINAL PRESERVED\n\nThe PKG table contains no obvious encrypted entry or PFS image, but this build has not yet proven complete byte coverage, package digest validation, and crash-safe reclamation for this package variant.\n\nNo files were removed from the source. Use Standard Mode to extract the accessible package entries."
    }

    private fun openAndParse(resolver: ContentResolver, uri: Uri): Parsed =
        withChannel(resolver, uri) { channel ->
            require(channel.size() >= HEADER_SIZE) { "File is smaller than the 0x1000-byte PS4 PKG header." }
            val headerBytes = readBytes(channel, 0L, HEADER_SIZE.toInt())
            val b = ByteBuffer.wrap(headerBytes).order(ByteOrder.BIG_ENDIAN)
            require(u32(b, 0x00) == PKG_MAGIC) { "PS4 PKG magic mismatch." }
            val entryCount = u32(b, 0x10)
            val tableOffset = u32(b, 0x18)
            require(entryCount in 1..MAX_ENTRIES.toLong()) { "Unreasonable PKG entry count: $entryCount" }
            require(tableOffset >= HEADER_SIZE && tableOffset <= channel.size()) { "PKG entry table offset is outside the file." }
            val tableBytes = safeMultiply(entryCount, ENTRY_SIZE, "PKG entry table size overflow.")
            require(tableOffset <= channel.size() - tableBytes) { "PKG entry table extends beyond end of file." }

            val entriesRaw = ArrayList<LongArray>(entryCount.toInt())
            val table = readBytes(channel, tableOffset, tableBytes.toInt())
            val tb = ByteBuffer.wrap(table).order(ByteOrder.BIG_ENDIAN)
            repeat(entryCount.toInt()) {
                val id = u32(tb, tb.position())
                val nameOff = u32(tb, tb.position() + 4)
                val flags1 = u32(tb, tb.position() + 8)
                val flags2 = u32(tb, tb.position() + 12)
                val offset = u32(tb, tb.position() + 16)
                val size = u32(tb, tb.position() + 20)
                tb.position(tb.position() + ENTRY_SIZE.toInt())
                require(offset <= channel.size() && size <= channel.size() - offset) {
                    "PKG entry 0x${id.toString(16)} points outside the package."
                }
                entriesRaw += longArrayOf(id, nameOff, flags1, flags2, offset, size)
            }

            val namesRecord = entriesRaw.firstOrNull { it[0] == 0x200L }
            val names = namesRecord?.let { readBytes(channel, it[4], it[5].toInt().coerceAtMost(1 shl 20)) }
            val entries = entriesRaw.map { fields ->
                val id = fields[0]
                val fromTable = names?.let { decodeName(it, fields[1]) }
                val fallback = knownName(id)
                val name = safeRelativePath(fromTable?.takeIf { it.isNotBlank() } ?: fallback)
                Entry(id, fields[1], fields[2], fields[3], fields[4], fields[5], name)
            }
            val contentId = String(headerBytes, 0x40, 0x24, Charsets.US_ASCII)
                .trimEnd('\u0000', ' ')
            val h = Header(
                packageType = u32(b, 0x04),
                fileCount = u32(b, 0x0C),
                entryCount = entryCount,
                tableOffset = tableOffset,
                bodyOffset = u64(b, 0x20),
                bodySize = u64(b, 0x28),
                contentOffset = u64(b, 0x30),
                contentSize = u64(b, 0x38),
                contentId = contentId,
                drmType = u32(b, 0x70),
                contentType = u32(b, 0x74),
                contentFlags = u32(b, 0x78),
                pfsImageCount = u32(b, 0x404),
                pfsImageOffset = u64(b, 0x410),
                pfsImageSize = u64(b, 0x418),
                declaredPackageSize = u64(b, 0x430)
            )
            require(h.pfsImageOffset <= channel.size() && h.pfsImageSize <= channel.size() - h.pfsImageOffset) {
                "Declared PFS image range extends beyond end of package."
            }
            Parsed(h, entries, channel.size())
        }

    private inline fun <T> withChannel(
        resolver: ContentResolver,
        uri: Uri,
        block: (FileChannel) -> T
    ): T {
        val pfd = resolver.openFileDescriptor(uri, "r")
            ?: error("Android could not open the selected PKG as a seekable document.")
        pfd.use {
            val stream = FileInputStream(it.fileDescriptor)
            val channel = stream.channel
            require(channel.isOpen && channel.size() >= 4) { "PKG source is not seekable." }
            return block(channel)
        }
    }

    private fun readBytes(channel: FileChannel, offset: Long, length: Int): ByteArray {
        require(offset >= 0 && length >= 0 && offset <= channel.size() - length) { "Read range is outside the PKG." }
        val buffer = ByteBuffer.allocate(length)
        channel.position(offset)
        while (buffer.hasRemaining()) {
            val n = channel.read(buffer)
            if (n < 0) error("Unexpected end of PKG while reading.")
            if (n == 0) continue
        }
        return buffer.array()
    }

    private fun copyRangeAndHash(
        channel: FileChannel,
        offset: Long,
        length: Long,
        onChunk: (ByteArray, Int) -> Unit
    ): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_SIZE)
        var remaining = length
        channel.position(offset)
        while (remaining > 0) {
            val wanted = minOf(buffer.size.toLong(), remaining).toInt()
            val bb = ByteBuffer.wrap(buffer, 0, wanted)
            val n = channel.read(bb)
            if (n < 0) error("Unexpected end of PKG entry.")
            if (n == 0) continue
            digest.update(buffer, 0, n)
            onChunk(buffer, n)
            remaining -= n
        }
        return digest.digest()
    }

    private fun writeRange(resolver: ContentResolver, channel: FileChannel, offset: Long, length: Long, output: Uri) {
        val buffer = ByteArray(BUFFER_SIZE)
        var remaining = length
        channel.position(offset)
        resolver.openOutputStream(output, "w")?.use { out ->
            while (remaining > 0) {
                val wanted = minOf(buffer.size.toLong(), remaining).toInt()
                val bb = ByteBuffer.wrap(buffer, 0, wanted)
                val n = channel.read(bb)
                if (n < 0) error("Unexpected end of PKG entry during extraction.")
                if (n == 0) continue
                out.write(buffer, 0, n)
                remaining -= n
            }
            out.flush()
        } ?: error("Destination provider refused to open an output file.")
    }

    private fun digestDocument(resolver: ContentResolver, uri: Uri): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        resolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                if (n > 0) digest.update(buffer, 0, n)
            }
        } ?: error("Cannot reopen extracted PKG entry for verification.")
        return digest.digest()
    }

    private fun decodeName(table: ByteArray, offset: Long): String? {
        if (offset < 0 || offset >= table.size) return null
        val start = offset.toInt()
        var end = start
        while (end < table.size && table[end].toInt() != 0) end++
        if (end == start) return null
        return String(table, start, end - start, Charsets.UTF_8)
            .replace('\u0000', ' ')
            .trim()
            .takeIf { it.isNotBlank() }
    }

    private fun knownName(id: Long): String = when (id) {
        0x0001L -> "_pkg_metadata/digests.bin"
        0x0010L -> "_pkg_metadata/entry_keys.bin"
        0x0020L -> "_pkg_metadata/image_key.bin"
        0x0080L -> "_pkg_metadata/general_digests.bin"
        0x0100L -> "_pkg_metadata/metas.bin"
        0x0200L -> "_pkg_metadata/entry_names.bin"
        0x0400L -> "license.dat"
        0x0401L -> "license.info"
        0x0402L -> "nptitle.dat"
        0x0403L -> "npbind.dat"
        0x0404L -> "selfinfo.dat"
        0x0406L -> "imageinfo.dat"
        0x0409L -> "psreserved.dat"
        0x1000L -> "param.sfo"
        0x1001L -> "playgo-chunk.dat"
        0x1002L -> "playgo-chunk.sha"
        0x1003L -> "playgo-manifest.xml"
        0x1004L -> "pronunciation.xml"
        0x1005L -> "pronunciation.sig"
        0x1006L -> "pic1.png"
        0x1008L -> "app/playgo-chunk.dat"
        0x1009L -> "app/playgo-chunk.sha"
        0x100AL -> "app/playgo-manifest.xml"
        0x1200L -> "icon0.png"
        0x1220L -> "pic0.png"
        0x1240L -> "snd0.at9"
        0x1260L -> "changeinfo/changeinfo.xml"
        0x1280L -> "icon0.dds"
        0x12A0L -> "pic0.dds"
        0x1400L -> "trophy/trophy00.trp"
        else -> "_pkg_metadata/entry_%04X.bin".format(Locale.ROOT, id)
    }

    private fun safeRelativePath(raw: String): String {
        val normalized = raw.replace('\\', '/')
        require(normalized.isNotBlank() && !normalized.startsWith('/') &&
            !normalized.startsWith("~") && !Regex("^[A-Za-z]:").containsMatchIn(normalized) &&
            normalized.indexOf('\u0000') < 0) { "Unsafe PKG entry path." }
        val parts = normalized.split('/').filter { it.isNotBlank() }
        require(parts.isNotEmpty() && parts.none { it == "." || it == ".." }) { "Unsafe PKG entry path." }
        return parts.joinToString("/")
    }

    private fun findOrCreateDirectory(resolver: ContentResolver, parent: Uri, name: String): Uri {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(parent, DocumentsContract.getDocumentId(parent))
        resolver.query(children, arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        ), null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getString(1) == name && cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                    return DocumentsContract.buildDocumentUriUsingTree(parent, cursor.getString(0))
                }
                if (cursor.getString(1) == name) error("PKG destination path collision at '$name'.")
            }
        }
        return DocumentsContract.createDocument(resolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, name)
            ?: error("Cannot create PKG destination directory '$name'.")
    }

    private fun uniqueName(resolver: ContentResolver, parent: Uri, name: String): String {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(parent, DocumentsContract.getDocumentId(parent))
        val used = mutableSetOf<String>()
        resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { c -> while (c.moveToNext()) used += c.getString(0) }
        if (name !in used) return name
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while ("$stem ($i)$ext" in used) i++
        return "$stem ($i)$ext"
    }

    private fun queryName(resolver: ContentResolver, uri: Uri): String =
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (c.moveToFirst() && i >= 0) c.getString(i) ?: "selected.pkg" else "selected.pkg"
        } ?: "selected.pkg"

    private fun u32(b: ByteBuffer, offset: Int): Long = b.getInt(offset).toLong() and 0xffffffffL
    private fun u64(b: ByteBuffer, offset: Int): Long {
        val value = b.getLong(offset)
        require(value >= 0) { "PKG contains an unsupported unsigned 64-bit range." }
        return value
    }
    private fun safeMultiply(a: Long, b: Long, message: String): Long {
        require(a >= 0 && b >= 0 && (a == 0L || b <= Long.MAX_VALUE / a)) { message }
        return a * b
    }
    private fun safeAdd(a: Long, b: Long, message: String): Long {
        require(a >= 0 && b >= 0 && a <= Long.MAX_VALUE - b) { message }
        return a + b
    }
    private fun human(bytes: Long): String = when {
        bytes >= (1L shl 30) -> "%.2f GB".format(bytes / (1L shl 30).toDouble())
        bytes >= (1L shl 20) -> "%.1f MB".format(bytes / (1L shl 20).toDouble())
        bytes >= (1L shl 10) -> "%.1f KB".format(bytes / (1L shl 10).toDouble())
        else -> "$bytes B"
    }
    private fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "xml" -> "application/xml"
        "sfo", "bin", "dat", "sha" -> "application/octet-stream"
        "txt" -> "text/plain"
        else -> "application/octet-stream"
    }
}
