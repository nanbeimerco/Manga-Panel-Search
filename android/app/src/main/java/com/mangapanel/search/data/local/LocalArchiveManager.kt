package com.mangapanel.search.data.local

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.github.junrar.Archive
import com.github.junrar.rarfile.FileHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.*
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.zip.ZipInputStream

fun decodeSampledBitmap(bytes: ByteArray, maxDimension: Int = 1200): Bitmap? {
    try {
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        val origW = options.outWidth
        val origH = options.outHeight
        if (origW <= 0 || origH <= 0) return null

        var sampleSize = 1
        val maxSide = maxOf(origW, origH)
        while ((maxSide / sampleSize) > maxDimension) {
            sampleSize *= 2
        }

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
    } catch (e: Throwable) {
        Log.e("LocalMangaArchive", "Error or OOM decoding sampled bitmap", e)
        return null
    }
}

sealed class LocalMangaArchive(
    val name: String,
    val type: String,
    val uri: Uri
) {
    abstract val pageCount: Int
    abstract fun getPageBytes(context: Context, pageIndex: Int): ByteArray?
    abstract fun getPageBitmap(context: Context, pageIndex: Int): Bitmap?
}

/**
 * ZIP / CBZ Archive handler with automatic UTF-8 / MS932 (Shift-JIS) fallback.
 */
class LocalZipArchive(
    name: String,
    type: String,
    uri: Uri,
    private val pageEntries: List<String>,
    private val charset: Charset
) : LocalMangaArchive(name, type, uri) {
    override val pageCount: Int = pageEntries.size

    override fun getPageBytes(context: Context, pageIndex: Int): ByteArray? {
        if (pageIndex < 0 || pageIndex >= pageEntries.size) return null
        val targetName = pageEntries[pageIndex]
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                ZipInputStream(stream, charset).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        if (entry.name == targetName) {
                            val buffer = ByteArrayOutputStream()
                            val data = ByteArray(8192)
                            var count: Int
                            while (zis.read(data).also { count = it } != -1) {
                                buffer.write(data, 0, count)
                            }
                            return buffer.toByteArray()
                        }
                        entry = zis.nextEntry
                    }
                    null
                }
            }
        } catch (e: Exception) {
            Log.w("LocalZipArchive", "Failed reading page $pageIndex in $name", e)
            null
        }
    }

    override fun getPageBitmap(context: Context, pageIndex: Int): Bitmap? {
        val bytes = getPageBytes(context, pageIndex) ?: return null
        return decodeSampledBitmap(bytes, maxDimension = 1200)
    }
}

/**
 * RAR / CBR Archive handler using Junrar.
 */
class LocalRarArchive(
    name: String,
    type: String,
    uri: Uri,
    private val pageFileNames: List<String>
) : LocalMangaArchive(name, type, uri) {
    override val pageCount: Int = pageFileNames.size

    override fun getPageBytes(context: Context, pageIndex: Int): ByteArray? {
        if (pageIndex < 0 || pageIndex >= pageFileNames.size) return null
        val targetName = pageFileNames[pageIndex]

        // Copy uri to temp file if not exists or reuse
        val cacheFile = File(context.cacheDir, "rar_${name.hashCode()}.rar")
        try {
            if (!cacheFile.exists() || cacheFile.length() == 0L) {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(cacheFile).use { output ->
                        input.copyTo(output)
                    }
                }
            }

            Archive(cacheFile).use { archive ->
                var fh = archive.nextFileHeader()
                while (fh != null) {
                    if (!fh.isDirectory && (fh.fileNameString == targetName || fh.fileNameW == targetName)) {
                        val bos = ByteArrayOutputStream()
                        archive.extractFile(fh, bos)
                        return bos.toByteArray()
                    }
                    fh = archive.nextFileHeader()
                }
            }
        } catch (e: Exception) {
            Log.w("LocalRarArchive", "Failed reading RAR page $pageIndex in $name", e)
        }
        return null
    }

    override fun getPageBitmap(context: Context, pageIndex: Int): Bitmap? {
        val bytes = getPageBytes(context, pageIndex) ?: return null
        return decodeSampledBitmap(bytes, maxDimension = 1200)
    }
}

/**
 * Directory archive containing image files directly.
 */
