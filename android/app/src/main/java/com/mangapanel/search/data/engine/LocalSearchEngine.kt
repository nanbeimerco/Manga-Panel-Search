package com.mangapanel.search.data.engine

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.mangapanel.search.data.local.LocalArchiveManager
import com.mangapanel.search.data.local.LocalMangaArchive
import com.mangapanel.search.data.model.BoundingBox
import com.mangapanel.search.data.model.SearchResultItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.calib3d.Calib3d
import org.opencv.core.*
import org.opencv.features2d.BFMatcher
import org.opencv.features2d.SIFT
import org.opencv.imgproc.Imgproc
import java.io.*
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern
import kotlin.math.max
import kotlin.math.min

data class CachedFeatures(
    val keypointCoords: FloatArray, // [x0, y0, x1, y1, ...]
    val descriptors: ByteArray,     // raw bytes of Mat
    val descRows: Int,
    val descCols: Int,
    val imgWidth: Int,
    val imgHeight: Int
)

data class VolumeMetadata(
    val volumeName: String,
    val volumePage: Int?,
    val displayPageLabel: String
)

class LocalSearchEngine(private val context: Context) {
    private val TAG = "LocalSearchEngine"
    private var isOpenCvInitialized = false
    private val sift by lazy { SIFT.create(0, 3, 0.04, 10.0, 1.6) }
    private val cacheDir = File(context.filesDir, "feature_cache").apply { mkdirs() }
    private val ramCache = ConcurrentHashMap<String, CachedFeatures>()

    init {
        initOpenCV()
    }

    private fun initOpenCV() {
        if (!isOpenCvInitialized) {
            isOpenCvInitialized = try {
                if (OpenCVLoader.initLocal()) {
                    Log.d(TAG, "OpenCV initialized successfully via initLocal()")
                    true
                } else {
                    OpenCVLoader.initDebug()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize OpenCV", e)
                false
            }
        }
    }

    fun isReady(): Boolean = isOpenCvInitialized

    /**
     * Parse archive name and page filename to determine volume name and within-volume page number.
     * Identical to PC latest version parse_manga_volume_and_page.
     */
    fun parseMangaVolumeAndPage(archiveName: String, pageFilename: String, pageNumber: Int): VolumeMetadata {
        val cleanFilename = pageFilename.replace("\\", "/")
        val parts = cleanFilename.split("/")

        var volStr = ""
        var volNum: Int? = null
        var pageInVol: Int? = null

        val pVol = Pattern.compile("(?:vol(?:ume)?|第)?\\s*[._\\-]?\\s*(\\d+)(?:\\s*巻)?", Pattern.CASE_INSENSITIVE)

        // Case 1: Check subfolder in archive (e.g. '《犬夜叉》Vol_02/inu02169.jpg')
        if (parts.size > 1) {
            val folder = parts[0]
            val m = pVol.matcher(folder)
            if (m.find()) {
                volNum = m.group(1)?.toIntOrNull()
                if (volNum != null) {
                    volStr = if (folder.contains("第") || folder.contains("巻")) String.format("第%02d巻", volNum) else String.format("Vol.%02d", volNum)
                }
            }
        }

        // Case 2: Check archiveName (e.g. '第10巻.rar')
        if (volStr.isEmpty()) {
            val m = pVol.matcher(archiveName)
            if (m.find()) {
                volNum = m.group(1)?.toIntOrNull()
                if (volNum != null) {
                    volStr = String.format("第%02d巻", volNum)
                }
            }
        }

        // Extract page number within volume from the file basename
        val basename = parts.last()
        val nameNoExt = basename.substringBeforeLast('.')
        val pPage = Pattern.compile("(?:page|p|_|-)?(\\d+)$", Pattern.CASE_INSENSITIVE)
        val mPage = pPage.matcher(nameNoExt)
        if (mPage.find()) {
            val numStr = mPage.group(1)
            if (numStr != null) {
                val prefix = if (volNum != null) String.format("%02d", volNum) else ""
                if (volNum != null && numStr.length >= 4 && numStr.startsWith(prefix)) {
                    pageInVol = numStr.substring(prefix.length).toIntOrNull()
                } else {
                    pageInVol = numStr.toIntOrNull()
                }
            }
        }

        val displayLabel = when {
            volStr.isNotEmpty() && pageInVol != null -> {
                if (parts.size > 1) "$volStr ${pageInVol}ページ (通算 ${pageNumber}ページ)"
                else "$volStr ${pageInVol}ページ"
            }
            volStr.isNotEmpty() -> "$volStr ${pageNumber}ページ"
            pageInVol != null -> "PAGE $pageInVol"
            else -> "PAGE $pageNumber"
        }

        return VolumeMetadata(volStr, pageInVol, displayLabel)
    }

    /**
     * Adaptive paper illumination normalization (Background Division)
     * Matches PC latest version:
     * - Preserves pristine high-contrast digital scans by percentile bypass
     * - Dynamic morphology kernel size based on resolution
     * - Flattens yellow paper tint and ambient shadow to clean white
     */
    fun normalizeMangaPhoto(srcBgr: Mat): Mat {
        val gray = Mat()
        Imgproc.cvtColor(srcBgr, gray, Imgproc.COLOR_BGR2GRAY)

        val h = gray.rows()
        val w = gray.cols()

        // Check if image is already a crisp digital manga (clean whites & blacks)
        // Compute min/max via minMaxLoc
        val mmr = Core.minMaxLoc(gray)
        if (mmr.minVal <= 25.0 && mmr.maxVal >= 235.0) {
            // Crisp scan: bypass destructive background division to preserve delicate lines
            return gray
        }

        var ksize = max(25, min(h, w) / 20)
        if (ksize % 2 == 0) ksize += 1

        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(ksize.toDouble(), ksize.toDouble()))
        val background = Mat()
        Imgproc.morphologyEx(gray, background, Imgproc.MORPH_CLOSE, kernel)

        val divided = Mat()
        Core.divide(gray, background, divided, 255.0)

        val normalized = Mat()
        Core.normalize(divided, normalized, 0.0, 255.0, Core.NORM_MINMAX)

        kernel.release()
        background.release()
        divided.release()
        gray.release()

        return normalized
    }

