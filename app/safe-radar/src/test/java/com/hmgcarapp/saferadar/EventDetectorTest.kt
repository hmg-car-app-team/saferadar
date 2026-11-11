package com.hmgcarapp.saferadar

import com.hmgcarapp.saferadar.domain.DriveSample
import com.hmgcarapp.saferadar.domain.EventDetector
import com.hmgcarapp.saferadar.domain.RiskKind
import com.hmgcarapp.saferadar.domain.TurnSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 임계값(3.5/3.0 m/s², 6°, 130km/h 등)은 미검증 초기값이다. 여기서는 그 값의 경계에서 동작이 갈리는지 리터럴로 확인한다. */
class EventDetectorTest {
    private fun s(t: Long, v: Float, brake: Boolean? = null, turn: TurnSignal? = null, steer: Float? = null) = DriveSample(t, v, brake, turn, steer)
    private fun run(d: EventDetector, vararg samples: DriveSample) = samples.flatMap { d.feed(it) }

    // ---------------- 첫 관측·끊김 ----------------
    @Test fun firstObservationNeverMakesEvent() {
        assertEquals(emptyList<Any>(), EventDetector().feed(s(0, 130f, brake = true, turn = TurnSignal.OFF, steer = 30f)))
    }

    @Test fun gapOverThreeSecondsResetsBaseline_notTreatedAsHardBrake() {
        val d = EventDetector()
        val ev = run(d, s(0, 100f, brake = true), s(5_000, 20f, brake = true), s(6_000, 20f, brake = true))
        assertEquals("끊긴 뒤 첫 표본은 기준만 잡는다", emptyList<Any>(), ev)
    }

    @Test fun nonFiniteOrNegativeSpeedResetsAndYieldsNothing() {
        val d = EventDetector()
        assertEquals(emptyList<Any>(), run(d, s(0, 100f), s(1_000, Float.NaN), s(2_000, 40f), s(3_000, 40f)))
    }

    // ---------------- 급감속 ----------------
    @Test fun hardBrake_boundary_12_5_vs_13_kmhPerSecond() {
        // 3.5 m/s² = 12.6 km/h/s
        assertEquals("12.5km/h/s = 3.47m/s² 는 미달", emptyList<Any>(), run(EventDetector(), s(0, 80f), s(1_000, 67.5f)))
        val ev = run(EventDetector(), s(0, 80f), s(1_000, 67f))
        assertEquals(listOf(RiskKind.HARD_BRAKE), ev.map { it.kind })
        assertEquals(1_000L, ev.single().tMs)
    }

    @Test fun hardBrake_notBelowMinFromSpeed() {
        assertEquals("기준 속도 14km/h(<15) 에서 0으로: 제외", emptyList<Any>(), run(EventDetector(), s(0, 14f), s(1_000, 0f)))
        assertEquals(listOf(RiskKind.HARD_BRAKE), run(EventDetector(), s(0, 15f), s(1_000, 0f)).map { it.kind })
    }

    @Test fun hardBrake_requiresPedalWhenBrakeSignalExists() {
        assertEquals("페달 신호가 있고 한 번도 밟히지 않음 -> 급감속 아님", emptyList<Any>(), run(EventDetector(), s(0, 100f, brake = false), s(1_000, 80f, brake = false)))
        assertEquals(listOf(RiskKind.HARD_BRAKE), run(EventDetector(), s(0, 100f, brake = false), s(1_000, 80f, brake = true)).map { it.kind })
        assertEquals("페달 신호 자체가 없으면 속도 하락만으로 판단", listOf(RiskKind.HARD_BRAKE), run(EventDetector(), s(0, 100f), s(1_000, 80f)).map { it.kind })
    }

    @Test fun hardBrake_oneContinuousBrakingCountsOnce_thenRearms() {
        val d = EventDetector()
        val ev = run(d,
            s(0, 100f, brake = true), s(1_000, 85f, brake = true), s(2_000, 70f, brake = true), s(3_000, 55f, brake = true), // 매초 -15km/h
            s(4_000, 55f, brake = true), s(5_000, 55f, brake = true),                                                         // 안정 -> 재무장
            s(6_000, 40f, brake = true))                                                                                       // 다시 급감속
        assertEquals(listOf(1_000L, 6_000L), ev.map { it.tMs })
        assertTrue(ev.all { it.kind == RiskKind.HARD_BRAKE })
    }

    // ---------------- 급가속 ----------------
    @Test fun hardAccel_boundary_10_vs_11_kmhPerSecond() {
        // 3.0 m/s² = 10.8 km/h/s
        assertEquals(emptyList<Any>(), run(EventDetector(), s(0, 40f), s(1_000, 50f)))
        assertEquals(listOf(RiskKind.HARD_ACCEL), run(EventDetector(), s(0, 40f), s(1_000, 51f)).map { it.kind })
    }

