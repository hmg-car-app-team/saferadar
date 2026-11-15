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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.hmgcarapp.pleos.designsystem.theme.PleosTheme
import com.hmgcarapp.saferadar.R
import com.hmgcarapp.saferadar.domain.Direction
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 주행 화면 배경(읽기 전용). 하늘색 그라디언트 하늘 아래, 바닥 평면 위에 도로·파장·위험 방향 그라디언트를 그리고
 * 그 위에 내 차(아이오닉 5 렌더 이미지, res/drawable-nodpi/ego_car.png)를 올린다.
 *
 * 바닥 위 요소는 모두 미터 단위 바닥 좌표(x = 오른쪽, z = 앞)에서 그린 뒤 원근 행렬 하나([GroundCamera.matrix])로
 * 화면에 투영한다. 그래서 차선·파장·위험 그라디언트가 같은 소실점, 같은 각도로 맞물린다.
 *
 * 주변 차량은 서버가 Geohash7 셀(약 150m)과 8방향으로만 알려 주므로 다른 차의 모델이나 차로 위 위치는 그리지 않는다.
 * 위험 차량이 있는 방향으로 내 차에서 빨간 빛이 번지고, 각도·거리 모두 부드럽게 옅어진다([Direction.NEAR] 면 둘레 전체).
 * 색은 Pleos 토큰에서만 만든다(하늘색 = regulation_blue 와 흰색을 섞은 값).
 */
@Composable
fun RoadScene(speedKmh: Float?, steerDeg: Float?, alertDirection: Direction?, modifier: Modifier = Modifier) {
    val pal = rememberRoadPalette()
    val car = ImageBitmap.imageResource(R.drawable.ego_car)
    val loop = rememberInfiniteTransition(label = "road")
    val ripple by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(RoadMotion.RIPPLE_MS, easing = LinearEasing)), label = "ripple")
    val pulse by loop.animateFloat(0.72f, 1f, infiniteRepeatable(tween(RoadMotion.PULSE_MS), RepeatMode.Reverse), label = "pulse")
    val curve by animateFloatAsState(RoadMotion.curveFor(steerDeg), tween(RoadMotion.CURVE_MS), label = "curve")

    // 위험 방향: 바뀌면 짧은 쪽으로 돌아가고, 경고가 끝나면 그 자리에서 서서히 꺼진다
    val bearing = alertDirection?.let(RoadMotion::bearingDeg)
    var aim by remember { mutableFloatStateOf(90f) }
    LaunchedEffect(bearing) { if (bearing != null) aim += RoadMotion.shortestDelta(aim, bearing) }
    val angle by animateFloatAsState(aim, tween(RoadMotion.TURN_MS), label = "aim")
    val intensity by animateFloatAsState(if (alertDirection != null) 1f else 0f, tween(RoadMotion.FADE_MS), label = "alert")
    val nearMix by animateFloatAsState(if (alertDirection == Direction.NEAR) 1f else 0f, tween(RoadMotion.FADE_MS), label = "near")

    // 차선 점선 이동량(m): 실제 속도로 누적, 정차하면 멈춘다
    val speed by rememberUpdatedState(speedKmh ?: 0f)
    var travelM by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) travelM = (travelM + speed / 3.6f * ((now - last) / 1_000_000_000f)) % RoadMotion.DASH_CYCLE_M
                last = now
            }
        }
    }

    Canvas(modifier) {
        val cam = GroundCamera(size.width, size.height)
        drawSky(pal)
        withTransform({
            transform(cam.matrix())
            clipRect(-RoadMotion.CLIP_HALF_W, cam.zNear, RoadMotion.CLIP_HALF_W, RoadMotion.Z_FAR)
        }) {
            drawRoad(cam, pal, curve * RoadMotion.CURVE_K, travelM)
            drawGroundLight(pal)
            drawRipples(pal, ripple)
            if (intensity > 0.01f) {
                drawHazard(pal, angle, intensity * pulse, nearMix, cam.zNear)
                drawAheadGlow(pal, angle, intensity * pulse * (1f - nearMix))
            }
            drawCarShadow(pal)
        }
        drawEgoCar(cam, car)
    }
}

