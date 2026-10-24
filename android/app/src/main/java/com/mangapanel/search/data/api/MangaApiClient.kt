package com.mangapanel.search.data.api

import com.mangapanel.search.data.model.LibraryResponse
import com.mangapanel.search.data.model.PathRequest
import com.mangapanel.search.data.model.SearchResponse
import com.mangapanel.search.data.model.SystemStatus
import com.mangapanel.search.data.model.TargetPathsResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

class MangaApiClient(
    var baseUrl: String = "http://10.0.2.2:8088"
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    suspend fun getStatus(): Result<SystemStatus> = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url("$baseUrl/api/status").build()
            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: return@withContext Result.failure(Exception("Empty body"))
            Result.success(json.decodeFromString<SystemStatus>(body))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getLibrary(): Result<LibraryResponse> = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url("$baseUrl/api/library").build()
            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: return@withContext Result.failure(Exception("Empty body"))
            Result.success(json.decodeFromString<LibraryResponse>(body))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun startIndexing(): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("$baseUrl/api/index/start")
                .post("".toRequestBody("application/json".toMediaTypeOrNull()))
                .build()
            val resp = client.newCall(req).execute()
            Result.success(resp.isSuccessful)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun searchPanel(
        imageBytes: ByteArray,
        filename: String = "panel.jpg",
        minScore: Float = 0.20f,
        normalizePhoto: Boolean = true,
        candidatePoolSize: Int = 0,
        minPanelAreaPercent: Float = 2.0f
    ): Result<SearchResponse> = withContext(Dispatchers.IO) {
        try {
            val imageBody = imageBytes.toRequestBody("image/jpeg".toMediaTypeOrNull())
            val multipart = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", filename, imageBody)
                .addFormDataPart("min_score", minScore.toString())
                .addFormDataPart("top_k", "5")
                .addFormDataPart("normalize_photo", normalizePhoto.toString())
                .addFormDataPart("candidate_pool_size", candidatePoolSize.toString())
                .addFormDataPart("min_panel_area_percent", minPanelAreaPercent.toString())
                .build()

            val req = Request.Builder()
                .url("$baseUrl/api/search")
                .post(multipart)
                .build()

            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: return@withContext Result.failure(Exception("Empty body"))
            Result.success(json.decodeFromString<SearchResponse>(body))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getTargetPaths(): Result<TargetPathsResponse> = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url("$baseUrl/api/paths").build()
            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: return@withContext Result.failure(Exception("Empty body"))
            Result.success(json.decodeFromString<TargetPathsResponse>(body))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun addTargetPath(path: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val jsonBody = json.encodeToString(PathRequest.serializer(), PathRequest(path))
            val req = Request.Builder()
                .url("$baseUrl/api/paths/add")
                .post(jsonBody.toRequestBody("application/json".toMediaTypeOrNull()))
                .build()
            val resp = client.newCall(req).execute()
            Result.success(resp.isSuccessful)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun removeTargetPath(path: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val jsonBody = json.encodeToString(PathRequest.serializer(), PathRequest(path))
            val req = Request.Builder()
                .url("$baseUrl/api/paths/remove")
                .post(jsonBody.toRequestBody("application/json".toMediaTypeOrNull()))
                .build()
            val resp = client.newCall(req).execute()
            Result.success(resp.isSuccessful)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun getFullImageUrl(relativeOrAbsoluteUrl: String): String {
        return if (relativeOrAbsoluteUrl.startsWith("http")) {
            relativeOrAbsoluteUrl
        } else {
            "$baseUrl$relativeOrAbsoluteUrl"
        }
    }
}
