package ru.dmitriy.workouts.feature.today

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.dmitriy.workouts.ui.theme.*
import kotlin.math.*

private data class BloomPoint(val x: Float, val y: Float, val z: Float, val color: Int)
private data class BloomFace(val corners: List<BloomPoint>, val depth: Float)
private val bloomRaster by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { renderBloom().asImageBitmap() }

/** Locally generated once off the UI thread; works with the software Canvas on API 26+. */
@Composable internal fun TrainingBloom(phase: TodayPhase, modifier: Modifier = Modifier) {
    val raster by produceState<ImageBitmap?>(null) {
        value = withContext(Dispatchers.Default) { bloomRaster }
    }
    val recovery = phase == TodayPhase.Recovery
    val completed = phase == TodayPhase.Completed || phase == TodayPhase.Saved
    val motion = rememberInfiniteTransition(label = "Дыхание формы")
    val breath by motion.animateFloat(0f, 1f, infiniteRepeatable(
        tween(if (recovery) Motion.BloomRecoveryDuration else if (completed) Motion.BloomCompletedDuration else Motion.BloomTrainingDuration,
            easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "Плавное движение")
    val glow by animateFloatAsState(if (recovery) .28f else if (completed) .55f else 1f,
        tween(Motion.Duration), label = "Свечение дня")
    val saturation by animateFloatAsState(if (recovery) .18f else if (completed) .65f else 1f,
        tween(Motion.Duration), label = "Цвет формы")
    val floatPx = with(LocalDensity.current) { Space.BloomFloat.toPx() }
    val filter = remember(saturation) { ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(saturation) }) }
    Box(modifier.semantics {
        stateDescription = when {
            recovery -> "Приглушённая форма, день восстановления"
            completed -> "Спокойное свечение, занятие сохранено"
            else -> "Мятное свечение, тренировочный день"
        }
    }, contentAlignment = Alignment.Center) {
        ComposeCanvas(Modifier.fillMaxSize()) {
            val d = size.minDimension
            val center = Offset(size.width * .5f, size.height * .5f)
            drawCircle(Brush.radialGradient(
                0f to AppColors.Glow.copy(alpha = (.23f + breath * .07f) * glow),
                .36f to AppColors.Glow.copy(alpha = .13f * glow),
                .68f to AppColors.Glow.copy(alpha = .045f * glow),
                1f to AppColors.Transparent,
                center = center, radius = d * (.6f + breath * .04f)), radius = d * .64f, center = center)
        }
        raster?.let {
            Image(it, contentDescription = null, colorFilter = filter,
                modifier = Modifier.fillMaxSize().testTag("today-bloom").graphicsLayer {
                    val pulse = if (recovery || completed) Motion.BloomRecoveryScale else Motion.BloomScale
                    scaleX = 1f + (pulse - 1f) * breath
                    scaleY = scaleX
                    translationY = (breath - .5f) * floatPx * if (recovery) .5f else 1f
                    rotationZ = (breath - .5f) * Motion.BloomTurn
                    rotationY = (breath - .5f) * Motion.BloomTurn
                    alpha = .58f + glow * .42f
                })
        }
    }
}

private fun renderBloom(): Bitmap {
    val shade = AppColors.BloomShade
    val teal = AppColors.BloomTeal
    fun rotate(x: Double, y: Double, z: Double): Triple<Double, Double, Double> {
        val tilt = .72
        val turn = -.38
        val y1 = y * cos(tilt) - z * sin(tilt)
        val z1 = y * sin(tilt) + z * cos(tilt)
        return Triple(x * cos(turn) - y1 * sin(turn), x * sin(turn) + y1 * cos(turn), z1)
    }
    fun point(u: Double, v: Double): BloomPoint {
        val radius = .275 + .034 * sin(3 * u + .3)
        val tube = .105 + .014 * cos(2 * u - .5)
        val xyz = rotate((radius + tube * cos(v)) * cos(u), (radius + tube * cos(v)) * sin(u), tube * sin(v) + .035 * sin(2 * u))
        val normal = rotate(cos(v) * cos(u), cos(v) * sin(u), sin(v))
        val diffuse = max(0.0, normal.first * -.36 + normal.second * -.55 + normal.third * .75)
        val highlight = max(0.0, normal.first * -.22 + normal.second * -.34 + normal.third * .91).pow(20.0)
        val light = (.12 + .70 * diffuse + .23 * highlight).toFloat().coerceIn(0f, 1f)
        val tint = ((sin(u - .4) + 1) * .5).toFloat()
        return BloomPoint(xyz.first.toFloat(), xyz.second.toFloat(), xyz.third.toFloat(), lerp(shade, lerp(teal, AppColors.Accent, tint), light).toArgb())
    }
    val around = 144
    val cross = 48
    val grid = Array(around + 1) { i -> Array(cross + 1) { j -> point(i * 2 * PI / around, j * 2 * PI / cross) } }
    val faces = buildList {
        for (i in 0 until around) for (j in 0 until cross) {
            val corners = listOf(grid[i][j], grid[i + 1][j], grid[i + 1][j + 1], grid[i][j + 1])
            add(BloomFace(corners, corners.sumOf { it.z.toDouble() }.toFloat() / 4))
        }
    }.sortedBy { it.depth }
    val resolution = 1024
    val vertices = FloatArray(faces.size * 12)
    val colors = IntArray(faces.size * 6)
    var cursor = 0
    for (face in faces) for (index in intArrayOf(0, 1, 2, 0, 2, 3)) {
        val p = face.corners[index]
        vertices[cursor * 2] = resolution * (.50f + p.x)
        vertices[cursor * 2 + 1] = resolution * (.49f + p.y)
        colors[cursor] = p.color
        cursor++
    }
    return Bitmap.createBitmap(resolution, resolution, Bitmap.Config.ARGB_8888).also { bitmap ->
        // Interpolated vertex colours produce a smooth surface without per-frame path allocation.
        Canvas(bitmap).drawVertices(Canvas.VertexMode.TRIANGLES, vertices.size, vertices, 0, null, 0, colors, 0, null, 0, 0,
            Paint().apply { color = AppColors.BloomHighlight.toArgb(); isDither = true })
    }
}