/** 순수 값: 바닥 치수, 방향 -> 바닥 각도, 위험 그라디언트 감쇠, 조향 -> 도로 휨. */
internal object RoadMotion {
    const val RIPPLE_MS = 3_000
    const val PULSE_MS = 900
    const val CURVE_MS = 600
    const val TURN_MS = 450
    const val FADE_MS = 400

    /** 바닥 치수(m). 차로 폭은 국내 고속도로 기준, 차는 아이오닉 5(전폭 1,890mm, 전장 4,635mm). */
    const val LANE_W = 3.5f
    const val CAR_W = 1.89f
    const val CAR_L = 4.64f
    /** 차 이미지의 바퀴 접지선 위치(뒤 범퍼에서 앞으로, m). */
    const val Z_CONTACT = 0.6f
    const val Z_FAR = 220f
    const val CLIP_HALF_W = 80f
    const val DASH_M = 6f
    const val GAP_M = 9f
    const val DASH_CYCLE_M = DASH_M + GAP_M
    /** 조향 최대일 때 휨 정도: x = k·z² (200m 앞에서 약 32m 옆). */
    const val CURVE_K = 0.0008f
    /** 운전대를 이만큼 돌리면 도로가 최대로 휜다(VHAL 관례: 오른쪽이 +). */
    const val STEER_FULL_DEG = 90f

    /** 위험 그라디언트: 중심 방향에서 이 각도만큼 벌어지면 0. */
    const val HAZARD_SPREAD_DEG = 70f
    const val HAZARD_RADIUS_M = 11f
    /** 거리 마스크: 반경의 이 비율에서 가장 밝고, [HAZARD_HALF_AT] 에서 절반, 끝에서 0. */
    const val HAZARD_PEAK_AT = 0.3f
    const val HAZARD_HALF_AT = 0.62f

    /**
     * 앞쪽 경고 보조 빛. 차 바로 앞 바닥은 뒤에서 본 차체에 가려지므로, 앞쪽 계열 경고는 차 지붕 너머 [AHEAD_GLOW_Z] m 에
     * 앞뒤로 긴 빛을 하나 더 둔다(옆으로는 [AHEAD_GLOW_SHIFT_X] m 까지만 비켜 화면을 가로지르지 않는다).
     */
    const val AHEAD_GLOW_Z = 40f
    const val AHEAD_GLOW_R = 7f
    const val AHEAD_GLOW_STRETCH = 3f
    const val AHEAD_GLOW_SHIFT_X = 6f
    /** 바로 근처일 때 둘레 전체 밝기. */
    const val NEAR_LEVEL = 0.75f
    const val SWEEP_STEPS = 72
    const val RIPPLE_COUNT = 3
    const val RIPPLE_MIN_M = 2.8f
    /** 파장의 가장 큰 반경(m). 원 전체를 그리므로 뒤쪽 끝이 카메라 앞에 있어야 한다(테스트로 고정). */
    const val RIPPLE_MAX_M = 12f

    val CAR_CENTER = Offset(0f, CAR_L / 2f)

    fun curveFor(steerDeg: Float?): Float = ((steerDeg ?: 0f) / STEER_FULL_DEG).coerceIn(-1f, 1f)

    /** 바닥 각도(도): +x(오른쪽) 기준, +z(앞) 쪽으로 증가. [Direction.NEAR] 는 특정 방향이 없다. */
    fun bearingDeg(d: Direction): Float? = when (d) {
        Direction.FRONT -> 90f
        Direction.FRONT_RIGHT -> 45f
        Direction.RIGHT -> 0f
        Direction.REAR_RIGHT -> -45f
        Direction.REAR -> -90f
        Direction.REAR_LEFT -> -135f
        Direction.LEFT -> 180f
        Direction.FRONT_LEFT -> 135f
        Direction.NEAR -> null
    }

    /** from 에서 to 로 가는 가장 짧은 회전(도, -180..180). */
    fun shortestDelta(from: Float, to: Float): Float = (((to - from) % 360f + 540f) % 360f) - 180f

