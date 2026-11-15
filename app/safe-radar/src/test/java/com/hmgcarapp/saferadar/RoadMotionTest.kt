package com.hmgcarapp.saferadar

import androidx.compose.ui.geometry.Offset
import com.hmgcarapp.saferadar.domain.Direction
import com.hmgcarapp.saferadar.ui.GroundCamera
import com.hmgcarapp.saferadar.ui.RoadMotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/** 주행 화면: 위험 그라디언트가 경고 문구와 같은 방향을 가리키는지, 바닥 투영이 도로·차 배치와 일치하는지. */
class RoadMotionTest {
    @Test fun everyDirectionExceptNearHasItsOwnBearing() {
        val bearings = Direction.entries.mapNotNull { RoadMotion.bearingDeg(it) }
        assertEquals(Direction.entries.size - 1, bearings.toSet().size)
        assertNull(RoadMotion.bearingDeg(Direction.NEAR))
    }

    @Test fun bearingPointsWhereTheLabelSays() {
        Direction.entries.filter { it != Direction.NEAR }.forEach { d ->
            val a = Math.toRadians(RoadMotion.bearingDeg(d)!!.toDouble())
            val x = cos(a); val z = sin(a) // 바닥: x 오른쪽, z 앞
            if ("왼쪽" in d.label) assertTrue(d.label + " 은 왼쪽", x < -0.1)
            if ("오른쪽" in d.label) assertTrue(d.label + " 은 오른쪽", x > 0.1)
            if ("앞" in d.label) assertTrue(d.label + " 은 앞", z > 0.1)
            if ("뒤" in d.label) assertTrue(d.label + " 은 뒤", z < -0.1)
        }
    }

    @Test fun hazardGlowFadesSmoothlyAwayFromItsDirection() {
        assertEquals(1f, RoadMotion.hazardFalloff(0f), 1e-6f)
        assertEquals(0f, RoadMotion.hazardFalloff(RoadMotion.HAZARD_SPREAD_DEG), 1e-6f)
        assertEquals(0f, RoadMotion.hazardFalloff(180f), 1e-6f)
        val samples = (0..70 step 10).map { RoadMotion.hazardFalloff(it.toFloat()) }
        assertTrue("중심에서 멀어질수록 어두워진다", samples.zipWithNext().all { (a, b) -> b <= a })
        assertEquals("좌우 대칭", RoadMotion.hazardFalloff(30f), RoadMotion.hazardFalloff(-30f), 1e-6f)
    }

    @Test fun shortestTurnWrapsAround() {
        assertEquals(-90f, RoadMotion.shortestDelta(135f, 45f), 1e-4f)
        assertEquals(90f, RoadMotion.shortestDelta(135f, -135f), 1e-4f) // 왼쪽 앞 -> 왼쪽 뒤는 180°를 넘지 않고 돈다
        assertEquals(0f, RoadMotion.shortestDelta(-90f, 270f), 1e-4f)
    }

    @Test fun groundMatrixMatchesCameraFormulaAndCarPlacement() {
        val cam = GroundCamera(1700f, 1000f)
        val m = cam.matrix()
        listOf(Offset(1.75f, 0f), Offset(-5.25f, 20f), Offset(3f, 120f)).forEach { g ->
            val s = m.map(g)
            assertEquals(cam.x(g.x, g.y), s.x, 0.5f)
            assertEquals(cam.y(g.y), s.y, 0.5f)
        }
        assertEquals("뒤 범퍼는 화면 80% 높이", cam.groundY, cam.y(0f), 0.5f)
        assertEquals("차 폭이 정한 픽셀 폭", cam.carPx, cam.x(RoadMotion.CAR_W / 2, 0f) - cam.x(-RoadMotion.CAR_W / 2, 0f), 0.5f)
        assertTrue("가장 가까운 바닥은 화면 아래 밖", cam.y(cam.zNear) > cam.h)
        assertTrue("카메라 앞쪽만 그린다", cam.zNear + cam.dc > 0f)
    }

    @Test fun steeringCurveIsBounded() {
        assertEquals(0f, RoadMotion.curveFor(null))
        assertEquals(1f, RoadMotion.curveFor(720f))
        assertEquals(-1f, RoadMotion.curveFor(-720f))
    }

    /** 뒤에서 본 차체가 바로 앞 바닥을 가리므로, 앞쪽 경고 보조 빛은 차 이미지 윗변 너머에 있어야 보인다. */
    @Test fun frontHazardGlowReachesPastTheCarRoof() {
        val cam = GroundCamera(2400f, 1000f)
        val carTop = cam.y(RoadMotion.Z_CONTACT) - cam.carImageWidthPx() * EGO_IMAGE_ASPECT
        val hiddenUntilZ = cam.zAt(carTop)
        assertTrue("앞쪽 빛 중심(${RoadMotion.AHEAD_GLOW_Z}m)이 차에 가려지는 거리(${hiddenUntilZ}m) 너머여야 한다", RoadMotion.AHEAD_GLOW_Z > hiddenUntilZ)
        assertEquals("정면이면 가장 밝다", 1f, RoadMotion.aheadWeight(90f), 1e-4f)
        assertEquals("옆 경고에는 앞쪽 빛이 없다", 0f, RoadMotion.aheadWeight(0f), 1e-4f)
        assertEquals("뒤 경고에는 앞쪽 빛이 없다", 0f, RoadMotion.aheadWeight(-90f), 1e-4f)
        assertTrue("왼쪽 앞이면 왼쪽으로 비킨다", RoadMotion.aheadShiftX(135f) < -1f)
        assertTrue("옆으로는 내 차로 근처까지만 비킨다(화면을 가로지르지 않음)", kotlin.math.abs(RoadMotion.aheadShiftX(45f)) <= RoadMotion.LANE_W * 2)
    }

    @Test fun groundDrawingStaysInFrontOfTheCamera() {
        val cam = GroundCamera(2400f, 1000f)
        // 파장은 원 전체를 그린다(위험 빛은 zNear 로 잘라 그린다): 가장 큰 파장의 뒤쪽 끝도 카메라 앞이어야 한다
        val farthestBehind = RoadMotion.CAR_CENTER.y - RoadMotion.RIPPLE_MAX_M
        assertTrue("원근 투영은 카메라 뒤(w<=0)를 그릴 수 없다", farthestBehind + cam.dc > 0.5f)
    }

    private companion object { const val EGO_IMAGE_ASPECT = 618f / 720f } // res/drawable-nodpi/ego_car.png
}
