package com.hmgcarapp.saferadar.ui

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
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
import androidx.compose.ui.platform.LocalContext
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
 *
 * 시스템에서 애니메이션을 끄면(개발자 옵션 배율 0, 접근성 "애니메이션 삭제") 파장·맥동·점선 흐름·방향 회전을 모두 멈추고
 * 같은 정보를 정지된 그림으로 보여 준다(파장은 고정된 동심원, 위험 빛은 최대 밝기로 바로 켜고 끈다).
 */
@Composable
fun RoadScene(speedKmh: Float?, steerDeg: Float?, alertDirection: Direction?, modifier: Modifier = Modifier) {
    val pal = rememberRoadPalette()
    val car = ImageBitmap.imageResource(R.drawable.ego_car)
    val still = rememberReduceMotion()
    // 반복 애니메이션은 움직임이 허용될 때만 만든다(꺼져 있으면 프레임을 돌리지 않는다)
    var ripple: State<Float>? = null
    var pulse: State<Float>? = null
    if (!still) {
        val loop = rememberInfiniteTransition(label = "road")
        ripple = loop.animateFloat(0f, 1f, infiniteRepeatable(tween(RoadMotion.RIPPLE_MS, easing = LinearEasing)), label = "ripple")
        pulse = loop.animateFloat(0.72f, 1f, infiniteRepeatable(tween(RoadMotion.PULSE_MS), RepeatMode.Reverse), label = "pulse")
    }
    val curve by animateFloatAsState(RoadMotion.curveFor(steerDeg), if (still) snap() else tween(RoadMotion.CURVE_MS), label = "curve")

    // 위험 방향: 켜져 있는 동안 바뀌면 짧은 쪽으로 돌아가고, 꺼져 있다가 새로 켜지면 그 방향에서 바로 켜진다
    // (기본 방향에서 돌아 들어오면 배너와 다른 방향이 잠깐 보인다). 경고가 끝나면 그 자리에서 서서히 꺼진다.
    val bearing = alertDirection?.let(RoadMotion::bearingDeg)
    val intensity by animateFloatAsState(if (alertDirection != null) 1f else 0f, if (still) snap() else tween(RoadMotion.FADE_MS), label = "alert")
    val currentIntensity by rememberUpdatedState(intensity)
    val currentStill by rememberUpdatedState(still)
    val aim = remember { Animatable(90f) }
    LaunchedEffect(bearing) {
        if (bearing == null) return@LaunchedEffect
        val target = aim.value + RoadMotion.shortestDelta(aim.value, bearing)
        if (currentStill || currentIntensity < RoadMotion.SNAP_BELOW) aim.snapTo(target) else aim.animateTo(target, tween(RoadMotion.TURN_MS))
    }
    val nearMix by animateFloatAsState(if (alertDirection == Direction.NEAR) 1f else 0f, if (still) snap() else tween(RoadMotion.FADE_MS), label = "near")

    // 차선 점선 이동량(m): 실제 속도로 누적, 정차하거나 애니메이션이 꺼져 있으면 멈춘다
    val speed by rememberUpdatedState(speedKmh ?: 0f)
    var travelM by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(still) {
        if (still) return@LaunchedEffect
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) travelM = (travelM + speed / 3.6f * ((now - last) / 1_000_000_000f)) % RoadMotion.DASH_CYCLE_M
                last = now
            }
        }
    }

    // 도로 경로·붓은 창 크기나 조향(curve)이 바뀔 때만 다시 만든다. 움직이는 값(파장·점선·위험 빛)은 그리기 단계에서만 읽는다.
    Spacer(
        modifier.drawWithCache {
            val cam = GroundCamera(size.width, size.height)
            val matrix = cam.matrix()
            val road = RoadLayer(cam.zNear, curve * RoadMotion.CURVE_K, pal)
            onDrawBehind {
                drawSky(pal)
                withTransform({
                    transform(matrix)
                    clipRect(-RoadMotion.CLIP_HALF_W, cam.zNear, RoadMotion.CLIP_HALF_W, RoadMotion.Z_FAR)
                }) {
                    drawRoad(road, travelM)
                    drawGroundLight(pal)
                    drawRipples(pal, ripple?.value)
                    if (intensity > 0.01f) {
                        val angle = aim.value
                        val glow = intensity * (pulse?.value ?: 1f)
                        drawHazard(pal, angle, glow, nearMix, cam.zNear)
                        drawAheadGlow(pal, angle, glow * (1f - nearMix))
                    }
                    drawCarShadow(pal)
                }
                drawEgoCar(cam, car)
            }
        },
    )
}

/**
 * 시스템 애니메이션 배율(Settings.Global.ANIMATOR_DURATION_SCALE)이 0 이면 true. 접근성의 "애니메이션 삭제"도 이 값을 0 으로 만든다.
 * 설정이 바뀌면 바로 따라간다.
 */