    /**
     * Extract SIFT features from Bitmap with L2-normalized descriptors.
     */
    fun extractFeatures(bitmap: Bitmap, normalizePhoto: Boolean = false): Pair<MatOfKeyPoint, Mat>? {
        initOpenCV()
        val matBgr = Mat()
        Utils.bitmapToMat(bitmap, matBgr)

        val gray = if (normalizePhoto) {
            val norm = normalizeMangaPhoto(matBgr)
            matBgr.release()
            norm
        } else {
            val g = Mat()
            Imgproc.cvtColor(matBgr, g, Imgproc.COLOR_RGBA2GRAY)
            matBgr.release()
            g
        }

        val keypoints = MatOfKeyPoint()
        val descriptors = Mat()
        sift.detectAndCompute(gray, Mat(), keypoints, descriptors)
        gray.release()

        if (descriptors.empty() || descriptors.rows() < 8) {
            keypoints.release()
            descriptors.release()
            return null
        }

        // L2-normalize descriptors for Euclidean distance (matches PC version fix)
        val normDesc = Mat(descriptors.rows(), descriptors.cols(), CvType.CV_32F)
        for (r in 0 until descriptors.rows()) {
            val rowMat = descriptors.row(r)
            val l2Norm = Core.norm(rowMat, Core.NORM_L2) + 1e-7
            Core.divide(rowMat, Scalar(l2Norm), normDesc.row(r))
        }
        descriptors.release()

        return Pair(keypoints, normDesc)
    }

    /**
     * Check if a specific page's features are already cached on disk or in RAM.
     */
    fun isPageCached(archive: LocalMangaArchive, pageIndex: Int): Boolean {
        val cacheKey = "${archive.name}_page_$pageIndex"
        if (ramCache.containsKey(cacheKey)) return true
        val cacheFile = File(cacheDir, "$cacheKey.bin")
        return cacheFile.exists() && cacheFile.length() > 0
    }

    /**
     * Count already cached pages for progress badge.
     */
    fun getCachedPageCount(archive: LocalMangaArchive): Int {
        var count = 0
        for (i in 0 until archive.pageCount) {
            if (isPageCached(archive, i)) {
                count++
            }
        }
        return count
    }