class LocalDirectoryArchive(
    name: String,
    uri: Uri,
    private val pageFiles: List<DocumentFile>
) : LocalMangaArchive(name, "DIR", uri) {
    override val pageCount: Int = pageFiles.size

    override fun getPageBytes(context: Context, pageIndex: Int): ByteArray? {
        if (pageIndex < 0 || pageIndex >= pageFiles.size) return null
        val doc = pageFiles[pageIndex]
        return try {
            context.contentResolver.openInputStream(doc.uri)?.use { stream ->
                stream.readBytes()
            }
        } catch (e: Exception) {
            Log.w("LocalDirectoryArchive", "Failed reading file ${doc.name}", e)
            null
        }
    }

    override fun getPageBitmap(context: Context, pageIndex: Int): Bitmap? {
        val bytes = getPageBytes(context, pageIndex) ?: return null
        return decodeSampledBitmap(bytes, maxDimension = 1200)
    }
}

data class RegisteredUriItem(
    val uriString: String,
    val displayName: String,
    val isTree: Boolean
)

class LocalArchiveManager(private val context: Context) {
    private val TAG = "LocalArchiveManager"
    private val prefs = context.getSharedPreferences("local_archive_prefs", Context.MODE_PRIVATE)
    private val PREF_KEY_URIS = "registered_uris"

    private var cachedArchives: List<LocalMangaArchive>? = null

    fun getRegisteredUris(): List<RegisteredUriItem> {
        val rawSet = prefs.getStringSet(PREF_KEY_URIS, emptySet()) ?: emptySet()
        return rawSet.mapNotNull { entry ->
            val parts = entry.split("|", limit = 3)
            if (parts.size >= 3) {
                RegisteredUriItem(
                    uriString = parts[0],
                    displayName = parts[1],
                    isTree = parts[2].toBoolean()
                )
            } else null
        }
    }

