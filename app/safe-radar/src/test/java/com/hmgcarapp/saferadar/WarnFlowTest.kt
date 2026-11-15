package com.hmgcarapp.saferadar

import com.hmgcarapp.saferadar.domain.Approach
import com.hmgcarapp.saferadar.domain.Candidate
import com.hmgcarapp.saferadar.domain.Direction
import com.hmgcarapp.saferadar.domain.RiskGrade
import com.hmgcarapp.saferadar.domain.RiskKind
import com.hmgcarapp.saferadar.domain.WarnEffect
import com.hmgcarapp.saferadar.domain.WarnEvent
import com.hmgcarapp.saferadar.domain.WarnFlow
import com.hmgcarapp.saferadar.domain.WarnState
import com.hmgcarapp.saferadar.domain.WarnText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 경고 알림 흐름: 이벤트 시퀀스를 reducer 에 넣어 상태·효과를 확인한다. */
class WarnFlowTest {
    private val rearLeft = Candidate(Direction.REAR_LEFT, RiskKind.LANE_CHANGE, RiskGrade.HIGH, 120.0)
    private val front = Candidate(Direction.FRONT, RiskKind.HARD_BRAKE, RiskGrade.HIGH, 90.0)

    private class Run { var st = WarnState(); val fx = mutableListOf<WarnEffect>()
        fun send(e: WarnEvent): List<WarnEffect> { val (n, f) = WarnFlow.reduce(st, e); st = n; fx += f; return f } }

    private fun driving() = Run().also { it.send(WarnEvent.DriveChanged(true)) }

    @Test fun textsAreExact() {
        assertEquals("왼쪽 뒤에 급차선변경이 잦은 차량이 있습니다. 차로 변경에 주의하세요.", WarnText.voice(rearLeft))
        assertEquals("왼쪽 뒤 · 방향지시등 없는 차로변경 주의", WarnText.banner(rearLeft))
        assertEquals("앞쪽에 급제동이 잦은 차량이 있습니다. 차간 거리를 넉넉히 두세요.", WarnText.voice(front))
        assertEquals("가까운 곳에 급제동이 잦은 차량이 있습니다. 주의하세요.", WarnText.voice(front.copy(direction = Direction.NEAR)))
        assertEquals("오른쪽 옆에 위험 등급 차량이 있습니다. 차로 변경에 주의하세요.", WarnText.voice(rearLeft.copy(direction = Direction.RIGHT, kind = null)))
        assertEquals("오른쪽 앞 · 위험 차량 주의", WarnText.banner(rearLeft.copy(direction = Direction.FRONT_RIGHT, kind = null)))
    }

    @Test fun noWarningWhileParked() {
        val r = Run()
        assertEquals(emptyList<WarnEffect>(), r.send(WarnEvent.Nearby(1_000, listOf(rearLeft))))
        assertNull(r.st.banner)
    }

    @Test fun warnsWithVoiceAndThreeSecondBanner() {
        val r = driving()
        val fx = r.send(WarnEvent.Nearby(1_000, listOf(rearLeft)))
        assertEquals(listOf<WarnEffect>(WarnEffect.Speak("왼쪽 뒤에 급차선변경이 잦은 차량이 있습니다. 차로 변경에 주의하세요.")), fx)
        assertEquals("왼쪽 뒤 · 방향지시등 없는 차로변경 주의", r.st.banner!!.text)
        assertEquals("주행 화면 원호가 켤 방향", Direction.REAR_LEFT, r.st.banner!!.direction)
        assertEquals(4_000L, r.st.banner!!.untilMs)
        r.send(WarnEvent.Tick(3_999)); assertNotNull("3초가 지나기 전에는 유지", r.st.banner)
        r.send(WarnEvent.Tick(4_000)); assertNull("3초 뒤 사라짐", r.st.banner)
    }

    @Test fun onlyHighGradeWarns() {
        val r = driving()
        r.send(WarnEvent.Nearby(1_000, listOf(rearLeft.copy(grade = RiskGrade.MID), rearLeft.copy(grade = RiskGrade.LOW))))
        assertNull(r.st.banner); assertNull("원호도 위험 등급만", r.st.radar); assertEquals(emptyList<WarnEffect>(), r.fx)
    }

    @Test fun radarArcStaysOnWhileVehicleIsReported_evenDuringVoiceCooldown() {
        val r = driving()
        r.send(WarnEvent.Nearby(1_000, listOf(rearLeft)))
        assertEquals(Direction.REAR_LEFT, r.st.radar!!.direction)
        assertEquals("8초 안이라 음성은 없음", emptyList<WarnEffect>(), r.send(WarnEvent.Nearby(4_000, listOf(rearLeft, front))))
        assertEquals("원호는 지금 가장 가까운 차량 방향으로 바뀐다", Direction.FRONT, r.st.radar!!.direction)
        r.send(WarnEvent.Tick(8_999)); assertNotNull("마지막 응답 뒤 5초까지 유지", r.st.radar)
        r.send(WarnEvent.Tick(9_000)); assertNull("응답이 끊기면 꺼짐", r.st.radar)
        r.send(WarnEvent.Nearby(10_000, listOf(rearLeft)))
        r.send(WarnEvent.DriveChanged(false)); assertNull("정차하면 꺼짐", r.st.radar)
    }

