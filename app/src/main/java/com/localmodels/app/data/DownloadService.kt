package com.localmodels.app.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.localmodels.app.MainActivity
import com.localmodels.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var lastNotify = 0L

    private var lastSampleTime = 0L
    private var lastSampleBytes = 0L
    private var emaSpeed = 0.0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val modelId = intent?.getStringExtra(EXTRA_MODEL_ID) ?: return START_NOT_STICKY
        ensureChannel()
        startForeground(NOTIFICATION_ID, buildNotification("загрузка", 0, 0))
        job?.cancel()
        job = scope.launch { runDownload(modelId) }
        return START_NOT_STICKY
    }

    private suspend fun runDownload(modelId: String) {
        lastSampleTime = 0L
        lastSampleBytes = 0L
        emaSpeed = 0.0
        val model = Catalog.load(this, Prefs(this).customCatalogJson).firstOrNull { it.id == modelId }
        if (model == null) {
            finish(modelId, DownloadStatus.FAILED, "модель не найдена")
            return
        }
        val store = ModelStore(this)
        try {
            var completed = 0L
            for (f in model.files) {
                val existing = if (store.file(model, f).let { it.isFile && it.length() == f.bytes }) f.bytes else 0L
                completed += existing
            }
            for (f in model.files) {
                val target = store.file(model, f)
                if (target.isFile && target.length() == f.bytes) continue
                target.parentFile?.mkdirs()
                val part = store.partFile(model, f)
                val base = completed
                var lastState = 0L
                lastSampleTime = 0L
                lastSampleBytes = 0L
                emaSpeed = 0.0
                downloadFile(model, f, part) { downloaded ->
                    val received = (base + downloaded).coerceAtMost(model.bytes)
                    val now = System.currentTimeMillis()
                    if (now - lastState > 150 || received >= model.bytes) {
                        lastState = now
                        val speed = updateSpeed(received)
                        val eta = if (speed > 0) (model.bytes - received + speed - 1) / speed else 0L
                        Downloads.update(
                            DownloadState(
                                modelId, received, model.bytes, DownloadStatus.RUNNING,
                                speedBytesPerSec = speed,
                                etaSeconds = eta.coerceAtLeast(0L)
                            )
                        )
                        if (now - lastNotify > 700) {
                            lastNotify = now
                            val pct = if (model.bytes > 0) ((received * 100) / model.bytes).toInt() else 0
                            var text = "${model.name} / $pct%"
                            val sp = formatSpeed(speed)
                            if (sp.isNotEmpty()) text += " / $sp"
                            val etaLabel = formatEta(eta)
                            if (etaLabel.isNotEmpty()) text += " / $etaLabel"
                            notify(text, received, model.bytes)
                        }
                    }
                }
                if (part.length() != f.bytes) throw IOException("размер файла не совпал")
                if (target.exists()) target.delete()
                if (!part.renameTo(target)) throw IOException("не удалось сохранить файл")
                completed += f.bytes
            }
            if (!store.isInstalled(model)) throw IOException("файлы повреждены")
            Downloads.update(DownloadState(modelId, model.bytes, model.bytes, DownloadStatus.DONE))
            notify("${model.name} / готово", model.bytes, model.bytes)
            finish(modelId, DownloadStatus.DONE, null)
        } catch (e: Exception) {
            Downloads.update(
                DownloadState(
                    modelId,
                    Downloads.get(modelId)?.received ?: 0L,
                    model.bytes,
                    DownloadStatus.FAILED,
                    e.message ?: "ошибка сети",
                    Downloads.get(modelId)?.speedBytesPerSec ?: 0L,
                    Downloads.get(modelId)?.etaSeconds ?: 0L
                )
            )
            finish(modelId, DownloadStatus.FAILED, null)
        }
    }

    /** EMA over ~250 ms samples, so the ETA does not jump on every chunk. */
    private fun updateSpeed(received: Long): Long {
        val now = SystemClock.elapsedRealtime()
        if (received < lastSampleBytes) {
            lastSampleTime = 0L
            emaSpeed = 0.0
        }
        if (lastSampleTime == 0L) {
            lastSampleTime = now
            lastSampleBytes = received
            return 0L
        }
        val dt = now - lastSampleTime
        if (dt >= 250) {
            val inst = (received - lastSampleBytes) * 1000.0 / dt
            emaSpeed = if (emaSpeed <= 0.0) inst else emaSpeed * 0.7 + inst * 0.3
            lastSampleTime = now
            lastSampleBytes = received
        }
        return emaSpeed.toLong().coerceAtLeast(0L)
    }

    private fun downloadFile(
        model: CatalogModel,
        f: ModelFile,
        part: File,
        onProgress: (Long) -> Unit
    ) {
        var url = f.url
        var lastError: Exception? = null
        val attempts = if (url.contains("huggingface.co")) 2 else 1

        for (attempt in 0 until attempts) {
            try {
                var downloaded = if (part.exists()) part.length() else 0L
                if (downloaded > f.bytes) {
                    part.delete()
                    downloaded = 0L
                }
                if (downloaded == f.bytes) return

                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 30_000
                    readTimeout = 60_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "LiriumAI/1.1 (Android)")
                    setRequestProperty("Accept", "*/*")
                    if (downloaded > 0) setRequestProperty("Range", "bytes=$downloaded-")
                }

                val code = conn.responseCode
                if (code == HttpURLConnection.HTTP_UNAUTHORIZED || code == HttpURLConnection.HTTP_FORBIDDEN) {
                    throw IOException("доступ запрещён ($code)")
                }
                if (code == 416) {
                    part.delete()
                    downloaded = 0L
                    throw IOException("повтор после 416")
                }
                if (code !in 200..299) throw IOException("HTTP $code")

                val append = code == 206 && downloaded > 0
                if (!append) downloaded = 0L

                conn.inputStream.use { input ->
                    RandomAccessFile(part, "rw").use { raf ->
                        if (!append) raf.setLength(0)
                        raf.seek(downloaded)
                        val buf = ByteArray(256 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            raf.write(buf, 0, n)
                            downloaded += n
                            onProgress(downloaded)
                        }
                    }
                }
                if (downloaded != f.bytes) throw IOException("загрузка прервана")
                return
            } catch (e: Exception) {
                lastError = e
                if (attempt + 1 < attempts) {
                    url = url.replace("huggingface.co", "hf-mirror.com")
                    // mirror may serve different bytes: restart this file cleanly
                    runCatching { if (part.exists()) part.delete() }
                    lastSampleTime = 0L
                    lastSampleBytes = 0L
                    emaSpeed = 0.0
                }
            }
        }
        throw lastError ?: IOException("ошибка загрузки")
    }

    private fun finish(modelId: String, status: DownloadStatus, error: String?) {
        if (status == DownloadStatus.DONE) {
            Downloads.update(
                DownloadState(
                    modelId,
                    Downloads.get(modelId)?.total ?: 0L,
                    Downloads.get(modelId)?.total ?: 0L,
                    status
                )
            )
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(CHANNEL_ID, "загрузки", NotificationManager.IMPORTANCE_LOW)
            channel.setShowBadge(false)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String, received: Long, total: Long): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(pending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (total > 0) {
            builder.setProgress(1000, ((received * 1000) / total).toInt(), false)
        } else {
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    private fun notify(text: String, received: Long, total: Long) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text, received, total))
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_MODEL_ID = "model_id"
        private const val CHANNEL_ID = "downloads"
        private const val NOTIFICATION_ID = 41

        fun start(context: Context, modelId: String) {
            val intent = Intent(context, DownloadService::class.java).putExtra(EXTRA_MODEL_ID, modelId)
            context.startForegroundService(intent)
        }
    }
}