    /** 앞쪽 보조 빛 세기(0..1)와 옆 위치(m): 정면 1, 왼쪽·오른쪽 앞 0.7, 옆·뒤 0. */
    fun aheadWeight(angleDeg: Float): Float = sin(Math.toRadians(angleDeg.toDouble())).toFloat().coerceAtLeast(0f)
    fun aheadShiftX(angleDeg: Float): Float = AHEAD_GLOW_SHIFT_X * cos(Math.toRadians(angleDeg.toDouble())).toFloat()

    /** 위험 중심 방향과 [deltaDeg] 만큼 떨어진 곳의 밝기(1..0). 끝에서 기울기가 0 이라 경계선이 생기지 않는다. */
    fun hazardFalloff(deltaDeg: Float): Float {
        val t = abs(deltaDeg) / HAZARD_SPREAD_DEG
        if (t >= 1f) return 0f
        val c = cos(t * PI / 2).toFloat()
        return c * c
    }
}

/**
 * 바닥 평면 -> 화면 원근 투영(핀홀 카메라). 카메라는 내 차 뒤 [dc] m, 높이 [hc] m 에서 앞을 본다.
 * 화면 비율로 정한다: 지평선 30%, 뒤 범퍼 80%, 차 폭은 화면 폭의 17%(높이의 30% 이내).
 */
internal class GroundCamera(w: Float, val h: Float) {
    val horizonY = h * 0.30f
    val groundY = h * 0.80f
    val carPx = min(w * 0.20f, h * 0.40f)
    val dc = 12f
    val f = carPx / RoadMotion.CAR_W * dc
    val hc = (groundY - horizonY) * dc / f
    val cx = w / 2f
    /** 화면 맨 아래보다 조금 더 가까운 바닥(z). */
    val zNear = f * hc / (h - horizonY) - dc - 0.5f

    fun x(gx: Float, gz: Float): Float = cx + f * gx / (gz + dc)
    fun y(gz: Float): Float = horizonY + f * hc / (gz + dc)
    /** 화면 y 에 보이는 바닥 거리(z). [y] 의 역함수. */
    fun zAt(screenY: Float): Float = f * hc / (screenY - horizonY) - dc
    /** 내 차 이미지의 화면 폭(뒤 범퍼 부근 기준). */
    fun carImageWidthPx(): Float = f * RoadMotion.CAR_W / (0.3f + dc)

    /** (x, z) -> 화면. u = cx + f·x/(z+dc), v = horizonY + f·hc/(z+dc) 를 3x3 원근 행렬로 쓴 것. */
    fun matrix(): Matrix = Matrix().apply {
        values[Matrix.ScaleX] = f
        values[Matrix.SkewX] = cx
        values[Matrix.TranslateX] = cx * dc
        values[Matrix.SkewY] = 0f
        values[Matrix.ScaleY] = horizonY
        values[Matrix.TranslateY] = horizonY * dc + f * hc
        values[Matrix.Perspective0] = 0f
        values[Matrix.Perspective1] = 1f
        values[Matrix.Perspective2] = dc
    }
}

@Immutable
private data class RoadPalette(
    val skyTop: Color, val skyMid: Color, val skyBottom: Color,
    val content: Color, val wave: Color, val danger: Color,
    val carBody: Color, val carLight: Color,
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
        )
    }
}

private fun DrawScope.drawSky(p: RoadPalette) =
    drawRect(Brush.verticalGradient(0f to p.skyTop, 0.45f to p.skyMid, 1f to p.skyBottom))

// ---------- 아래 draw 함수들은 바닥 좌표(m)에서 그린다(drawEgoCar 제외) ----------

/** 멀어질수록 옅어지는 붓(z 기준). */
private fun fade(color: Color, alpha: Float, zNear: Float) = Brush.verticalGradient(
    0f to color.copy(alpha = alpha), 0.12f to color.copy(alpha = alpha * 0.8f), 0.45f to color.copy(alpha = alpha * 0.25f), 1f to color.copy(alpha = 0f),
    startY = zNear, endY = RoadMotion.Z_FAR,
)

/** x = x0 + k·z² 포물선을 2차 베지어 하나로 정확히 그린다(두 끝 접선의 교점이 제어점). */
private fun Path.parabola(x0: Float, k: Float, z0: Float, z1: Float, move: Boolean) {
    if (move) moveTo(x0 + k * z0 * z0, z0) else lineTo(x0 + k * z0 * z0, z0)
    quadraticBezierTo(x0 + k * z0 * z1, (z0 + z1) / 2f, x0 + k * z1 * z1, z1)
}

