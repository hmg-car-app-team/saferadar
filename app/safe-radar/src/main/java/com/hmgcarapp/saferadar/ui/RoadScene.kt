package com.hmgcarapp.saferadar.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.lerp
import com.hmgcarapp.pleos.designsystem.theme.PleosTheme
import com.hmgcarapp.saferadar.domain.Direction
import kotlin.math.sin

/**
 * 주행 화면 배경(읽기 전용). 하늘색 그라디언트 위에 원근 도로, 뒤에서 본 내 차, 내 차 둘레 8방향 감지 원호,
 * 내 차에서 퍼지는 하늘색 파장을 그린다.
 *
 * 주변 차량은 서버가 Geohash7 셀(약 150m)과 8방향으로만 알려 주므로 차로 위 위치나 다른 차 모델은 그리지 않는다.
 * 위험 차량이 있는 방향은 해당 원호만 빨갛게 맥동한다([Direction.NEAR] 이면 원호 전체).
 * 색은 Pleos 토큰에서만 만든다(하늘색 = regulation_blue 와 흰색을 섞은 값).
 */
@Composable
fun RoadScene(speedKmh: Float?, steerDeg: Float?, alertDirection: Direction?, modifier: Modifier = Modifier) {
    val pal = rememberRoadPalette()
    val loop = rememberInfiniteTransition(label = "road")
    val ripple by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(RoadMotion.RIPPLE_MS, easing = LinearEasing)), label = "ripple")
    val pulse by loop.animateFloat(0.55f, 1f, infiniteRepeatable(tween(RoadMotion.PULSE_MS), RepeatMode.Reverse), label = "pulse")
    val curve by animateFloatAsState(RoadMotion.curveFor(steerDeg), tween(RoadMotion.CURVE_MS), label = "curve")
    val speed by rememberUpdatedState(speedKmh ?: 0f)
    // 차선 점선의 이동량(주기 단위 0..1): 실제 속도에 비례해 누적한다. 정차하면 멈춘다.
    var travel by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) travel = (travel + speed / 3.6f * ((now - last) / 1_000_000_000f) / RoadMotion.DASH_CYCLE_M) % 1f
                last = now
            }
        }
    }
    Canvas(modifier) {
        val g = RoadGeometry(size.width, size.height, curve)
        drawSky(pal)
        drawRoad(g, pal, travel)
        drawHalo(g, pal)
        drawRipples(g, pal, ripple)
        drawSensorArcs(g, pal, alertDirection, pulse, front = true)
        drawEgoCar(g, pal)
        drawSensorArcs(g, pal, alertDirection, pulse, front = false)
    }
}

/** 순수 값: 방향 -> 원호 각도, 조향각 -> 도로 휨. */
internal object RoadMotion {
    const val RIPPLE_MS = 2_600
    const val PULSE_MS = 650
    const val CURVE_MS = 600
    /** 점선 한 주기(칠한 선 + 빈칸)를 실제 도로 9m 로 본다. */
    const val DASH_CYCLE_M = 9f
    /** 운전대를 이만큼 돌리면 도로가 최대로 휜다(VHAL 관례: 오른쪽이 +). */
    const val STEER_FULL_DEG = 90f
    const val SWEEP_DEG = 36f
    const val RIPPLE_COUNT = 3

    val SECTORS = listOf(Direction.FRONT, Direction.FRONT_RIGHT, Direction.RIGHT, Direction.REAR_RIGHT, Direction.REAR, Direction.REAR_LEFT, Direction.LEFT, Direction.FRONT_LEFT)

    fun curveFor(steerDeg: Float?): Float = ((steerDeg ?: 0f) / STEER_FULL_DEG).coerceIn(-1f, 1f)

    /** Compose 원호 각도(0 = 화면 오른쪽, 시계 방향). 진행 방향(앞)이 화면 위다. [Direction.NEAR] 는 특정 원호가 없다. */
    fun angleOf(d: Direction): Float? = when (d) {
        Direction.FRONT -> -90f
        Direction.FRONT_RIGHT -> -45f
        Direction.RIGHT -> 0f
        Direction.REAR_RIGHT -> 45f
        Direction.REAR -> 90f
        Direction.REAR_LEFT -> 135f
        Direction.LEFT -> 180f
        Direction.FRONT_LEFT -> -135f
        Direction.NEAR -> null
    }

