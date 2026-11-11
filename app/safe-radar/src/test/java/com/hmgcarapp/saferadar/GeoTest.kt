package com.hmgcarapp.saferadar

import com.hmgcarapp.saferadar.domain.Direction
import com.hmgcarapp.saferadar.domain.DirectionCalc
import com.hmgcarapp.saferadar.domain.GeoMath
import com.hmgcarapp.saferadar.domain.Geohash
import com.hmgcarapp.saferadar.domain.HeadingTracker
import com.hmgcarapp.saferadar.domain.NearbyMapper
import com.hmgcarapp.saferadar.domain.NearbyVehicle
import com.hmgcarapp.saferadar.domain.RiskGrade
import com.hmgcarapp.saferadar.domain.RiskKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoTest {
    // ---------------- Geohash: 서버(Python) 테스트와 같은 표준 벡터 ----------------
    @Test fun geohashKnownVectors() {
        assertEquals("ezs42", Geohash.encode(42.605, -5.603, 5))
        assertEquals("u4pruydqqvj", Geohash.encode(57.64911, 10.40744, 11))
        assertEquals("wydm6ds", Geohash.encode(37.5, 127.03, 7))
        assertEquals("s000000", Geohash.encode(0.0, 0.0, 7))
        assertEquals(7, Geohash.SEND_PRECISION)
    }

    @Test fun geohashDecodeCellSizeAndContainment() {
        val c = Geohash.decode("wydm6ds")!!
        assertTrue(c.latMin <= 37.5 && 37.5 < c.latMax && c.lonMin <= 127.03 && 127.03 < c.lonMax)
        assertEquals(180.0 / (1 shl 17), c.latMax - c.latMin, 1e-12) // 7자리 = 위도 17비트
        assertEquals(360.0 / (1 shl 18), c.lonMax - c.lonMin, 1e-12) // 경도 18비트
        assertEquals(37.49908447265625, c.latMin, 1e-12)
        assertEquals(127.03079223632812, c.lonMax, 1e-12)
    }

    @Test fun geohashValidation() {
        listOf("", "wydm6d", "wydm6dsa", "wydm6d!", "WYDM6DS", "wydm6da").forEach { assertFalse(it, Geohash.isValid(it)) }
        assertTrue(Geohash.isValid("wydm6ds"))
        assertNull(Geohash.decode("wydm6da"))
        assertNull(Geohash.decode(""))
        try { Geohash.encode(91.0, 0.0); throw AssertionError("범위 밖 좌표는 거부해야 한다") } catch (e: IllegalArgumentException) { }
    }

    // ---------------- 방위·방향 ----------------
    @Test fun headingSectorBoundaries() {
        assertEquals(0, GeoMath.headingSector(0.0))
        assertEquals(0, GeoMath.headingSector(22.4))
        assertEquals(1, GeoMath.headingSector(22.5))
        assertEquals(1, GeoMath.headingSector(45.0))
        assertEquals(2, GeoMath.headingSector(90.0))
        assertEquals(4, GeoMath.headingSector(180.0))
        assertEquals(7, GeoMath.headingSector(337.4))
        assertEquals(0, GeoMath.headingSector(337.5))
        assertEquals(0, GeoMath.headingSector(359.0))
        assertEquals(6, GeoMath.headingSector(-90.0))
        assertEquals(270.0, GeoMath.norm360(-90.0), 1e-9)
    }

    @Test fun sameSectorIncludesAdjacentWrapAround() {
        assertTrue(GeoMath.sameSector(0, 0)); assertTrue(GeoMath.sameSector(0, 1)); assertTrue(GeoMath.sameSector(0, 7)); assertTrue(GeoMath.sameSector(7, 0))
        assertFalse(GeoMath.sameSector(0, 2)); assertFalse(GeoMath.sameSector(0, 4)); assertFalse(GeoMath.sameSector(1, 6))
    }

    @Test fun relativeDirectionTable() {
        val cases = listOf(
            Triple(-110.0, -55.0, Direction.REAR_LEFT), Triple(90.0, 0.0, Direction.FRONT), Triple(0.0, 50.0, Direction.RIGHT), Triple(0.0, -50.0, Direction.LEFT),
            Triple(50.0, 50.0, Direction.FRONT_RIGHT), Triple(50.0, -50.0, Direction.FRONT_LEFT), Triple(-50.0, 45.0, Direction.REAR_RIGHT), Triple(-100.0, 0.0, Direction.REAR),
            Triple(20.0, 20.0, Direction.NEAR),
            // 경계: 40m 이상부터 구분
            Triple(40.0, 0.0, Direction.FRONT), Triple(39.9, 0.0, Direction.NEAR), Triple(-40.0, 0.0, Direction.REAR), Triple(0.0, -40.0, Direction.LEFT),
            Triple(0.0, 39.9, Direction.NEAR), Triple(-39.9, -39.9, Direction.NEAR), Triple(100.0, -39.9, Direction.FRONT), Triple(39.9, 100.0, Direction.RIGHT),
        )
        cases.forEach { (a, l, d) -> assertEquals("along=$a lateral=$l", d, DirectionCalc.relative(a, l)) }
    }

    @Test fun alongLateralByHeading() {
        val m = 111_320.0
        val (a0, l0) = GeoMath.alongLateral(37.5, 127.03, 0.0, 37.5 - 110 / m, 127.03)   // 북향, 110m 남쪽
        assertEquals(-110.0, a0, 0.5); assertEquals(0.0, l0, 0.5)
        val (a1, l1) = GeoMath.alongLateral(37.5, 127.03, 90.0, 37.5 + 100 / m, 127.03)  // 동향, 100m 북쪽 = 왼쪽
        assertEquals(0.0, a1, 0.5); assertEquals(-100.0, l1, 0.5)
        val (a2, l2) = GeoMath.alongLateral(37.5, 127.03, 180.0, 37.5 + 100 / m, 127.03) // 남향, 100m 북쪽 = 뒤
        assertEquals(-100.0, a2, 0.5); assertEquals(0.0, l2, 0.5)
    }

    @Test fun bearingAndHaversineLiterals() {
        assertEquals(0.0, GeoMath.bearingDeg(37.5, 127.03, 37.6, 127.03), 1e-6)
        assertEquals(90.0, GeoMath.bearingDeg(37.5, 127.03, 37.5, 127.1), 1e-6)
        assertEquals(180.0, GeoMath.bearingDeg(37.5, 127.03, 37.4, 127.03), 1e-6)
        assertEquals(270.0, GeoMath.bearingDeg(37.5, 127.03, 37.5, 126.9), 1e-6)
        assertEquals(111_194.9, GeoMath.haversineM(0.0, 0.0, 1.0, 0.0), 5.0)
    }

    // ---------------- 근접 후보(서버가 준 셀 + 내 위치·방향) ----------------
    private fun v(gh: String, hd: Int = 0, g: RiskGrade = RiskGrade.HIGH, k: RiskKind? = RiskKind.LANE_CHANGE) = NearbyVehicle(gh, hd, g, k)
    private val myLat = 37.5; private val myLon = 127.03

    @Test fun mapperDirectionsFromCellCenters() {
        // 셀 중심 기준 상대 위치(미터): wydm6d7(-178.4, -112.0) wydm6du(+127.4, +9.3) wydm6dk(-178.4, +9.3) wydm6ds(-25.5, +9.3)
        fun one(gh: String) = NearbyMapper.candidates(myLat, myLon, 0.0, listOf(v(gh))).single()
        assertEquals(Direction.REAR_LEFT, one("wydm6d7").direction)
        assertEquals(210.6, one("wydm6d7").distanceM, 0.5)
        assertEquals(Direction.FRONT, one("wydm6du").direction)
        assertEquals(127.7, one("wydm6du").distanceM, 0.5)
        assertEquals(Direction.REAR, one("wydm6dk").direction)
        assertEquals(Direction.NEAR, one("wydm6ds").direction)
    }

    @Test fun mapperExcludesFarOppositeAndKeepsGradeKind() {
        assertEquals("셀 중심 거리 484m > 300m", emptyList<Any>(), NearbyMapper.candidates(myLat, myLon, 0.0, listOf(v("wydm69u"))))
        assertEquals("반대 방향(섹터 4) 제외", emptyList<Any>(), NearbyMapper.candidates(myLat, myLon, 0.0, listOf(v("wydm6dk", hd = 4))))
        assertEquals("인접 섹터(1)는 같은 방향", 1, NearbyMapper.candidates(myLat, myLon, 0.0, listOf(v("wydm6dk", hd = 1))).size)
        assertEquals("형식이 잘못된 셀은 버린다", emptyList<Any>(), NearbyMapper.candidates(myLat, myLon, 0.0, listOf(v("wydm6da"))))
        val c = NearbyMapper.candidates(myLat, myLon, 0.0, listOf(v("wydm6dk", g = RiskGrade.MID, k = null))).single()
        assertEquals(RiskGrade.MID, c.grade); assertNull(c.kind)
    }

    @Test fun mapperRespectsMyHeading() {
        // 남향(180°)으로 달리는 내 입장에서 북쪽 셀(wydm6dk 는 남쪽이므로 반대): 남향이면 wydm6dk 는 앞쪽
        val c = NearbyMapper.candidates(myLat, myLon, 180.0, listOf(v("wydm6dk", hd = 4))).single()
        assertEquals(Direction.FRONT, c.direction)
        assertEquals(emptyList<Any>(), NearbyMapper.candidates(myLat, myLon, 180.0, listOf(v("wydm6dk", hd = 0))))
    }

    @Test fun mapperRadiusBoundaryAt300m() {
        val m = 111_320.0
        val inside = Geohash.encode(myLat - 250 / m, myLon)   // 셀 중심 178.4m 남쪽
        val outside = Geohash.encode(myLat - 300 / m, myLon)  // 다음 칸: 셀 중심 331.4m
        val cIn = Geohash.decode(inside)!!; val cOut = Geohash.decode(outside)!!
        assertTrue(GeoMath.haversineM(myLat, myLon, cIn.centerLat, cIn.centerLon) <= 300.0)
        assertTrue(GeoMath.haversineM(myLat, myLon, cOut.centerLat, cOut.centerLon) > 300.0)
        assertEquals(1, NearbyMapper.candidates(myLat, myLon, 0.0, listOf(v(inside))).size)
        assertEquals(0, NearbyMapper.candidates(myLat, myLon, 0.0, listOf(v(outside))).size)
    }

    // ---------------- 진행 방향 추정 ----------------
    @Test fun headingTrackerUsesReportedThenDerivesFromMovement() {
        val h = HeadingTracker()
        assertNull("첫 위치만으로는 방향을 모른다", h.update(37.5, 127.03, null))
        assertEquals(135.0, h.update(37.5, 127.03, 135f)!!, 1e-9)
        assertEquals("보고값이 범위 밖이면 이전 값 유지", 135.0, h.update(37.5, 127.03, 400f)!!, 1e-9)
        assertEquals("NaN 도 무시", 135.0, h.update(37.5, 127.03, Float.NaN)!!, 1e-9)
        val h2 = HeadingTracker()
        h2.update(37.5, 127.03, null)
        assertNull("7m 이동은 방향으로 보지 않는다(<8m)", h2.update(37.5 + 7 / 111_320.0, 127.03, null))
        assertEquals("북쪽으로 20m", 0.0, h2.update(37.5 + 20 / 111_320.0, 127.03, null)!!, 0.5)
    }
}
