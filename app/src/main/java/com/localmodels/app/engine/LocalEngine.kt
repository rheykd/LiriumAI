package com.localmodels.app.engine

import android.content.Context
import com.localmodels.app.data.CatalogModel
import com.localmodels.app.data.ModelStore
import com.localmodels.app.data.ChatTurn
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Single shared llama.cpp engine. The app UI and the LAN server both use it,
 * so loading, chatting and unloading are serialized through one lock.
 */
object LocalEngine {

    private val lock = ReentrantLock(true)
    private val sessions = SessionTracker()

    fun useSession(value: String) = lock.withLock {
        sessions.ensure(value) {
            if (loadedModelId != null) LlamaBridge.reset()
        }
    }

    fun useConversation(id: String, history: List<ChatTurn>, system: String, cancelled: () -> Boolean = { false }) = lock.withLock {
        LlamaBridge.clearStop()
        if (cancelled()) throw IllegalStateException("stopped")
        val key = "chat:$id:model:$loadedModelId:system:$system"
        sessions.ensure(key) {
            LlamaBridge.restoreHistory(history.map { it.role }.toTypedArray(), history.map { it.text }.toTypedArray(), system)
        }
    }

    @Volatile
    var loadedModelId: String? = null
        private set

    @Volatile
    var vision: Boolean = false
        private set

    fun load(context: Context, store: ModelStore, model: CatalogModel): Int {
        lock.withLock {
            if (!store.isInstalled(model)) return 4
            LlamaBridge.init(context.applicationInfo.nativeLibraryDir)
            if (loadedModelId == model.id) return 0
            sessions.invalidate()
            LlamaBridge.unload()
            loadedModelId = null
            vision = false
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
            val modelPath = store.file(model, model.primary).absolutePath
            val mmprojPath = model.mmproj?.let { store.file(model, it).absolutePath }
            val rc = LlamaBridge.load(modelPath, mmprojPath, model.context, threads)
            if (rc == 0) {
                loadedModelId = model.id
                vision = LlamaBridge.visionSupported()
                if (model.vision && !vision) {
                    LlamaBridge.unload()
                    loadedModelId = null
                    return 3
                }
            }
            return rc
        }
    }

    fun unload() {
        lock.withLock {
            sessions.invalidate()
            LlamaBridge.unload()
            loadedModelId = null
            vision = false
        }
    }

    fun reset() {
        lock.withLock { sessions.invalidate(); LlamaBridge.reset() }
    }

    fun stop() {
        LlamaBridge.stop()
    }

    fun chat(
        text: String,
        images: Array<ByteArray>,
        widths: IntArray,
        heights: IntArray,
        maxTokens: Int,
        temp: Float,
        topP: Float,
        topK: Int,
        repeatPenalty: Float,
        system: String?,
        sink: TokenSink,
        cancelled: () -> Boolean = { false }
    ) {
        lock.withLock {
            LlamaBridge.clearStop()
            if (cancelled()) throw IllegalStateException("stopped")
            try { LlamaBridge.chat(
                text, images, widths, heights,
                maxTokens, temp, topP, topK, repeatPenalty,
                system ?: "", sink
            )
                if (cancelled()) sessions.invalidate()
            } catch (e: Throwable) { sessions.invalidate(); throw e }
        }
    }

    fun <T> exclusive(action: () -> T): T = lock.withLock(action)
}
