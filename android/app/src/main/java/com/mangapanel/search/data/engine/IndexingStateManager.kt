package com.mangapanel.search.data.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class IndexingProgressInfo(
    val isRunning: Boolean = false,
    val progress: Float = 0f,
    val currentVolume: String = "",
    val progressText: String = "",
    val completedPages: Int = 0,
    val totalPages: Int = 0,
    val isFinished: Boolean = false
)

object IndexingStateManager {
    private val _state = MutableStateFlow(IndexingProgressInfo())
    val state: StateFlow<IndexingProgressInfo> = _state.asStateFlow()

    @Volatile
    var isCancelRequested: Boolean = false

    fun start(total: Int) {
        isCancelRequested = false
        _state.value = IndexingProgressInfo(
            isRunning = true,
            progress = 0f,
            currentVolume = "",
            progressText = "準備中...",
            completedPages = 0,
            totalPages = total,
            isFinished = false
        )
    }

    fun updateProgress(currentVolume: String, completed: Int, total: Int, detailText: String) {
        val frac = if (total > 0) completed.toFloat() / total.toFloat() else 0f
        _state.value = IndexingProgressInfo(
            isRunning = true,
            progress = frac,
            currentVolume = currentVolume,
            progressText = detailText,
            completedPages = completed,
            totalPages = total,
            isFinished = false
        )
    }

    fun finish() {
        _state.value = _state.value.copy(
            isRunning = false,
            progress = 1f,
            progressText = "インデックス完了！",
            isFinished = true
        )
    }

    fun cancel() {
        isCancelRequested = true
        _state.value = _state.value.copy(
            isRunning = false,
            progressText = "インデックスを一時停止しました（作成済みデータは保存されています）"
        )
    }

    fun reset() {
        isCancelRequested = false
        _state.value = IndexingProgressInfo()
    }
}