@Composable
fun rememberReduceMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    fun read() = RoadMotion.isStill(Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f))
    var still by remember { mutableStateOf(read()) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { still = read() }
        }
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return still
}

/** 순수 값: 바닥 치수, 방향 -> 바닥 각도, 위험 그라디언트 감쇠, 조향 -> 도로 휨. */
internal object RoadMotion {
    const val RIPPLE_MS = 3_000
    const val PULSE_MS = 900
    const val CURVE_MS = 600
    const val TURN_MS = 450
    const val FADE_MS = 400
    /** 위험 빛이 이보다 어두우면(꺼져 있음) 새 방향으로 돌지 않고 바로 옮긴다. */
    const val SNAP_BELOW = 0.05f

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
    /** 차선 굵기(m). */
    const val LINE_W_M = 0.09f
    /** 조향 최대일 때 휨 정도: x = k·z² (200m 앞에서 약 32m 옆). */
    const val CURVE_K = 0.0008f
    /**
     * 운전대를 이만큼 돌리면 도로가 최대로 휜다. Pleos Vehicle SDK 조향각은 왼쪽이 +다
     * (Pleos Connect v2.1.0 에뮬레이터에서 +90° 를 넣으면 시스템 주행 화면의 도로가 왼쪽으로 휜다).
     */
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

    /** 화면 도로 휨(-1 왼쪽 .. +1 오른쪽). 조향각은 왼쪽이 + 라 부호를 뒤집는다. */
    fun curveFor(steerDeg: Float?): Float = (0f - (steerDeg ?: 0f) / STEER_FULL_DEG).coerceIn(-1f, 1f)

    /** 애니메이션 배율이 0(또는 잘못된 값)이면 움직임 없이 그린다. */
    fun isStill(animatorScale: Float): Boolean = !(animatorScale > 0f)

    /** 애니메이션이 꺼져 있을 때 파장 대신 그리는 고정 동심원의 위치(0..1, 파장 진행도와 같은 축). */
    val STILL_RIPPLES = floatArrayOf(0.12f, 0.45f, 0.78f)

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
 * 화면 비율로 정한다: 지평선 30%, 뒤 범퍼 80%. 차 폭은 가로 창(2분할·전체)에서 폭의 20%(높이의 40% 이내),
 * 세로 창(3분할 840dp)에서 폭의 30%.
 */
internal class GroundCamera(w: Float, val h: Float) {
    val horizonY = h * 0.30f
    val groundY = h * 0.80f
    val carPx = if (w < h) w * 0.30f else min(w * 0.20f, h * 0.40f)
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

/** 3차로 도로의 경로와 붓(바닥 좌표). 창 크기·조향이 바뀔 때만 만든다. */
private class RoadLayer(z0: Float, k: Float, p: RoadPalette) {
    private val edge = RoadMotion.LANE_W * 1.5f
    private val half = RoadMotion.LANE_W / 2
    val surface = band(-edge - 0.6f, edge + 0.6f, k, z0)
    val surfaceBrush = fade(p.content, 0.10f, z0)
    /** 내 차로: 하늘색 주행 카펫 */
    val carpet = band(-half, half, k, z0)
    val carpetBrush = Brush.verticalGradient(
        0f to p.wave.copy(alpha = 0.34f), 0.08f to p.wave.copy(alpha = 0.22f), 0.3f to p.wave.copy(alpha = 0.06f), 1f to p.wave.copy(alpha = 0f),
        startY = z0, endY = RoadMotion.Z_FAR,
    )
    val edges = listOf(lane(-edge, k, z0), lane(edge, k, z0))
    val edgeBrush = fade(p.content, 0.45f, z0)
    val centers = listOf(lane(-half, k, z0), lane(half, k, z0))
    val centerBrush = fade(p.content, 0.70f, z0)
}

private fun DrawScope.drawRoad(r: RoadLayer, travelM: Float) {
    drawPath(r.surface, r.surfaceBrush)
    drawPath(r.carpet, r.carpetBrush)
    r.edges.forEach { drawPath(it, r.edgeBrush, style = Stroke(RoadMotion.LINE_W_M)) }
    // 내 차로 경계 점선: 실제 속도로 흐른다
    val dashes = PathEffect.dashPathEffect(floatArrayOf(RoadMotion.DASH_M, RoadMotion.GAP_M), travelM)
    r.centers.forEach { drawPath(it, r.centerBrush, style = Stroke(RoadMotion.LINE_W_M, pathEffect = dashes)) }
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
/** [progress] 가 null 이면(애니메이션 꺼짐) 감지 범위를 고정 동심원으로 그린다. */
private fun DrawScope.drawRipples(p: RoadPalette, progress: Float?) {
    val c = RoadMotion.CAR_CENTER
    val phases = if (progress == null) RoadMotion.STILL_RIPPLES else FloatArray(RoadMotion.RIPPLE_COUNT) { i -> (progress + i.toFloat() / RoadMotion.RIPPLE_COUNT) % 1f }
    phases.forEach { t ->
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