    /** 차 뒤에 가려지는 쪽(앞·옆)은 차보다 먼저 그리고, 뒤쪽 원호는 차 위에 그린다. */
    fun isBehindCar(angleDeg: Float): Boolean = sin(Math.toRadians(angleDeg.toDouble())) <= 0.01
}

@Immutable
private data class RoadPalette(
    val skyTop: Color, val skyMid: Color, val skyBottom: Color,
    val content: Color, val wave: Color, val danger: Color,
    val carBody: Color, val carLight: Color, val glass: Color,
)

@Composable
private fun rememberRoadPalette(): RoadPalette {
    val c = PleosTheme.colors
    return remember(c) {
        val sky = lerp(c.regulationBlue, c.basicStaticLight100, 0.45f)
        RoadPalette(
            skyTop = c.backgroundPrimary,
            skyMid = lerp(c.backgroundPrimary, sky, if (c.isDark) 0.22f else 0.38f),
            skyBottom = lerp(c.backgroundPrimary, c.regulationBlue, if (c.isDark) 0.42f else 0.30f),
            content = c.textDarkPrimary,
            wave = sky,
            danger = c.regulationRed,
            carBody = c.basicStaticDark100,
            carLight = c.basicStaticLight100,
            glass = lerp(c.basicStaticDark100, c.regulationBlue, 0.28f),
        )
    }
}

/** 장면 배치. 모두 캔버스 크기에 비례하므로 폼팩터(Connect-S/L/W)마다 같은 구도가 된다. */
private class RoadGeometry(val w: Float, val h: Float, curve: Float) {
    /** 위쪽 속도 표시와 겹치지 않게 지평선을 화면 30% 높이에 둔다. */
    val horizonY = h * 0.30f
    val carW = minOf(w * 0.15f, h * 0.26f)
    val carH = carW * 0.82f
    val cx = w / 2f
    val groundY = h * 0.84f
    private val laneHalf = carW * 0.95f
    val vanishX = cx + curve * w * 0.20f
    val sensorCenter = Offset(cx, groundY - carH * 0.40f)
    val rippleCenter = Offset(cx, groundY - carH * 0.12f)

    private fun halfAt(y: Float, lanes: Float) = laneHalf * lanes * (y - horizonY) / (groundY - horizonY)
    private fun bottom(lanes: Float) = Offset(cx + halfAt(h, lanes), h)
    private fun control(lanes: Float): Offset {
        val my = (horizonY + h) / 2f
        return Offset(cx + halfAt(my, lanes) + (vanishX - cx) * 0.15f, my)
    }

    /** 차선 하나: 화면 아래에서 소실점까지. [lanes] 는 내 차로 반폭의 배수(±1 = 내 차로 경계, ±3 = 도로 가장자리). */
    fun line(lanes: Float): Path = Path().apply {
        val b = bottom(lanes); val c = control(lanes)
        moveTo(b.x, b.y); quadraticBezierTo(c.x, c.y, vanishX, horizonY)
    }

    /** 두 차선 사이 면. */
    fun band(left: Float, right: Float): Path = Path().apply {
        val bl = bottom(left); val cl = control(left); val br = bottom(right); val cr = control(right)
        moveTo(bl.x, bl.y); quadraticBezierTo(cl.x, cl.y, vanishX, horizonY)
        quadraticBezierTo(cr.x, cr.y, br.x, br.y); close()
    }
}

private fun DrawScope.drawSky(p: RoadPalette) =
    drawRect(Brush.verticalGradient(0f to p.skyTop, 0.45f to p.skyMid, 1f to p.skyBottom))