private fun lane(x0: Float, k: Float, z0: Float): Path = Path().apply { parabola(x0, k, z0, RoadMotion.Z_FAR, move = true) }

private fun band(xl: Float, xr: Float, k: Float, z0: Float): Path = Path().apply {
    parabola(xl, k, z0, RoadMotion.Z_FAR, move = true)
    parabola(xr, k, RoadMotion.Z_FAR, z0, move = false)
    close()
}

private fun DrawScope.drawRoad(cam: GroundCamera, p: RoadPalette, k: Float, travelM: Float) {
    val z0 = cam.zNear
    val edge = RoadMotion.LANE_W * 1.5f
    drawPath(band(-edge - 0.6f, edge + 0.6f, k, z0), fade(p.content, 0.10f, z0))
    // 내 차로: 하늘색 주행 카펫
    drawPath(
        band(-RoadMotion.LANE_W / 2, RoadMotion.LANE_W / 2, k, z0),
        Brush.verticalGradient(
            0f to p.wave.copy(alpha = 0.34f), 0.08f to p.wave.copy(alpha = 0.22f), 0.3f to p.wave.copy(alpha = 0.06f), 1f to p.wave.copy(alpha = 0f),
            startY = z0, endY = RoadMotion.Z_FAR,
        ),
    )
    val width = 0.09f
    for (x in floatArrayOf(-edge, edge)) drawPath(lane(x, k, z0), fade(p.content, 0.45f, z0), style = Stroke(width))
    val dashes = PathEffect.dashPathEffect(floatArrayOf(RoadMotion.DASH_M, RoadMotion.GAP_M), travelM)
    for (x in floatArrayOf(-RoadMotion.LANE_W / 2, RoadMotion.LANE_W / 2)) drawPath(lane(x, k, z0), fade(p.content, 0.70f, z0), style = Stroke(width, pathEffect = dashes))
}

/** 차 앞쪽으로 번지는 빛(레퍼런스의 흰 광륜), 차 밑 하늘색 바닥광, 후미등이 도로에 비치는 붉은 반사. */
private fun DrawScope.drawGroundLight(p: RoadPalette) {
    val ahead = Offset(0f, 11f)
    drawCircle(Brush.radialGradient(0f to p.carLight.copy(alpha = 0.32f), 0.5f to p.wave.copy(alpha = 0.12f), 1f to Color.Transparent, center = ahead, radius = 11f), 11f, ahead)
    val c = RoadMotion.CAR_CENTER
    drawCircle(Brush.radialGradient(0f to p.wave.copy(alpha = 0.42f), 1f to Color.Transparent, center = c, radius = 4.6f), 4.6f, c)
    val tail = Offset(0f, -0.9f)
    scale(1.35f, 1f, pivot = tail) {
        drawCircle(Brush.radialGradient(0f to p.danger.copy(alpha = 0.30f), 1f to Color.Transparent, center = tail, radius = 1.8f), 1.8f, tail)
    }
}

/** 내 차에서 바닥을 따라 퍼지는 하늘색 파장: 바닥 위 원이라 원근이 도로와 정확히 맞는다. */
private fun DrawScope.drawRipples(p: RoadPalette, progress: Float) {
    val c = RoadMotion.CAR_CENTER
    repeat(RoadMotion.RIPPLE_COUNT) { i ->
        val t = (progress + i.toFloat() / RoadMotion.RIPPLE_COUNT) % 1f
        val fadeOut = (1f - t) * (1f - t)
        drawCircle(p.wave.copy(alpha = 0.60f * fadeOut), RoadMotion.RIPPLE_MIN_M + (RoadMotion.RIPPLE_MAX_M - RoadMotion.RIPPLE_MIN_M) * t, c, style = Stroke(0.08f + 0.22f * (1f - t)))
    }
}

