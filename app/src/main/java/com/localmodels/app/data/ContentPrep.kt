package com.localmodels.app.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.os.ParcelFileDescriptor
import java.io.File

data class Attachment(
    val file: File,
    val name: String,
    val mime: String,
    val isImage: Boolean
)

object ContentPrep {

    data class ImageData(val rgb: ByteArray, val width: Int, val height: Int)

    data class Prepared(
        val text: String,
        val images: List<ImageData>
    )

    private const val DEFAULT_IMAGE_SIDE = 1280
    private const val MAX_TEXT_CHARS = 12_000
    private const val MAX_PDF_PAGES = 2

    fun prepare(attachments: List<Attachment>, maxImageSide: Int = DEFAULT_IMAGE_SIDE): Prepared {
        val maxSide = maxImageSide.coerceIn(256, 2048)
        val text = StringBuilder()
        val images = ArrayList<ImageData>()

        for (a in attachments) {
            when {
                a.mime.startsWith("image/") -> {
                    decodeBitmap(a.file, maxSide)?.let { bmp ->
                        toImageData(bmp)?.let { images += it }
                        bmp.recycle()
                    } ?: run {
                        text.append("\n[изображение: ").append(a.name).append("]")
                    }
                }
                a.mime == "application/pdf" || a.name.endsWith(".pdf", true) -> {
                    val pages = renderPdf(a.file, maxSide)
                    if (pages.isEmpty()) {
                        text.append("\n[файл: ").append(a.name).append(" / pdf]")
                    } else {
                        images += pages
                        if (pages.size >= MAX_PDF_PAGES) {
                            text.append("\n[документ: ").append(a.name).append(" / первые ")
                                .append(pages.size).append(" страниц]")
                        }
                    }
                }
                isTextLike(a) -> {
                    val content = runCatching { a.file.readText(Charsets.UTF_8) }.getOrNull()
                    if (content.isNullOrBlank()) {
                        text.append("\n[файл: ").append(a.name).append("]")
                    } else {
                        val clipped = if (content.length > MAX_TEXT_CHARS) {
                            content.substring(0, MAX_TEXT_CHARS) + "\n…"
                        } else content
                        text.append("\n[файл: ").append(a.name).append("]\n")
                        text.append(clipped)
                    }
                }
                else -> {
                    text.append("\n[файл: ").append(a.name)
                        .append(" / ").append(a.mime)
                        .append(" / ").append(formatBytes(a.file.length())).append("]")
                }
            }
        }

        return Prepared(text.toString().trim(), images)
    }

    /** Saves the generated vision test image as PNG for the chat preview. */
    fun createVisionTestPng(file: File): Boolean {
        val bmp = visionTestBitmap()
        val out = file.outputStream()
        return try {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            true
        } finally {
            runCatching { out.close() }
            bmp.recycle()
        }
    }

    /** A generated image with a known number and color, used to prove that vision works. */
    fun visionTestImage(): ImageData {
        val bmp = visionTestBitmap()
        val data = toImageData(bmp)
        bmp.recycle()
        return data ?: ImageData(ByteArray(3), 1, 1)
    }

    fun visionTestBitmap(): Bitmap {
        val size = 512
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        val red = Paint().apply { color = Color.rgb(210, 30, 30); isAntiAlias = false }
        canvas.drawRect(340f, 40f, 480f, 180f, red)
        val blue = Paint().apply { color = Color.rgb(20, 60, 200); isAntiAlias = false }
        canvas.drawRect(340f, 330f, 480f, 470f, blue)
        val text = Paint().apply {
            color = Color.BLACK
            textSize = 190f
            isAntiAlias = true
            isFakeBoldText = true
        }
        canvas.drawText("42", 40f, 300f, text)
        val label = Paint().apply { color = Color.BLACK; textSize = 54f; isAntiAlias = true }
        canvas.drawText("LIRIUM", 34f, 460f, label)
        return bmp
    }

    private fun isTextLike(a: Attachment): Boolean {
        if (a.mime.startsWith("text/")) return true
        val ext = a.name.substringAfterLast('.', "").lowercase()
        return ext in setOf(
            "txt", "md", "markdown", "json", "csv", "tsv", "xml", "html", "htm",
            "yaml", "yml", "toml", "ini", "log", "kt", "kts", "java", "py", "js",
            "ts", "tsx", "jsx", "c", "h", "cpp", "hpp", "cs", "go", "rs", "rb",
            "php", "sh", "bat", "sql", "srt", "properties", "gradle"
        )
    }

    private fun decodeBitmap(file: File, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while ((bounds.outWidth / sample) > maxSide * 2 ||
            (bounds.outHeight / sample) > maxSide * 2
        ) {
            sample *= 2
        }

        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        var bmp = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null

        val scale = minOf(
            1f,
            maxSide.toFloat() / bmp.width,
            maxSide.toFloat() / bmp.height
        )
        if (scale < 1f) {
            val scaled = Bitmap.createScaledBitmap(
                bmp,
                (bmp.width * scale).toInt().coerceAtLeast(1),
                (bmp.height * scale).toInt().coerceAtLeast(1),
                true
            )
            bmp.recycle()
            bmp = scaled
        }

        val rotation = runCatching {
            when (ExifInterface(file.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
            )) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }.getOrDefault(0f)

        if (rotation != 0f) {
            val matrix = Matrix().apply { postRotate(rotation) }
            val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
            bmp.recycle()
            bmp = rotated
        }
        return bmp
    }

    private fun toImageData(bmp: Bitmap): ImageData? {
        val w = bmp.width
        val h = bmp.height
        if (w <= 0 || h <= 0) return null
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        val rgb = ByteArray(w * h * 3)
        var o = 0
        for (p in pixels) {
            rgb[o++] = ((p shr 16) and 0xFF).toByte()
            rgb[o++] = ((p shr 8) and 0xFF).toByte()
            rgb[o++] = (p and 0xFF).toByte()
        }
        return ImageData(rgb, w, h)
    }

    private fun renderPdf(file: File, maxSide: Int): List<ImageData> {
        val result = ArrayList<ImageData>()
        var descriptor: ParcelFileDescriptor? = null
        try {
            descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            PdfRenderer(descriptor).use { renderer ->
                val count = minOf(renderer.pageCount, MAX_PDF_PAGES)
                for (i in 0 until count) {
                    renderer.openPage(i).use { page ->
                        val target = minOf(1240, maxSide)
                        val scale = target.toFloat() / page.width.toFloat()
                        val w = (page.width * scale).toInt().coerceAtLeast(1)
                        val h = (page.height * scale).toInt().coerceAtLeast(1)
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(android.graphics.Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        toImageData(bmp)?.let { result += it }
                        bmp.recycle()
                    }
                }
            }
        } catch (_: Exception) {
            // ignore broken pdf
        } finally {
            runCatching { descriptor?.close() }
        }
        return result
    }
}