private fun DrawScope.drawRoad(g: RoadGeometry, p: RoadPalette, travel: Float) {
    fun fade(color: Color, alpha: Float) = Brush.verticalGradient(0f to color.copy(alpha = 0f), 1f to color.copy(alpha = alpha), startY = g.horizonY, endY = g.h)
    drawPath(g.band(-3f, 3f), fade(p.content, 0.10f))
    // 내 차로: 하늘색 주행 카펫
    drawPath(g.band(-1f, 1f), Brush.verticalGradient(0f to p.wave.copy(alpha = 0f), 0.7f to p.wave.copy(alpha = 0.16f), 1f to p.wave.copy(alpha = 0.30f), startY = g.horizonY, endY = g.h))
    val width = g.carW * 0.022f
    for (edge in floatArrayOf(-3f, 3f)) drawPath(g.line(edge), fade(p.content, 0.40f), style = Stroke(width, cap = StrokeCap.Round))
    // 내 차로 경계는 점선: 속도에 맞춰 화면 아래로 흐른다
    val dash = g.carH * 0.42f; val gap = g.carH * 0.58f
    val effect = PathEffect.dashPathEffect(floatArrayOf(dash, gap), travel * (dash + gap))
    for (lane in floatArrayOf(-1f, 1f)) drawPath(g.line(lane), fade(p.content, 0.65f), style = Stroke(width, pathEffect = effect))
}

/** 첨부 레퍼런스처럼 차 너머가 밝게 번지는 빛. */
private fun DrawScope.drawHalo(g: RoadGeometry, p: RoadPalette) {
    val c = Offset(g.cx, g.groundY - g.carH * 0.85f)
    val r = g.carW * 1.3f
    scale(1.8f, 0.55f, pivot = c) {
        drawCircle(Brush.radialGradient(0f to p.carLight.copy(alpha = 0.40f), 0.55f to p.wave.copy(alpha = 0.16f), 1f to Color.Transparent, center = c, radius = r), r, c)
    }
}

/** 내 차에서 바닥을 따라 퍼지는 하늘색 파장(감지 범위가 살아 있음을 보여 준다). */
private fun DrawScope.drawRipples(g: RoadGeometry, p: RoadPalette, progress: Float) {
    val c = g.rippleCenter
    repeat(RoadMotion.RIPPLE_COUNT) { i ->
        val t = (progress + i.toFloat() / RoadMotion.RIPPLE_COUNT) % 1f
        val rx = g.carW * (0.8f + 2.9f * t)
        val ry = rx * 0.34f
        val fadeOut = (1f - t) * (1f - t)
        drawOval(p.wave.copy(alpha = 0.60f * fadeOut), Offset(c.x - rx, c.y - ry), Size(rx * 2, ry * 2), style = Stroke(g.carW * (0.03f * (1f - t) + 0.008f)))
    }
}

private fun DrawScope.drawSensorArcs(g: RoadGeometry, p: RoadPalette, alert: Direction?, pulse: Float, front: Boolean) {
    val c = g.sensorCenter
    val rx = g.carW * 1.15f
    val ry = rx * 0.62f
    val band = g.carW * 0.13f
    RoadMotion.SECTORS.forEach { d ->
        val angle = RoadMotion.angleOf(d) ?: return@forEach
        if (RoadMotion.isBehindCar(angle) != front) return@forEach
        val start = angle - RoadMotion.SWEEP_DEG / 2
        if (alert == d || alert == Direction.NEAR) {
            arc(c, rx, ry, start, p.danger.copy(alpha = 0.18f * pulse), band * 2.6f)
            arc(c, rx * 1.36f, ry * 1.36f, start, p.danger.copy(alpha = 0.45f * pulse), band * 0.6f)
            arc(c, rx, ry, start, p.danger.copy(alpha = pulse), band * 1.25f)
        } else {
            arc(c, rx, ry, start, p.wave.copy(alpha = 0.30f), band)
        }
    }
}

private fun DrawScope.arc(c: Offset, rx: Float, ry: Float, start: Float, color: Color, width: Float) =
    drawArc(color, start, RoadMotion.SWEEP_DEG, false, Offset(c.x - rx, c.y - ry), Size(rx * 2, ry * 2), style = Stroke(width, cap = StrokeCap.Butt))