/**
 * 위험 방향 그라디언트. 각도 방향은 부채꼴 그라디언트(중심 방향 1 -> 양옆 0), 거리 방향은 원형 마스크(차 바로 둘레가
 * 가장 밝고 반경 끝에서 0)로 곱해, 내 차에서 위험 쪽으로 빛이 번지는 모양이 된다.
 * 앞쪽 반경은 카메라 뒤까지 닿을 만큼 크므로, 원 대신 보이는 바닥([zNear] 앞쪽)으로 자른 사각형에 칠한다.
 */
private fun DrawScope.drawHazard(p: RoadPalette, angleDeg: Float, strength: Float, nearMix: Float, zNear: Float) {
    val c = RoadMotion.CAR_CENTER
    val r = RoadMotion.HAZARD_RADIUS_M
    val steps = RoadMotion.SWEEP_STEPS
    val stops = Array(steps + 1) { i ->
        val a = i * 360f / steps
        val directional = RoadMotion.hazardFalloff(RoadMotion.shortestDelta(angleDeg, a))
        val alpha = (directional + (RoadMotion.NEAR_LEVEL - directional) * nearMix) * strength
        (i.toFloat() / steps) to p.danger.copy(alpha = alpha.coerceIn(0f, 1f))
    }
    val area = Rect(c.x - r, maxOf(c.y - r, zNear), c.x + r, c.y + r)
    drawIntoCanvas { it.saveLayer(area, Paint()) }
    drawRect(Brush.sweepGradient(*stops, center = c), area.topLeft, area.size)
    drawRect(
        Brush.radialGradient(
            0f to p.danger.copy(alpha = 0f), RoadMotion.HAZARD_PEAK_AT * 0.66f to p.danger.copy(alpha = 0f), RoadMotion.HAZARD_PEAK_AT to p.danger,
            RoadMotion.HAZARD_HALF_AT to p.danger.copy(alpha = 0.5f), 1f to p.danger.copy(alpha = 0f),
            center = c, radius = r,
        ),
        area.topLeft, area.size, blendMode = BlendMode.DstIn,
    )
    drawIntoCanvas { it.restore() }
}

/** 앞쪽 경고 보조 빛: 차 지붕 너머 바닥에 앞뒤로 긴 빨간 빛. */
private fun DrawScope.drawAheadGlow(p: RoadPalette, angleDeg: Float, strength: Float) {
    val w = RoadMotion.aheadWeight(angleDeg) * strength
    if (w < 0.01f) return
    val c = Offset(RoadMotion.aheadShiftX(angleDeg), RoadMotion.AHEAD_GLOW_Z)
    val r = RoadMotion.AHEAD_GLOW_R
    scale(1f, RoadMotion.AHEAD_GLOW_STRETCH, pivot = c) {
        drawCircle(Brush.radialGradient(0f to p.danger.copy(alpha = 0.85f * w), 0.45f to p.danger.copy(alpha = 0.40f * w), 1f to Color.Transparent, center = c, radius = r), r, c)
    }
}

/** 차 밑 접지 그림자(차 길이 방향으로 긴 타원). */
private fun DrawScope.drawCarShadow(p: RoadPalette) {
    val c = RoadMotion.CAR_CENTER
    scale(0.55f, 1f, pivot = c) {
        drawCircle(Brush.radialGradient(0f to p.carBody.copy(alpha = 0.60f), 0.7f to p.carBody.copy(alpha = 0.25f), 1f to Color.Transparent, center = c, radius = 3.1f), 3.1f, c)
    }
}

/** 내 차 렌더 이미지: 같은 카메라로 계산한 폭·접지선에 맞춰 화면 좌표에 놓는다. */
private fun DrawScope.drawEgoCar(cam: GroundCamera, img: ImageBitmap) {
    val wPx = cam.carImageWidthPx()
    val hPx = wPx * img.height / img.width
    val bottom = cam.y(RoadMotion.Z_CONTACT)
    drawImage(
        img,
        srcOffset = IntOffset.Zero, srcSize = IntSize(img.width, img.height),
        dstOffset = IntOffset((cam.cx - wPx / 2f).roundToInt(), (bottom - hPx).roundToInt()),
        dstSize = IntSize(wPx.roundToInt(), hPx.roundToInt()),
        filterQuality = FilterQuality.High,
    )
}