    @Test fun hardAccel_notFromStandstill() {
        assertEquals("기준 속도 4km/h(<5): 제외", emptyList<Any>(), run(EventDetector(), s(0, 4f), s(1_000, 15f)))
    }

    @Test fun samplesAtHalfSecondUseOneSecondBaseline() {
        // 0.5초 간격 표본: 속도는 1초 간격으로 갱신되는 폴링 값처럼 계단형. 급감속은 1초 이상 떨어진 기준과 비교한다.
        val ev = run(EventDetector(),
            s(0, 100f, brake = true), s(500, 100f, brake = true), s(1_000, 86f, brake = true), s(1_500, 86f, brake = true), s(2_000, 72f, brake = true))
        assertEquals(listOf(1_000L), ev.map { it.tMs })
    }

    // ---------------- 방향지시등 없는 차로변경 ----------------
    private fun laneSamples(turnAtStart: TurnSignal?, speed: Float = 80f, swing: Float = 6.5f): List<DriveSample> = listOf(
        s(0, speed, turn = turnAtStart, steer = 0f), s(500, speed, turn = TurnSignal.OFF, steer = swing / 2), s(1_000, speed, turn = TurnSignal.OFF, steer = -swing / 2),
    )

    @Test fun laneChange_swingBoundary_6deg() {
        assertEquals(listOf(RiskKind.LANE_CHANGE), run(EventDetector(), *laneSamples(TurnSignal.OFF, swing = 6.5f).toTypedArray()).map { it.kind })
        assertEquals("조향 변동폭 5.9° 는 미달", emptyList<Any>(), run(EventDetector(), *laneSamples(TurnSignal.OFF, swing = 5.9f).toTypedArray()))
    }

    @Test fun laneChange_speedBoundary_50kmh() {
        assertEquals(emptyList<Any>(), run(EventDetector(), *laneSamples(TurnSignal.OFF, speed = 49f).toTypedArray()))
        assertEquals(1, run(EventDetector(), *laneSamples(TurnSignal.OFF, speed = 50f).toTypedArray()).size)
    }

    @Test fun laneChange_withTurnSignalIsNotAnEvent_butStaleSignalIs() {
        // 지시등을 켜고(t=0) 1초 뒤 차로변경 -> 정상
        assertEquals(emptyList<Any>(), run(EventDetector(), *laneSamples(TurnSignal.LEFT).toTypedArray()))
        // 지시등은 5초 전에 꺼졌고 이후 변경 -> 방향지시등 없는 차로변경
        val d = EventDetector()
        val warm = (0..10).map { s(it * 500L, 80f, turn = if (it == 0) TurnSignal.RIGHT else TurnSignal.OFF, steer = 0f) } // t=0 지시등, ~5000ms 까지 직진
        val ev = run(d, *warm.toTypedArray(), s(5_500, 80f, turn = TurnSignal.OFF, steer = 4f), s(6_000, 80f, turn = TurnSignal.OFF, steer = -3f))
        assertEquals(listOf(RiskKind.LANE_CHANGE), ev.map { it.kind })
    }

    @Test fun laneChange_signalLookbackBoundary_4s() {
        fun runAt(swingAt: Long): List<RiskKind> {
            val d = EventDetector()
            val pre = (0..(swingAt / 500).toInt()).map { s(it * 500L, 80f, turn = if (it == 0) TurnSignal.LEFT else TurnSignal.OFF, steer = 0f) }
            return run(d, *pre.toTypedArray(), s(swingAt + 500, 80f, turn = TurnSignal.OFF, steer = 4f), s(swingAt + 1_000, 80f, turn = TurnSignal.OFF, steer = -3f)).map { it.kind }
        }
        assertEquals("지시등 3.5~4.0초 뒤(<=4s)에는 켜고 바꾼 것", emptyList<RiskKind>(), runAt(3_000)) // 마지막 표본 t=4000 -> 4000-0 <= 4000
        assertEquals("4.5초 뒤는 신호 없는 변경", listOf(RiskKind.LANE_CHANGE), runAt(3_500)) // 마지막 표본 t=4500
    }

    @Test fun laneChange_unknownTurnSignal_isNeverJudged() {
        // 방향지시등 신호가 한 번도 오지 않았다 = 꺼짐이 아니라 '알 수 없음'
        val ev = run(EventDetector(), s(0, 80f, steer = 0f), s(500, 80f, steer = 4f), s(1_000, 80f, steer = -4f))
        assertEquals(emptyList<Any>(), ev)
    }