/** 뒤에서 본 내 차(3/4 높이). 차체는 정적 다크 토큰, 윤곽은 하늘색 림 라이트. */
private fun DrawScope.drawEgoCar(g: RoadGeometry, p: RoadPalette) {
    val cx = g.cx; val gy = g.groundY; val w = g.carW; val h = g.carH; val top = gy - h
    val shadowC = Offset(cx, gy)
    scale(1f, 0.28f, pivot = shadowC) {
        drawCircle(Brush.radialGradient(0f to p.carBody.copy(alpha = 0.55f), 1f to Color.Transparent, center = shadowC, radius = w * 0.78f), w * 0.78f, shadowC)
    }
    // 바퀴·사이드미러
    drawRoundRect(p.carBody, Offset(cx - w * 0.49f, gy - h * 0.22f), Size(w * 0.17f, h * 0.24f), CornerRadius(w * 0.03f))
    drawRoundRect(p.carBody, Offset(cx + w * 0.32f, gy - h * 0.22f), Size(w * 0.17f, h * 0.24f), CornerRadius(w * 0.03f))
    for (side in floatArrayOf(-1f, 1f)) drawOval(p.carBody, Offset(cx + side * w * 0.56f - w * 0.06f, gy - h * 0.70f), Size(w * 0.12f, h * 0.08f))

    val body = Path().apply {
        moveTo(cx - w * 0.50f, gy - h * 0.14f)
        lineTo(cx - w * 0.53f, gy - h * 0.50f)
        quadraticBezierTo(cx - w * 0.53f, gy - h * 0.62f, cx - w * 0.41f, gy - h * 0.66f)
        lineTo(cx - w * 0.32f, top + h * 0.07f)
        quadraticBezierTo(cx, top - h * 0.03f, cx + w * 0.32f, top + h * 0.07f)
        lineTo(cx + w * 0.41f, gy - h * 0.66f)
        quadraticBezierTo(cx + w * 0.53f, gy - h * 0.62f, cx + w * 0.53f, gy - h * 0.50f)
        lineTo(cx + w * 0.50f, gy - h * 0.14f)
        quadraticBezierTo(cx, gy - h * 0.06f, cx - w * 0.50f, gy - h * 0.14f)
        close()
    }
    drawPath(body, Brush.verticalGradient(0f to lerp(p.carBody, p.carLight, 0.22f), 0.55f to p.carBody, 1f to p.carBody, startY = top, endY = gy))
    drawPath(body, p.wave.copy(alpha = 0.55f), style = Stroke(w * 0.012f))

    val glass = Path().apply {
        moveTo(cx - w * 0.28f, top + h * 0.11f)
        quadraticBezierTo(cx, top + h * 0.03f, cx + w * 0.28f, top + h * 0.11f)
        lineTo(cx + w * 0.37f, gy - h * 0.64f)
        quadraticBezierTo(cx, gy - h * 0.60f, cx - w * 0.37f, gy - h * 0.64f)
        close()
    }
    drawPath(glass, Brush.verticalGradient(0f to p.glass, 1f to lerp(p.glass, p.wave, 0.35f), startY = top, endY = gy - h * 0.62f))
    drawLine(p.carLight.copy(alpha = 0.18f), Offset(cx - w * 0.18f, top + h * 0.12f), Offset(cx - w * 0.26f, gy - h * 0.64f), w * 0.03f)
    drawLine(p.carLight.copy(alpha = 0.16f), Offset(cx - w * 0.44f, gy - h * 0.52f), Offset(cx + w * 0.44f, gy - h * 0.52f), w * 0.008f)

    // 후미등: 번짐 + 본체 + 밝은 심
    for (left in booleanArrayOf(true, false)) {
        val tl = Offset(if (left) cx - w * 0.48f else cx + w * 0.20f, gy - h * 0.47f)
        val sz = Size(w * 0.28f, h * 0.07f)
        drawRoundRect(p.danger.copy(alpha = 0.25f), tl - Offset(w * 0.03f, h * 0.04f), Size(sz.width + w * 0.06f, sz.height + h * 0.08f), CornerRadius(h * 0.06f))
        drawRoundRect(p.danger, tl, sz, CornerRadius(h * 0.035f))
        drawRoundRect(lerp(p.danger, p.carLight, 0.55f), tl + Offset(w * 0.02f, sz.height * 0.30f), Size(sz.width - w * 0.04f, sz.height * 0.30f), CornerRadius(h * 0.02f))
    }
    drawRoundRect(lerp(p.carBody, p.carLight, 0.06f), Offset(cx - w * 0.45f, gy - h * 0.30f), Size(w * 0.90f, h * 0.12f), CornerRadius(h * 0.05f))
    drawRoundRect(p.carLight.copy(alpha = 0.14f), Offset(cx - w * 0.12f, gy - h * 0.40f), Size(w * 0.24f, h * 0.08f), CornerRadius(h * 0.015f))
}
