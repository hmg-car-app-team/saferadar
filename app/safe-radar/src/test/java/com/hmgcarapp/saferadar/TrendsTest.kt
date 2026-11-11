package com.hmgcarapp.saferadar

import com.hmgcarapp.saferadar.domain.Badge
import com.hmgcarapp.saferadar.domain.Trends
import com.hmgcarapp.saferadar.domain.TripRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrendsTest {
    private val day = Trends.DAY_MS
    private val tz = 9 * 3_600_000L // KST
    private val baseDay = 20_000L   // 임의의 기준 일 번호(오늘)
    /** 기준일 + [offset]일 의 [hour]시(KST) 벽시계 시각 */
    private fun at(offset: Int, hour: Int = 12) = (baseDay + offset) * day - tz + hour * 3_600_000L
    private fun trip(offset: Int, score: Int, hour: Int = 12) = TripRecord(at(offset, hour), score, 10f, 20, listOf(0, 0, 0, 0, 0))
    private val now = at(0, 20)

    @Test fun dayIndexBoundaries() {
        assertEquals(0L, Trends.dayIndex(0, 0))
        assertEquals(-1L, Trends.dayIndex(-1, 0))
        assertEquals(0L, Trends.dayIndex(day - 1, 0))
        assertEquals("시간대 오프셋이 날짜를 넘긴다", 1L, Trends.dayIndex(day - 1, 1))
        assertEquals(20_000L, Trends.dayIndex(at(0, 0), tz))
        assertEquals(19_999L, Trends.dayIndex(at(0, 0) - 1, tz))
    }

    @Test fun last7DaysAveragesPerDay_oldestFirst() {
        val trips = listOf(trip(0, 80, 8), trip(0, 90, 18), trip(-2, 70), trip(-6, 100), trip(-7, 60))
        val d = Trends.last7Days(trips, now, tz)
        assertEquals(7, d.size)
        assertEquals(19_994L, d.first().dayIndex); assertEquals(20_000L, d.last().dayIndex)
        assertEquals(100, d[0].avg); assertEquals(1, d[0].trips)           // -6일
        assertNull(d[1].avg)                                               // -5일 기록 없음
        assertEquals(70, d[4].avg)                                         // -2일
        assertEquals(85, d[6].avg); assertEquals(2, d[6].trips)            // 오늘 (80+90)/2
    }

    @Test fun weekAverageDeltaAndCount() {
        val trips = listOf(trip(0, 80, 8), trip(0, 90, 18), trip(-2, 70), trip(-6, 100), trip(-7, 60), trip(-9, 64), trip(-14, 10))
        assertEquals("이번 주: (80+90+70+100)/4", 85, Trends.weekAverage(trips, now, tz))
        assertEquals(4, Trends.weekCount(trips, now, tz))
        assertEquals("지난주 [-13..-7]: (60+64)/2", 62, Trends.weekAverage(trips, now, tz, 1))
        assertEquals(23, Trends.weekDelta(trips, now, tz))
        assertNull("지난주에 기록이 없으면 비교 불가", Trends.weekDelta(listOf(trip(0, 80)), now, tz))
        assertNull(Trends.weekAverage(emptyList(), now, tz))
    }

    @Test fun weekDeltaNegative() {
        val trips = listOf(trip(0, 70), trip(-8, 90))
        assertEquals(-20, Trends.weekDelta(trips, now, tz))
    }

    @Test fun badgesFirstAndCalm() {
        assertEquals(emptySet<Badge>(), Trends.badges(emptyList(), now, tz))
        assertEquals(setOf(Badge.FIRST_TRIP), Trends.badges(listOf(trip(0, 89)), now, tz))
        assertEquals(setOf(Badge.FIRST_TRIP, Badge.CALM_90), Trends.badges(listOf(trip(0, 90)), now, tz))
    }

    @Test fun streak3NeedsThreeConsecutiveAtLeast80() {
        assertTrue(Badge.STREAK_3 in Trends.badges(listOf(trip(-2, 80), trip(-1, 85), trip(0, 90)), now, tz))
        assertFalse("중간 79점이면 연속이 끊긴다", Badge.STREAK_3 in Trends.badges(listOf(trip(-2, 80), trip(-1, 79), trip(0, 90)), now, tz))
        assertFalse("2회뿐", Badge.STREAK_3 in Trends.badges(listOf(trip(-1, 85), trip(0, 90)), now, tz))
        // 입력 순서와 무관하게 시각 순으로 판단
        assertTrue(Badge.STREAK_3 in Trends.badges(listOf(trip(0, 90), trip(-2, 80), trip(-1, 85)), now, tz))
    }

    @Test fun improvedNeeds5PointsAndTwoTripsInBothWeeks() {
        val ok = listOf(trip(0, 80), trip(-1, 80), trip(-8, 70), trip(-9, 70))
        assertTrue(Badge.IMPROVED in Trends.badges(ok, now, tz))
        val four = listOf(trip(0, 74), trip(-1, 74), trip(-8, 70), trip(-9, 70))
        assertFalse("4점 상승은 미달", Badge.IMPROVED in Trends.badges(four, now, tz))
        val few = listOf(trip(0, 90), trip(-8, 70), trip(-9, 70))
        assertFalse("이번 주 주행이 1회뿐", Badge.IMPROVED in Trends.badges(few, now, tz))
    }
}
