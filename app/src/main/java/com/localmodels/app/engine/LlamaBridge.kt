package com.localmodels.app.engine

interface TokenSink {
    fun onToken(text: String)
    fun onStats(tokens: Int, generationMs: Long) {}
}

/**
 * Thin JNI facade over llama.cpp + libmtmd.
 * All heavy calls must be made from a background thread.
 */
object LlamaBridge {

    @Volatile
    private var initialized = false

    fun init(nativeLibDir: String) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            System.loadLibrary("local_llm")
            nativeInit(nativeLibDir)
            initialized = true
        }
    }

    private external fun nativeInit(nativeLibDir: String)

    external fun load(modelPath: String, mmprojPath: String?, nCtx: Int, nThreads: Int): Int

    external fun unload()

    external fun reset()

    external fun restoreHistory(roles: Array<String>, texts: Array<String>, system: String)

    external fun visionSupported(): Boolean

    external fun clearStop()

    external fun stop()

    external fun systemInfo(): String

    external fun chat(
        text: String,
        images: Array<ByteArray>,
        widths: IntArray,
        heights: IntArray,
        maxTokens: Int,
        temp: Float,
        topP: Float,
        topK: Int,
        repeatPenalty: Float,
        system: String,
        sink: TokenSink
    )
}
