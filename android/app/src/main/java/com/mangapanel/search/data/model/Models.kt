package com.mangapanel.search.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class BoundingBox(
    val x: Int,
    val y: Int,
    val w: Int,
    val h: Int
)

@Serializable
data class SearchResultItem(
    @SerialName("archive_name") val archiveName: String,
    @SerialName("archive_path") val archivePath: String,
    @SerialName("page_index") val pageIndex: Int,
    @SerialName("page_number") val pageNumber: Int,
    @SerialName("page_filename") val pageFilename: String,
    val score: Float,
    @SerialName("inliers_count") val inliersCount: Int,
    @SerialName("total_matches") val totalMatches: Int,
    @SerialName("bounding_box") val boundingBox: BoundingBox,
    @SerialName("page_image_url") val pageImageUrl: String
)

@Serializable
data class SearchResponse(
    val success: Boolean,
    val message: String? = null,
    @SerialName("normalize_photo_applied") val normalizePhotoApplied: Boolean = true,
    val results: List<SearchResultItem> = emptyList()
)

@Serializable
data class SystemStatus(
    @SerialName("is_indexing") val isIndexing: Boolean,
    @SerialName("progress_percent") val progressPercent: Float,
    @SerialName("total_archives") val totalArchives: Int,
    @SerialName("total_pages") val totalPages: Int,
    @SerialName("indexed_pages") val indexedPages: Int,
    @SerialName("current_file") val currentFile: String,
    @SerialName("data_dir") val dataDir: String,
    @SerialName("last_error") val lastError: String = ""
)

@Serializable
data class ArchiveItem(
    val name: String,
    val type: String,
    @SerialName("page_count") val pageCount: Int,
    @SerialName("cached_pages") val cachedPages: Int,
    @SerialName("is_fully_indexed") val isFullyIndexed: Boolean
)

@Serializable
data class LibraryResponse(
    val archives: List<ArchiveItem> = emptyList(),
    @SerialName("data_dir") val dataDir: String
)

@Serializable
data class TargetPathItem(
    val path: String,
    val name: String,
    @SerialName("is_dir") val isDir: Boolean,
    val exists: Boolean
)

@Serializable
data class TargetPathsResponse(
    val paths: List<TargetPathItem> = emptyList(),
    @SerialName("total_archives") val totalArchives: Int = 0
)

@Serializable
data class PathRequest(
    val path: String
)

