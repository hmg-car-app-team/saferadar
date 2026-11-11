package com.hmgcarapp.saferadar

import com.hmgcarapp.saferadar.domain.RiskEvent
import com.hmgcarapp.saferadar.domain.RiskKind
import com.hmgcarapp.saferadar.domain.TripTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripTrackerTest {
    private fun drive(t: TripTracker, fromS: Int, toS: Int, kmh: Float, driving: Boolean = true) =
        (fromS..toS).mapNotNull { t.onTick(it * 1_000L, driving, kmh, emptyList()) }

    @Test fun startsAtFiveKmh_andIntegratesDistance() {
        val t = TripTracker()
        assertNull(t.onTick(0, true, 4f, emptyList())); assertFalse(t.isActive)
        t.onTick(1_000, true, 5f, emptyList()); assertTrue("5km/h 부터 시작", t.isActive)
        drive(t, 2, 121, 36f) // 36km/h = 10m/s, 120틱 -> 1200m
        assertEquals(1.2, t.liveDistanceKm, 0.001)
        assertEquals("120초 = 2분", 2, t.liveMinutes)
        assertEquals(100, t.liveScore())
    }

    @Test fun endsAfter30sParked_withSummary() {
        val t = TripTracker()
        t.onTick(0, true, 36f, emptyList())
        drive(t, 1, 120, 36f)
        val parked = (121..151).mapNotNull { t.onTick(it * 1_000L, false, 0f, emptyList()) }
        assertEquals("정차 시작(121초) 30초 뒤인 151초에 정확히 한 번", 1, parked.size)
        val s = parked.single()
        assertEquals(1.2, s.distanceKm, 0.001); assertEquals(2, s.durationMin); assertEquals(100, s.score)
        assertFalse("종료 뒤 초기화", t.isActive)
        assertNull("종료 직후 정차는 새 주행이 아님", t.onTick(151_000, false, 0f, emptyList()))
    }

    @Test fun shortStopKeepsTheTripGoing() {
        val t = TripTracker()
        t.onTick(0, true, 36f, emptyList())
        drive(t, 1, 60, 36f)
        val stop = (61..80).mapNotNull { t.onTick(it * 1_000L, false, 0f, emptyList()) } // 20초 정차
        assertTrue(stop.isEmpty())
        drive(t, 81, 100, 36f)
        assertTrue(t.isActive)
        val end = (101..131).mapNotNull { t.onTick(it * 1_000L, false, 0f, emptyList()) }
        assertEquals(1, end.size)
        assertEquals("정차 구간은 거리에 넣지 않는다: 주행 80틱 x 10m", 0.8, end.single().distanceKm, 0.001)
        assertEquals("주행 80초 = 1분", 1, end.single().durationMin)
    }

    @Test fun gapsAreNotIntegrated() {
        val t = TripTracker()
        t.onTick(0, true, 36f, emptyList())
        t.onTick(1_000, true, 36f, emptyList())
        t.onTick(11_000, true, 36f, emptyList()) // 10초 공백(>3초): 거리·시간에 넣지 않음
        assertEquals(0.01, t.liveDistanceKm, 0.0001)
    }

    @Test fun eventsCountedWithDeductionMinutes() {
        val t = TripTracker()
        t.onTick(0, true, 36f, emptyList())
        drive(t, 1, 149, 36f)
        t.onTick(150_000, true, 36f, listOf(RiskEvent(RiskKind.HARD_BRAKE, 150_000)))
        t.onTick(151_000, true, 36f, listOf(RiskEvent(RiskKind.LANE_CHANGE, 151_000)))
        assertEquals(listOf(1, 0, 1, 0, 0), t.liveCounts)
        val s = t.finishNow()!!
        assertEquals(listOf(RiskKind.HARD_BRAKE, RiskKind.LANE_CHANGE), s.deductions.map { it.kind })
        assertEquals("150초 -> 2분 무렵", listOf(2, 2), s.deductions.map { it.atMin })
        assertEquals("거리 1.5km 이하라 환산 없이 6+8=14 감점", 86, s.score)
    }

    @Test fun shortTripHasNoScore() {
        val t = TripTracker()
        t.onTick(0, true, 36f, emptyList())
        drive(t, 1, 30, 36f)
        val s = t.finishNow()!!
        assertNull(s.score); assertEquals(0, s.durationMin)
    }

    @Test fun finishNowWhenInactiveIsNull() { assertNull(TripTracker().finishNow()) }

    @Test fun snapshotAndRestoreSummary() {
        val t = TripTracker()
        t.onTick(0, true, 72f, emptyList())
        drive(t, 1, 180, 72f) // 20m/s * 180s = 3600m, 3분
        t.onTick(181_000, true, 72f, listOf(RiskEvent(RiskKind.HARD_ACCEL, 181_000)))
        val snap = assertNotNull_(t.snapshot())
        assertEquals(3.62, snap.distanceM / 1000.0, 0.03)
        val s = TripTracker.summarize(snap)
        assertEquals(96, s.score) // 4 감점(3.6km < 10km 이므로 환산 없음)
        assertEquals(3, s.durationMin)
        assertNull(TripTracker().snapshot())
    }

    private fun <T> assertNotNull_(v: T?): T { assertNotNull(v); return v!! }
}