    @Test fun globalMinGap8s_andSignatureCooldown30s() {
        val r = driving()
        r.send(WarnEvent.Nearby(1_000, listOf(rearLeft)))
        assertEquals("8초 안에는 다른 상황도 경고하지 않음", emptyList<WarnEffect>(), r.send(WarnEvent.Nearby(8_999, listOf(front))))
        assertEquals("8초가 지나면 다른 상황은 경고", 1, r.send(WarnEvent.Nearby(9_000, listOf(front))).size)
        assertEquals("같은 상황(방향·유형)은 30초 안에 반복하지 않음", emptyList<WarnEffect>(), r.send(WarnEvent.Nearby(30_999, listOf(rearLeft))))
        assertEquals("30초가 지나면 다시 경고", 1, r.send(WarnEvent.Nearby(31_000, listOf(rearLeft))).size)
        assertEquals(3, r.fx.size)
    }

    @Test fun picksNearestCandidate() {
        val r = driving()
        val fx = r.send(WarnEvent.Nearby(1_000, listOf(rearLeft.copy(distanceM = 150.0), front.copy(distanceM = 60.0))))
        assertEquals(WarnEffect.Speak(WarnText.voice(front.copy(distanceM = 60.0))), fx.single())
    }

    @Test fun driveChangedToParkedClearsBannerAndCancelsSpeech() {
        val r = driving()
        r.send(WarnEvent.Nearby(1_000, listOf(rearLeft)))
        val fx = r.send(WarnEvent.DriveChanged(false))
        assertEquals(listOf<WarnEffect>(WarnEffect.CancelSpeech), fx)
        assertNull(r.st.banner)
        assertEquals("정차 중에는 후보가 와도 경고하지 않는다", emptyList<WarnEffect>(), r.send(WarnEvent.Nearby(60_000, listOf(front))))
    }

    @Test fun signatureMemoryIsBounded() {
        val r = driving()
        var t = 1_000L
        Direction.entries.forEach { d -> RiskKind.entries.forEach { k ->
            r.send(WarnEvent.Nearby(t, listOf(Candidate(d, k, RiskGrade.HIGH, 100.0)))); t += WarnFlow.MIN_GAP_MS
        } }
        assertTrue(r.st.lastBySignature.size <= WarnFlow.MAX_SIGNATURES)
        assertEquals("가장 최근 상황은 기억한다", true, r.st.lastBySignature.containsKey("NEAR|SPEED_SURGE"))
    }
}

/** '접근 중' 판정: 앞쪽 ±각도 이내만 참, 정보 없으면 null(기존 경고 유지). */
class ApproachTest {
    @Test fun ahead() { assertEquals(true, Approach.isAhead(0.0)); assertEquals(true, Approach.isAhead(-30.0)) }
    @Test fun behindAndSide() { assertEquals(false, Approach.isAhead(180.0)); assertEquals(false, Approach.isAhead(90.0)); assertEquals(false, Approach.isAhead(-100.0)) }
    @Test fun boundary() {
        assertEquals(true, Approach.isAhead(Approach.AHEAD_HALF_ANGLE_DEG)); assertEquals(true, Approach.isAhead(-Approach.AHEAD_HALF_ANGLE_DEG))
        assertEquals(false, Approach.isAhead(Approach.AHEAD_HALF_ANGLE_DEG + 0.1))
    }
    @Test fun unknown() { assertNull(Approach.isAhead(null)); assertNull(Approach.isAhead(Double.NaN)); assertNull(Approach.relativeBearing(5.0, 5.0)) }
    @Test fun bearingFromPosition() { assertEquals(0.0, Approach.relativeBearing(100.0, 0.0)!!, 1e-6); assertEquals(90.0, Approach.relativeBearing(0.0, 100.0)!!, 1e-6) }
    // 위험 차량 정보에는 속도가 없어 '접근 중' 여부를 알 수 없다 — 문구는 위치(앞쪽 등)만 말하고 접근을 단정하지 않는다.
    @Test fun wordingNeverClaimsApproaching() {
        val ahead = Candidate(Direction.FRONT, RiskKind.HARD_BRAKE, RiskGrade.HIGH, 90.0, 10.0)
        assertTrue(WarnText.voice(ahead).contains("있습니다")); assertTrue(!WarnText.voice(ahead).contains("접근")); assertTrue(!WarnText.banner(ahead).contains("접근"))
        val noDir = ahead.copy(relBearingDeg = null)
        assertTrue(WarnText.voice(noDir).contains("있습니다")); assertTrue(!WarnText.banner(noDir).contains("접근"))
        val side = Candidate(Direction.REAR_LEFT, RiskKind.LANE_CHANGE, RiskGrade.HIGH, 120.0, -150.0)
        assertTrue(!WarnText.voice(side).contains("접근"))
    }
    @Test fun unknownStillWarns() {
        val (st0, _) = WarnFlow.reduce(WarnState(), WarnEvent.DriveChanged(true))
        val (_, fx) = WarnFlow.reduce(st0, WarnEvent.Nearby(0, listOf(Candidate(Direction.FRONT, null, RiskGrade.HIGH, 90.0))))
        assertEquals(1, fx.size)
    }
}