    fun addTreeUri(uri: Uri): Boolean {
        return try {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, flags)
            val doc = DocumentFile.fromTreeUri(context, uri)
            val name = doc?.name ?: uri.lastPathSegment ?: "Manga Folder"
            saveUriEntry(uri.toString(), name, true)
            cachedArchives = null
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed addTreeUri for $uri", e)
            false
        }
    }

    fun addDocumentUri(uri: Uri): Boolean {
        return try {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            try {
                context.contentResolver.takePersistableUriPermission(uri, flags)
            } catch (_: Exception) {}
            val doc = DocumentFile.fromSingleUri(context, uri)
            val name = doc?.name ?: uri.lastPathSegment ?: "Manga File"
            saveUriEntry(uri.toString(), name, false)
            cachedArchives = null
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed addDocumentUri for $uri", e)
            false
        }
    }

    fun removeUri(uriString: String) {
        val current = getRegisteredUris().toMutableList()
        current.removeAll { it.uriString == uriString }
        val newSet = current.map { "${it.uriString}|${it.displayName}|${it.isTree}" }.toSet()
        prefs.edit().putStringSet(PREF_KEY_URIS, newSet).apply()
        cachedArchives = null
    }

    private fun saveUriEntry(uriString: String, displayName: String, isTree: Boolean) {
        val current = getRegisteredUris().toMutableList()
        current.removeAll { it.uriString == uriString }
        current.add(RegisteredUriItem(uriString, displayName, isTree))
        val newSet = current.map { "${it.uriString}|${it.displayName}|${it.isTree}" }.toSet()
        prefs.edit().putStringSet(PREF_KEY_URIS, newSet).apply()
    }

    suspend fun getArchivesAsync(forceRescan: Boolean = false): List<LocalMangaArchive> = withContext(Dispatchers.IO) {
        if (cachedArchives == null || forceRescan) {
            cachedArchives = scanAllArchives()
        }
        cachedArchives ?: emptyList()
    }

    fun getArchives(): List<LocalMangaArchive> {
        return cachedArchives ?: scanAllArchives().also { cachedArchives = it }
    }

    fun getArchiveByName(name: String): LocalMangaArchive? {
        return getArchives().find { it.name == name }
    }

    fun getPageBitmap(archiveName: String, pageIndex: Int): Bitmap? {
        val arc = getArchiveByName(archiveName) ?: return null
        return arc.getPageBitmap(context, pageIndex)
    }

    private fun scanAllArchives(): List<LocalMangaArchive> {
        val result = mutableListOf<LocalMangaArchive>()
        val registered = getRegisteredUris()

        for (item in registered) {
            try {
                val uri = Uri.parse(item.uriString)
                if (item.isTree) {
                    val treeDoc = DocumentFile.fromTreeUri(context, uri)
                    if (treeDoc != null && treeDoc.exists()) {
                        scanTreeDirectory(treeDoc, result, depth = 0)
                    }
                } else {
                    val fileDoc = DocumentFile.fromSingleUri(context, uri)
                    if (fileDoc != null && fileDoc.exists()) {
                        createArchiveFromFile(fileDoc)?.let { result.add(it) }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error scanning target: ${item.displayName}", e)
            }
        }
        return result
    }

    private fun scanTreeDirectory(dir: DocumentFile, outList: MutableList<LocalMangaArchive>, depth: Int) {
        if (depth > 4) return // prevent too deep recursion
        val children = dir.listFiles()
        val imageFiles = mutableListOf<DocumentFile>()

        for (child in children) {
            val name = child.name ?: continue
            val lower = name.lowercase()
            if (child.isDirectory) {
                scanTreeDirectory(child, outList, depth + 1)
            } else if (lower.endsWith(".cbz") || lower.endsWith(".zip") || lower.endsWith(".rar") || lower.endsWith(".cbr")) {
                createArchiveFromFile(child)?.let { outList.add(it) }
            } else if (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".webp")) {
                imageFiles.add(child)
            }
        }

        // If directory itself contains multiple images (e.g., extracted manga folder)
        if (imageFiles.isNotEmpty()) {
            imageFiles.sortBy { it.name ?: "" }
            outList.add(LocalDirectoryArchive(dir.name ?: "Images", dir.uri, imageFiles))
        }
    }

    private fun createArchiveFromFile(file: DocumentFile): LocalMangaArchive? {
        val name = file.name ?: return null
        val lower = name.lowercase()

        // 1. Check ZIP / CBZ
        if (lower.endsWith(".cbz") || lower.endsWith(".zip")) {
            val type = if (lower.endsWith(".cbz")) "CBZ" else "ZIP"
            // Try UTF-8 first, fallback to MS932 (Shift-JIS)
            var archive = scanZipEntries(file, name, type, StandardCharsets.UTF_8)
            if (archive == null) {
                archive = scanZipEntries(file, name, type, Charset.forName("MS932"))
            }
            return archive
        }

        // 2. Check RAR / CBR
        if (lower.endsWith(".rar") || lower.endsWith(".cbr")) {
            val type = if (lower.endsWith(".cbr")) "CBR" else "RAR"
            return scanRarEntries(file, name, type)
        }

        return null
    }

    private fun scanZipEntries(file: DocumentFile, name: String, type: String, charset: Charset): LocalZipArchive? {
        return try {
            val entries = mutableListOf<String>()
            context.contentResolver.openInputStream(file.uri)?.use { stream ->
                ZipInputStream(stream, charset).use { zis ->
                    var e = zis.nextEntry
                    while (e != null) {
                        val eLower = e.name.lowercase()
                        if (!e.isDirectory && (eLower.endsWith(".jpg") || eLower.endsWith(".jpeg") || eLower.endsWith(".png") || eLower.endsWith(".webp"))) {
                            entries.add(e.name)
                        }
                        e = zis.nextEntry
                    }
                }
            }
            if (entries.isNotEmpty()) {
                entries.sort()
                LocalZipArchive(name, type, file.uri, entries, charset)
            } else null
        } catch (e: Exception) {
            Log.d(TAG, "Zip parse failed with charset $charset for $name: ${e.message}")
            null
        }
    }

    private fun scanRarEntries(file: DocumentFile, name: String, type: String): LocalRarArchive? {
        val cacheFile = File(context.cacheDir, "rar_scan_${name.hashCode()}.rar")
        return try {
            context.contentResolver.openInputStream(file.uri)?.use { input ->
                FileOutputStream(cacheFile).use { output ->
                    input.copyTo(output)
                }
            }

            val entries = mutableListOf<String>()
            Archive(cacheFile).use { archive ->
                var fh: FileHeader? = archive.nextFileHeader()
                while (fh != null) {
                    val fn = fh.fileNameString ?: fh.fileNameW ?: ""
                    val eLower = fn.lowercase()
                    if (!fh.isDirectory && (eLower.endsWith(".jpg") || eLower.endsWith(".jpeg") || eLower.endsWith(".png") || eLower.endsWith(".webp"))) {
                        entries.add(fn)
                    }
                    fh = archive.nextFileHeader()
                }
            }
            if (entries.isNotEmpty()) {
                entries.sort()
                LocalRarArchive(name, type, file.uri, entries)
            } else null
        } catch (e: Exception) {
            Log.w(TAG, "Rar parse failed for $name", e)
            null
        } finally {
            try { cacheFile.delete() } catch (_: Exception) {}
        }
    }
}
