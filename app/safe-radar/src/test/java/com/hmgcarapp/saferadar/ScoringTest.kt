package com.hmgcarapp.saferadar

import com.hmgcarapp.saferadar.domain.GradeCalc
import com.hmgcarapp.saferadar.domain.RiskEvent
import com.hmgcarapp.saferadar.domain.RiskGrade
import com.hmgcarapp.saferadar.domain.RiskKind
import com.hmgcarapp.saferadar.domain.ScoreCalc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 가중치(6/4/8/5/5)는 미검증 초기값. 점수는 리터럴로 기대한다. 개수 순서: [급감속, 급가속, 차로변경, 고속, 고속진입]. */
class ScoringTest {
    @Test fun noEventsIs100() { assertEquals(100, ScoreCalc.score(listOf(0, 0, 0, 0, 0), 5.0, 10)) }

    @Test fun penaltyIsNotDilutedBelow10km_andPer10kmAbove() {
        val c = listOf(2, 0, 1, 0, 0) // 6*2 + 8 = 20
        assertEquals(80, ScoreCalc.score(c, 5.0, 10))
        assertEquals(80, ScoreCalc.score(c, 10.0, 10))
        assertEquals(90, ScoreCalc.score(c, 20.0, 20))
        assertEquals(96, ScoreCalc.score(c, 50.0, 40))
    }

    @Test fun eachKindWeight() {
        assertEquals(94, ScoreCalc.score(listOf(1, 0, 0, 0, 0), 1.0, 5))
        assertEquals(96, ScoreCalc.score(listOf(0, 1, 0, 0, 0), 1.0, 5))
        assertEquals(92, ScoreCalc.score(listOf(0, 0, 1, 0, 0), 1.0, 5))
        assertEquals(95, ScoreCalc.score(listOf(0, 0, 0, 1, 0), 1.0, 5))
        assertEquals(95, ScoreCalc.score(listOf(0, 0, 0, 0, 1), 1.0, 5))
    }

    @Test fun clampedAtZero() { assertEquals(0, ScoreCalc.score(listOf(20, 20, 20, 20, 20), 1.0, 30)) }

    @Test fun shortTripsAreNotScored_boundary() {
        assertNull(ScoreCalc.score(listOf(0, 0, 0, 0, 0), 0.49, 10))
        assertNull(ScoreCalc.score(listOf(0, 0, 0, 0, 0), 3.0, 1))
        assertEquals(100, ScoreCalc.score(listOf(0, 0, 0, 0, 0), 0.5, 2))
    }

    @Test fun tipFollowsBiggestPenaltyContribution() {
        assertTrue(ScoreCalc.tip(listOf(1, 0, 1, 0, 0)).contains("방향지시등")) // 6 vs 8
        assertTrue(ScoreCalc.tip(listOf(3, 0, 1, 0, 0)).contains("거리")) // 18 vs 8
        assertTrue("동률(급가속 2회=8, 차로변경 1회=8)은 번호가 낮은 급가속", ScoreCalc.tip(listOf(0, 2, 1, 0, 0)).contains("페달"))
        assertTrue(ScoreCalc.tip(listOf(0, 0, 0, 0, 0)).contains("부드럽게"))
    }

    @Test fun summarizeBundlesScoreAndTip() {
        val s = ScoreCalc.summarize(12.0, 15, listOf(1, 0, 0, 0, 0), emptyList())
        assertEquals(95, s.score) // 6*10/12 = 5
        assertEquals(1, s.totalEvents); assertEquals(1, s.count(RiskKind.HARD_BRAKE)); assertEquals(0, s.count(RiskKind.HIGH_SPEED))
    }

    // ---------------- 공유용 등급 ----------------
    private fun ev(k: RiskKind, t: Long) = RiskEvent(k, t)

    @Test fun gradeThresholds() {
        assertEquals(RiskGrade.LOW, GradeCalc.grade(emptyList(), 0))
        assertEquals(RiskGrade.LOW, GradeCalc.grade(listOf(ev(RiskKind.HARD_ACCEL, 0)), 0)) // 4
        assertEquals(RiskGrade.MID, GradeCalc.grade(listOf(ev(RiskKind.HARD_BRAKE, 0)), 0)) // 6
        assertEquals(RiskGrade.MID, GradeCalc.grade(listOf(ev(RiskKind.LANE_CHANGE, 0)), 0)) // 8
        assertEquals(RiskGrade.HIGH, GradeCalc.grade(listOf(ev(RiskKind.HARD_BRAKE, 0), ev(RiskKind.HARD_BRAKE, 1)), 1)) // 12
        assertEquals(RiskGrade.HIGH, GradeCalc.grade(listOf(ev(RiskKind.LANE_CHANGE, 0), ev(RiskKind.HARD_ACCEL, 0)), 0)) // 12
    }

    @Test fun gradeWindowIs10Minutes() {
        val old = listOf(ev(RiskKind.LANE_CHANGE, 0), ev(RiskKind.LANE_CHANGE, 0)) // 16
        assertEquals(RiskGrade.HIGH, GradeCalc.grade(old, 600_000))
        assertEquals(RiskGrade.LOW, GradeCalc.grade(old, 600_001))
    }

    @Test fun dominantKind() {
        val es = listOf(ev(RiskKind.HARD_ACCEL, 0), ev(RiskKind.HARD_ACCEL, 1), ev(RiskKind.HARD_ACCEL, 2), ev(RiskKind.LANE_CHANGE, 3)) // 12 vs 8
        assertEquals(RiskKind.HARD_ACCEL, GradeCalc.dominant(es, 10))
        assertNull(GradeCalc.dominant(es, 700_000))
    }
}