    @Test fun laneChange_cooldown6s_andIgnoredSwingIsNotRecountedLater() {
        val d = EventDetector()
        fun straight(from: Long, to: Long) = (from..to step 500).map { s(it, 80f, turn = TurnSignal.OFF, steer = 0f) }
        val first = run(d, *laneSamples(TurnSignal.OFF).toTypedArray())
        assertEquals(listOf(1_000L), first.map { it.tMs })
        // 쿨다운(6초) 안인 5초대의 흔들림은 세지 않고, 쿨다운이 끝난 뒤에도 그 흔들림이 되살아나지 않는다
        val inCooldown = run(d, *straight(1_500, 4_500).toTypedArray(), s(5_000, 80f, turn = TurnSignal.OFF, steer = 4f), s(5_500, 80f, turn = TurnSignal.OFF, steer = -4f))
        assertEquals(emptyList<Any>(), inCooldown)
        val afterCooldown = run(d, *straight(6_000, 7_000).toTypedArray())
        assertEquals("1000+6000=7000 이후라도 이미 무시한 흔들림은 다시 세지 않는다", emptyList<Any>(), afterCooldown)
        // 쿨다운이 끝난 뒤 새 흔들림은 센다
        val fresh = run(d, s(7_500, 80f, turn = TurnSignal.OFF, steer = 4f), s(8_000, 80f, turn = TurnSignal.OFF, steer = -4f))
        assertEquals(listOf(8_000L), fresh.map { it.tMs })
    }

    // ---------------- 고속 ----------------
    private fun cruise(d: EventDetector, v: Float, fromS: Int, toS: Int) = (fromS..toS).flatMap { d.feed(s(it * 1_000L, v)) }

    @Test fun highSpeed_firesAfterTenSecondsOnce_andRearmsBelow120() {
        val d = EventDetector()
        val a = cruise(d, 130f, 0, 9)
        assertEquals("9초 지속은 미달", emptyList<Any>(), a)
        val b = d.feed(s(10_000, 130f))
        assertEquals(listOf(RiskKind.HIGH_SPEED), b.map { it.kind })
        assertEquals("같은 구간에서 다시 세지 않음", emptyList<Any>(), cruise(d, 135f, 11, 30))
        // 125 로 내려가도(재무장 기준 120 이상) 같은 구간
        assertEquals(emptyList<Any>(), cruise(d, 125f, 31, 40))
        assertEquals(emptyList<Any>(), cruise(d, 119f, 41, 42))
        // 119 로 내려가 재무장 -> 125 를 거쳐 다시 130 으로 10초 지속하면 새로 센다
        assertEquals(emptyList<Any>(), cruise(d, 125f, 43, 43))
        val again = cruise(d, 130f, 44, 54)
        assertEquals(listOf(RiskKind.HIGH_SPEED), again.map { it.kind })
        assertEquals(54_000L, again.single().tMs)
    }

    @Test fun highSpeed_129isNotHigh() {
        assertEquals(emptyList<Any>(), cruise(EventDetector(), 129.9f, 0, 30))
    }

    // ---------------- 급격한 고속 진입 ----------------
    @Test fun surge_gain40ToAtLeast100() {
        val ev = run(EventDetector(), s(0, 60f), s(1_000, 70f), s(2_000, 80f), s(3_000, 90f), s(4_000, 100f))
        assertEquals(listOf(RiskKind.SPEED_SURGE), ev.map { it.kind })
        assertEquals(4_000L, ev.single().tMs)
        val less = run(EventDetector(), s(0, 61f), s(1_000, 70f), s(2_000, 80f), s(3_000, 90f), s(4_000, 100f))
        assertEquals("39km/h 증가는 미달", emptyList<Any>(), less)
        val low = run(EventDetector(), s(0, 40f), s(1_000, 50f), s(2_000, 60f), s(3_000, 70f), s(4_000, 80f))
        assertEquals("도달 속도 100 미만", emptyList<Any>(), low)
    }

    @Test fun surge_cooldown20s() {
        val d = EventDetector()
        val a = run(d, s(0, 60f), s(1_000, 70f), s(2_000, 80f), s(3_000, 90f), s(4_000, 100f))
        assertEquals(1, a.size)
        val b = run(d, s(5_000, 60f), s(6_000, 70f), s(7_000, 80f), s(8_000, 90f), s(9_000, 100f)) // 5초 뒤 또 -> 쿨다운
        assertTrue(b.none { it.kind == RiskKind.SPEED_SURGE })
    }
}