    /**
     * Precompute features and write directly to disk cache.
     */
    fun precomputePageFeaturesDiskOnly(archive: LocalMangaArchive, pageIndex: Int): Boolean {
        val cacheKey = "${archive.name}_page_$pageIndex"
        val cacheFile = File(cacheDir, "$cacheKey.bin")
        if (cacheFile.exists() && cacheFile.length() > 0) {
            return true
        }

        val bmp = try {
            archive.getPageBitmap(context, pageIndex) ?: return false
        } catch (e: Throwable) {
            Log.w(TAG, "Error getting page bitmap: $cacheKey", e)
            return false
        }

        val width = bmp.width
        val height = bmp.height

        val extracted = try {
            extractFeatures(bmp, normalizePhoto = false)
        } finally {
            bmp.recycle()
        }

        if (extracted == null) return false

        val (kp, desc) = extracted
        return try {
            val kpArray = kp.toArray()
            val kpCoords = FloatArray(kpArray.size * 2)
            for (i in kpArray.indices) {
                kpCoords[i * 2] = kpArray[i].pt.x.toFloat()
                kpCoords[i * 2 + 1] = kpArray[i].pt.y.toFloat()
            }

            val descBytes = ByteArray((desc.total() * desc.elemSize()).toInt())
            desc.get(0, 0, descBytes)

            DataOutputStream(FileOutputStream(cacheFile)).use { dos ->
                dos.writeInt(width)
                dos.writeInt(height)
                dos.writeInt(kpArray.size)
                for (v in kpCoords) dos.writeFloat(v)
                dos.writeInt(desc.rows())
                dos.writeInt(desc.cols())
                dos.writeInt(descBytes.size)
                dos.write(descBytes)
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed saving disk cache for $cacheKey", e)
            false
        } finally {
            kp.release()
            desc.release()
        }
    }

    /**
     * Get or compute cached features for a given archive page.
     */
    fun getOrComputePageFeatures(archive: LocalMangaArchive, pageIndex: Int): CachedFeatures? {
        val cacheKey = "${archive.name}_page_$pageIndex"
        ramCache[cacheKey]?.let { return it }

        val cacheFile = File(cacheDir, "$cacheKey.bin")
        if (cacheFile.exists()) {
            try {
                DataInputStream(FileInputStream(cacheFile)).use { dis ->
                    val width = dis.readInt()
                    val height = dis.readInt()
                    val kpSize = dis.readInt()
                    val kpCoords = FloatArray(kpSize * 2)
                    for (i in 0 until kpSize * 2) {
                        kpCoords[i] = dis.readFloat()
                    }
                    val rows = dis.readInt()
                    val cols = dis.readInt()
                    val byteLen = dis.readInt()
                    val descBytes = ByteArray(byteLen)
                    dis.readFully(descBytes)

                    val cached = CachedFeatures(kpCoords, descBytes, rows, cols, width, height)
                    if (ramCache.size > 200) {
                        ramCache.clear() // Prevent unbounded RAM accumulation
                    }
                    ramCache[cacheKey] = cached
                    return cached
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed reading cache for $cacheKey", e)
            }
        }

        // Compute features from page bitmap
        val bmp = try {
            archive.getPageBitmap(context, pageIndex) ?: return null
        } catch (e: Throwable) {
            return null
        }
        val width = bmp.width
        val height = bmp.height

        val extracted = try {
            extractFeatures(bmp, normalizePhoto = false)
        } finally {
            bmp.recycle()
        }
        if (extracted == null) return null

        val (kp, desc) = extracted
        return try {
            val kpArray = kp.toArray()
            val kpCoords = FloatArray(kpArray.size * 2)
            for (i in kpArray.indices) {
                kpCoords[i * 2] = kpArray[i].pt.x.toFloat()
                kpCoords[i * 2 + 1] = kpArray[i].pt.y.toFloat()
            }

            val descBytes = ByteArray((desc.total() * desc.elemSize()).toInt())
            desc.get(0, 0, descBytes)

            val cached = CachedFeatures(kpCoords, descBytes, desc.rows(), desc.cols(), width, height)
            if (ramCache.size > 200) {
                ramCache.clear()
            }
            ramCache[cacheKey] = cached

            // Save to disk cache
            try {
                DataOutputStream(FileOutputStream(cacheFile)).use { dos ->
                    dos.writeInt(width)
                    dos.writeInt(height)
                    dos.writeInt(kpArray.size)
                    for (v in kpCoords) dos.writeFloat(v)
                    dos.writeInt(desc.rows())
                    dos.writeInt(desc.cols())
                    dos.writeInt(descBytes.size)
                    dos.write(descBytes)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed saving cache for $cacheKey", e)
            }

            cached
        } finally {
            kp.release()
            desc.release()
        }
    }

    /**
     * Search query panel against archives.
     * Fully synchronized with PC latest version:
     * - SIFT + Lowe's ratio test (threshold 0.75)
     * - Bijective 1-to-1 constraint (prevents Many-to-One screentone/hatching collapse)
     * - Spatial dispersion check (threshold 0.05)
     * - RANSAC homography (reprojection error 6.0)
     * - Minimum panel area check
     * - Combined scoring: inlier count (55%) + inlier ratio (30%) + size factor (15%)
     * - Metadata volume/page parsing
     */
    suspend fun searchPanel(
        queryBitmap: Bitmap,
        archives: List<LocalMangaArchive>,
        minScore: Float = 0.20f,
        normalizePhoto: Boolean = true,
        candidatePoolSize: Int = 0,
        minPanelAreaPercent: Float = 1.0f,
        topK: Int = 5
    ): List<SearchResultItem> = withContext(Dispatchers.Default) {
        initOpenCV()
        val qFeatures = extractFeatures(queryBitmap, normalizePhoto = normalizePhoto)
            ?: return@withContext emptyList()

        val (qKp, qDesc) = qFeatures
        val qKpArray = qKp.toArray()
        val qWidth = queryBitmap.width
        val qHeight = queryBitmap.height

        val matcher = BFMatcher.create(BFMatcher.BRUTEFORCE, false)
        val allResults = mutableListOf<SearchResultItem>()

        for (archive in archives) {
            for (pageIndex in 0 until archive.pageCount) {
                val pageFeat = getOrComputePageFeatures(archive, pageIndex) ?: continue
                if (pageFeat.descRows < 8) continue

                val pageDesc = Mat(pageFeat.descRows, pageFeat.descCols, CvType.CV_32F)
                pageDesc.put(0, 0, pageFeat.descriptors)

                val knnMatches = ArrayList<MatOfDMatch>()
                matcher.knnMatch(qDesc, pageDesc, knnMatches, 2)

                val goodMatches = ArrayList<DMatch>()
                for (m in knnMatches) {
                    val matchesArray = m.toArray()
                    if (matchesArray.size >= 2) {
                        if (matchesArray[0].distance < 0.75f * matchesArray[1].distance) {
                            goodMatches.add(matchesArray[0])
                        }
                    }
                }

                if (goodMatches.size < 8) {
                    pageDesc.release()
                    continue
                }

                // Bijective 1-to-1 Constraint (Matches PC version):
                // For each target page feature, keep only the single best query match with smallest distance.
                // Eliminates many-to-one collapse on screen tones and cross-hatching.
                val bestMatchForTarget = HashMap<Int, DMatch>()
                for (gm in goodMatches) {
                    val existing = bestMatchForTarget[gm.trainIdx]
                    if (existing == null || gm.distance < existing.distance) {
                        bestMatchForTarget[gm.trainIdx] = gm
                    }
                }

                val bijectiveMatches = bestMatchForTarget.values.toList()
                if (bijectiveMatches.size < 8) {
                    pageDesc.release()
                    continue
                }

                // Spatial dispersion check on query side:
                // If matched points are concentrated in a microscopic spot (< 5% span), reject before RANSAC (Matches PC 0.05)
                var minQueryX = Double.MAX_VALUE; var maxQueryX = Double.MIN_VALUE
                var minQueryY = Double.MAX_VALUE; var maxQueryY = Double.MIN_VALUE
                for (bm in bijectiveMatches) {
                    val pt = qKpArray[bm.queryIdx].pt
                    minQueryX = min(minQueryX, pt.x); maxQueryX = max(maxQueryX, pt.x)
                    minQueryY = min(minQueryY, pt.y); maxQueryY = max(maxQueryY, pt.y)
                }
                if ((maxQueryX - minQueryX) < qWidth * 0.05 || (maxQueryY - minQueryY) < qHeight * 0.05) {
                    pageDesc.release()
                    continue
                }

                // Prepare points for RANSAC
                val srcPtsList = ArrayList<Point>()
                val dstPtsList = ArrayList<Point>()
                for (bm in bijectiveMatches) {
                    val qPt = qKpArray[bm.queryIdx].pt
                    srcPtsList.add(qPt)

                    val pX = pageFeat.keypointCoords[bm.trainIdx * 2].toDouble()
                    val pY = pageFeat.keypointCoords[bm.trainIdx * 2 + 1].toDouble()
                    dstPtsList.add(Point(pX, pY))
                }

                val srcMat = MatOfPoint2f()
                srcMat.fromList(srcPtsList)
                val dstMat = MatOfPoint2f()
                dstMat.fromList(dstPtsList)

                val inlierMask = Mat()
                // Reprojection error threshold = 6.0 (Matches PC version)
                val H = Calib3d.findHomography(srcMat, dstMat, Calib3d.RANSAC, 6.0, inlierMask)

                val inliers = if (!inlierMask.empty()) {
                    Core.countNonZero(inlierMask)
                } else 0

                if (!H.empty() && inliers >= 8) {
                    // Compute bounding box via Homography
                    val qCorners = MatOfPoint2f(
                        Point(0.0, 0.0),
                        Point(qWidth.toDouble(), 0.0),
                        Point(qWidth.toDouble(), qHeight.toDouble()),
                        Point(0.0, qHeight.toDouble())
                    )
                    val transformedCorners = MatOfPoint2f()
                    Core.perspectiveTransform(qCorners, transformedCorners, H)
                    val pts = transformedCorners.toArray()

                    var minX = Double.MAX_VALUE
                    var maxX = Double.MIN_VALUE
                    var minY = Double.MAX_VALUE
                    var maxY = Double.MIN_VALUE
                    for (p in pts) {
                        minX = min(minX, p.x)
                        maxX = max(maxX, p.x)
                        minY = min(minY, p.y)
                        maxY = max(maxY, p.y)
                    }

                    val bX = max(0, minX.toInt())
                    val bY = max(0, minY.toInt())
                    val bW = max(10, min(pageFeat.imgWidth - bX, (maxX - minX).toInt()))
                    val bH = max(10, min(pageFeat.imgHeight - bY, (maxY - minY).toInt()))

                    // Minimum panel area filter
                    val pageArea = (pageFeat.imgWidth * pageFeat.imgHeight).toDouble()
                    val boxArea = (bW * bH).toDouble()
                    val areaPercent = if (pageArea > 0) (boxArea / pageArea) * 100.0 else 0.0

                    if (areaPercent >= minPanelAreaPercent && bW > 10 && bH > 10) {
                        // Scoring formula (Matches PC latest version: inliers (55%) + ratio (30%) + size (15%))
                        val inlierRatio = inliers.toDouble() / max(1, goodMatches.size)
                        val sizeFactor = min(1.0, areaPercent / 4.0)
                        val score = min(1.0, (inliers.toDouble() / 30.0) * 0.55 + inlierRatio * 0.30 + sizeFactor * 0.15).toFloat()

                        if (score >= minScore) {
                            val pageNum = pageIndex + 1
                            val volMeta = parseMangaVolumeAndPage(archive.name, "page_${pageNum}.jpg", pageNum)

                            allResults.add(
                                SearchResultItem(
                                    archiveName = archive.name,
                                    archivePath = archive.uri.toString(),
                                    pageIndex = pageIndex,
                                    pageNumber = pageNum,
                                    pageFilename = volMeta.displayPageLabel,
                                    score = score,
                                    inliersCount = inliers,
                                    totalMatches = goodMatches.size,
                                    boundingBox = BoundingBox(bX, bY, bW, bH),
                                    pageImageUrl = "local://${archive.name}/$pageIndex"
                                )
                            )
                        }
                    }

                    qCorners.release()
                    transformedCorners.release()
                }

                srcMat.release()
                dstMat.release()
                inlierMask.release()
                H.release()
                pageDesc.release()
            }
        }

        qKp.release()
        qDesc.release()

        // Sort descending by score & inliers (Matches PC version)
        allResults.sortWith(compareByDescending<SearchResultItem> { it.score }.thenByDescending { it.inliersCount })
        return@withContext allResults.take(topK)
    }
}
