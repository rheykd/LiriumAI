package com.localmodels.app.data

import org.json.JSONObject

data class SourceRef(val title: String, val url: String, val excerpt: String) {
    fun json() = JSONObject().put("title", title).put("url", url).put("excerpt", excerpt)
    companion object {
        fun parse(json: JSONObject) = SourceRef(json.optString("title"), json.getString("url"), json.optString("excerpt"))
    }
}

data class GenerationStats(val firstTokenMs: Long, val totalMs: Long, val tokens: Int?, val generationMs: Long, val processRamBytes: Long, val model: String) {
    val tokensPerSecond: Double? get() = tokens?.takeIf { generationMs > 0 }?.let { it * 1000.0 / generationMs }
    fun json() = JSONObject().put("firstTokenMs", firstTokenMs).put("totalMs", totalMs).put("tokens", tokens ?: JSONObject.NULL)
        .put("generationMs", generationMs).put("processRamBytes", processRamBytes).put("model", model)
    companion object {
        fun parse(j: JSONObject) = GenerationStats(j.optLong("firstTokenMs", -1), j.optLong("totalMs"),
            if (j.isNull("tokens")) null else j.optInt("tokens"), j.optLong("generationMs"), j.optLong("processRamBytes"), j.optString("model"))
    }
}
