package com.localmodels.app.data

import android.app.ActivityManager
import android.content.Context
import android.os.StatFs
import java.io.File
import kotlin.math.max

data class DeviceCapacity(val totalRam: Long, val availableRam: Long, val freeStorage: Long)
data class ModelAssessment(val estimatedRam: Long, val load: String, val warnings: List<String>)

object Compatibility {
    fun capacity(context: Context): DeviceCapacity {
        val memory = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(memory)
        return DeviceCapacity(memory.totalMem, memory.availMem, StatFs(context.filesDir.path).availableBytes)
    }
    fun assess(model: CatalogModel, device: DeviceCapacity, remainingBytes: Long = model.bytes): ModelAssessment {
        // Weight mapping, KV/context and vision buffers; deliberately an estimate, not a benchmark.
        val estimated = (model.bytes * 1.2).toLong() + model.context * 65536L +
            (if (model.vision) 768L else 384L) * 1024 * 1024
        val warnings = buildList {
            if (device.availableRam < estimated) add("свободной ОЗУ меньше оценки: модель может не загрузиться или Android закроет приложение")
            if (model.recommendedRamGb > 0 && device.totalRam < model.recommendedRamGb * 1024L * 1024 * 1024)
                add("общая ОЗУ ниже ориентира каталога: ${model.recommendedRamGb} ГБ")
            if (device.freeStorage < max(0, remainingBytes) + 256L * 1024 * 1024) add("недостаточно свободного места для загрузки с запасом 256 МБ")
        }
        val load = when {
            model.bytes >= 4L * 1024 * 1024 * 1024 -> "очень высокая нагрузка на CPU; ответы могут быть медленными"
            model.bytes >= 2L * 1024 * 1024 * 1024 || model.vision -> "высокая нагрузка на CPU, особенно при обработке фото"
            else -> "умеренная нагрузка на CPU"
        }
        return ModelAssessment(estimated, load, warnings)
    }
}

data class DownloadCheck(val model: CatalogModel, val device: DeviceCapacity, val assessment: ModelAssessment)
