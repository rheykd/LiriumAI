package com.localmodels.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun ChatsIcon(color: Color = TextSecondary, size: Dp = 20.dp) {
    Canvas(Modifier.size(size)) {
        val s = this.size.minDimension
        val w = s * 0.07f
        for (y in listOf(0.25f, 0.5f, 0.75f)) {
            drawLine(color, Offset(s * 0.15f, s * y), Offset(s * 0.85f, s * y), w, StrokeCap.Round)
        }
    }
}

@Composable
fun PlusIcon(color: Color = TextPrimary, size: Dp = 20.dp) {
    Canvas(Modifier.size(size)) {
        val s = this.size.minDimension
        val w = s * 0.055f
        drawLine(color, Offset(s * 0.22f, s * 0.5f), Offset(s * 0.78f, s * 0.5f), w, StrokeCap.Round)
        drawLine(color, Offset(s * 0.5f, s * 0.22f), Offset(s * 0.5f, s * 0.78f), w, StrokeCap.Round)
    }
}

@Composable
fun SendIcon(color: Color = Color.Black, size: Dp = 18.dp) {
    Canvas(Modifier.size(size)) {
        val s = this.size.minDimension
        val w = s * 0.075f
        drawLine(color, Offset(s * 0.5f, s * 0.78f), Offset(s * 0.5f, s * 0.24f), w, StrokeCap.Round)
        drawLine(color, Offset(s * 0.5f, s * 0.24f), Offset(s * 0.26f, s * 0.48f), w, StrokeCap.Round)
        drawLine(color, Offset(s * 0.5f, s * 0.24f), Offset(s * 0.74f, s * 0.48f), w, StrokeCap.Round)
    }
}

@Composable
fun StopIcon(color: Color = Color.Black, size: Dp = 16.dp) {
    Canvas(Modifier.size(size)) {
        val s = this.size.minDimension
        drawRect(color, Offset(s * 0.28f, s * 0.28f), Size(s * 0.44f, s * 0.44f))
    }
}

@Composable
fun ChevronDown(color: Color = TextSecondary, size: Dp = 14.dp) {
    Canvas(Modifier.size(size)) {
        val s = this.size.minDimension
        val w = s * 0.09f
        drawLine(color, Offset(s * 0.24f, s * 0.4f), Offset(s * 0.5f, s * 0.64f), w, StrokeCap.Round)
        drawLine(color, Offset(s * 0.5f, s * 0.64f), Offset(s * 0.76f, s * 0.4f), w, StrokeCap.Round)
    }
}

@Composable
fun BackIcon(color: Color = TextPrimary, size: Dp = 20.dp) {
    Canvas(Modifier.size(size)) {
        val s = this.size.minDimension
        val w = s * 0.06f
        drawLine(color, Offset(s * 0.42f, s * 0.22f), Offset(s * 0.2f, s * 0.5f), w, StrokeCap.Round)
        drawLine(color, Offset(s * 0.2f, s * 0.5f), Offset(s * 0.42f, s * 0.78f), w, StrokeCap.Round)
    }
}

@Composable
fun PhotoIcon(color: Color = TextSecondary, size: Dp = 20.dp) {
    Canvas(Modifier.size(size)) {
        val s = this.size.minDimension
        val stroke = Stroke(s * 0.06f)
        drawRoundRect(
            color,
            Offset(s * 0.16f, s * 0.2f),
            Size(s * 0.68f, s * 0.6f),
            androidx.compose.ui.geometry.CornerRadius(s * 0.12f),
            style = stroke
        )
        drawCircle(color, s * 0.055f, Offset(s * 0.36f, s * 0.38f))
        val p = Path().apply {
            moveTo(s * 0.24f, s * 0.72f)
            lineTo(s * 0.44f, s * 0.5f)
            lineTo(s * 0.58f, s * 0.66f)
            lineTo(s * 0.68f, s * 0.56f)
            lineTo(s * 0.78f, s * 0.7f)
        }
        drawPath(p, color, style = stroke)
    }
}

@Composable
fun CloseIcon(color: Color = TextSecondary, size: Dp = 12.dp) {
    Canvas(Modifier.size(size)) {
        val s = this.size.minDimension
        val w = s * 0.11f
        drawLine(color, Offset(s * 0.22f, s * 0.22f), Offset(s * 0.78f, s * 0.78f), w, StrokeCap.Round)
        drawLine(color, Offset(s * 0.78f, s * 0.22f), Offset(s * 0.22f, s * 0.78f), w, StrokeCap.Round)
    }
}

@Composable
fun SettingsIcon(color: Color = TextPrimary, size: Dp = 20.dp) {
    Canvas(Modifier.size(size)) {
        val s = this.size.minDimension
        val w = s * 0.055f
        val xs = listOf(0.72f, 0.34f, 0.58f)
        val ys = listOf(0.26f, 0.5f, 0.74f)
        for (i in 0..2) {
            drawLine(color, Offset(s * 0.18f, s * ys[i]), Offset(s * 0.82f, s * ys[i]), w, StrokeCap.Round)
            drawCircle(Bg, s * 0.1f, Offset(s * xs[i], s * ys[i]))
            drawCircle(color, s * 0.075f, Offset(s * xs[i], s * ys[i]))
        }
    }
}

@Composable
fun FileIcon(color: Color = TextSecondary, size: Dp = 20.dp) {
    Canvas(Modifier.size(size)) {
        val s = this.size.minDimension
        val stroke = Stroke(s * 0.06f)
        val p = Path().apply {
            moveTo(s * 0.26f, s * 0.14f)
            lineTo(s * 0.6f, s * 0.14f)
            lineTo(s * 0.74f, s * 0.3f)
            lineTo(s * 0.74f, s * 0.86f)
            lineTo(s * 0.26f, s * 0.86f)
            close()
        }
        drawPath(p, color, style = stroke)
        drawLine(color, Offset(s * 0.6f, s * 0.14f), Offset(s * 0.6f, s * 0.3f), s * 0.05f)
        drawLine(color, Offset(s * 0.6f, s * 0.3f), Offset(s * 0.74f, s * 0.3f), s * 0.05f)
    }
}
