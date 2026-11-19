package com.localmodels.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class ModelFile(
    val name: String,
    val role: String,
    val bytes: Long,
    val url: String
)

data class CatalogModel(
    val id: String,
    val name: String,
    val params: String,
    val quant: String,
    val vision: Boolean,
    val context: Int,
    val bytes: Long,
    val license: String,
    val files: List<ModelFile>,
    val imageMaxSide: Int = 1280,
    val recommendedRamGb: Int = 0,
    val description: String = ""
) {
    val primary: ModelFile get() = files.first { it.role == "model" }
    val mmproj: ModelFile? get() = files.firstOrNull { it.role == "mmproj" }
    val sizeLabel: String get() = formatBytes(bytes)
    val meta: String get() = "$params / $quant / $sizeLabel"
}

fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 МБ"
    val mb = bytes / 1024.0 / 1024.0
    return if (mb >= 1024.0) {
        val gb = mb / 1024.0
        String.format(java.util.Locale.US, "%.1f ГБ", gb)
    } else {
        String.format(java.util.Locale.US, "%.0f МБ", mb)
    }
}

object Catalog {
    const val MAX_MODEL_GB = 10
    const val MAX_MODEL_BYTES = MAX_MODEL_GB * 1024L * 1024 * 1024
    private fun safeSegment(value: String): Boolean =
        value.isNotBlank() && value != "." && value != ".." &&
            value.none { it == '/' || it == '\\' || it == ':' || it.isISOControl() }

    private const val BUILTIN = "builtin"

    @Volatile
    private var cachedKey: String? = null

    @Volatile
    private var cached: List<CatalogModel> = emptyList()

    fun load(context: Context, customJson: String? = null): List<CatalogModel> {
        val key = customJson ?: BUILTIN
        if (cachedKey == key) return cached
        synchronized(this) {
            if (cachedKey == key) return cached
            val builtin = parse(
                context.assets.open("catalog.json").bufferedReader().use { it.readText() }
            )
            val custom = if (customJson.isNullOrBlank()) emptyList() else runCatching {
                parse(customJson)
            }.getOrElse { emptyList() }
            val seen = builtin.map { it.id }.toHashSet()
            val merged = builtin + custom.filter { seen.add(it.id) }
            cached = merged
            cachedKey = key
            return merged
        }
    }

    /** Parses either {"models":[...]} or a bare array. */
    fun parse(raw: String): List<CatalogModel> {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return emptyList()
        val arr: JSONArray = if (trimmed.startsWith("[")) {
            JSONArray(trimmed)
        } else {
            JSONObject(trimmed).getJSONArray("models")
        }
        val list = ArrayList<CatalogModel>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val filesArr = o.optJSONArray("files") ?: continue
            val files = ArrayList<ModelFile>(filesArr.length())
            for (j in 0 until filesArr.length()) {
                val f = filesArr.optJSONObject(j) ?: continue
                val url = f.optString("url", "")
                val name = f.optString("name", "")
                val bytes = f.optLong("bytes", 0L)
                val role = f.optString("role", "model")
                require(safeSegment(name)) { "недопустимое имя файла модели" }
                require(url.startsWith("https://") || url.startsWith("http://")) { "нужна ссылка http или https" }
                require(bytes > 0 && bytes <= MAX_MODEL_BYTES) { "недопустимый размер файла" }
                require(role in listOf("model", "mmproj")) { "неизвестная роль файла" }
                files += ModelFile(name = name, role = role, bytes = bytes, url = url)
            }
            val id = o.optString("id", "")
            require(safeSegment(id)) { "недопустимый id модели" }
            if (id.isBlank() || files.none { it.role == "model" }) continue
            val total = files.sumOf { it.bytes }
            require(total <= MAX_MODEL_BYTES) { "модель больше $MAX_MODEL_GB ГБ" }
            require(files.count { it.role == "model" } == 1) { "нужен один файл модели" }
            require(files.map { it.name }.distinct().size == files.size) { "повтор имён файлов" }
            list += CatalogModel(
                id = id,
                name = o.optString("name", id),
                params = o.optString("params", ""),
                quant = o.optString("quant", ""),
                vision = o.optBoolean("vision", files.any { it.role == "mmproj" }) && files.any { it.role == "mmproj" },
                context = o.optInt("context", 4096).coerceIn(2048, 32768),
                bytes = total,
                license = o.optString("license", ""),
                files = files,
                imageMaxSide = o.optInt("imageMaxSide", 1280).coerceIn(256, 2048),
                recommendedRamGb = o.optInt("recommendedRamGb", 0).coerceIn(0, 64),
                description = o.optString("description", "")
            )
        }
        return list
    }
}
