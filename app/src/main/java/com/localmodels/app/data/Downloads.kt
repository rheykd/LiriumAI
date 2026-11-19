package com.localmodels.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class DownloadStatus { RUNNING, DONE, FAILED }

data class DownloadState(
    val modelId: String,
    val received: Long,
    val total: Long,
    val status: DownloadStatus,
    val error: String? = null,
    val speedBytesPerSec: Long = 0L,
    val etaSeconds: Long = 0L
) {
    val progress: Float
        get() = if (total <= 0) 0f else (received.toFloat() / total.toFloat()).coerceIn(0f, 1f)
}

object Downloads {

    private val _states = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val states = _states.asStateFlow()

    fun get(modelId: String): DownloadState? = _states.value[modelId]

    fun update(state: DownloadState) {
        _states.update { it + (state.modelId to state) }
    }

    fun clear(modelId: String) {
        _states.update { it - modelId }
    }

    fun isActive(modelId: String): Boolean = _states.value[modelId]?.status == DownloadStatus.RUNNING
}

fun formatSpeed(bytesPerSec: Long): String {
    if (bytesPerSec <= 0) return ""
    val mb = bytesPerSec / 1024.0 / 1024.0
    return if (mb >= 1.0) {
        String.format(java.util.Locale.US, "%.1f МБ/с", mb)
    } else {
        String.format(java.util.Locale.US, "%.0f КБ/с", bytesPerSec / 1024.0)
    }
}

fun formatEta(seconds: Long): String {
    if (seconds <= 0) return ""
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return when {
        h > 0 -> "$h ч $m мин"
        m > 0 -> "$m мин"
        else -> "$s с"
    }
}
