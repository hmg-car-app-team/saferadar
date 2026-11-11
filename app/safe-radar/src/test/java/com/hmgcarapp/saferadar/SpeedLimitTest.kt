package com.hmgcarapp.saferadar

import com.hmgcarapp.saferadar.domain.DriveSample
import com.hmgcarapp.saferadar.domain.EventDetector
import com.hmgcarapp.saferadar.domain.RiskKind
import com.hmgcarapp.saferadar.domain.SpeedLimit
import com.hmgcarapp.saferadar.vehicle.SpeedLimitSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/** NaviHelper onCurrentRoadSpeedLimit 연동(SDK 문서 기준, 에뮬레이터에서는 콜백 수신만 확인, 0이 아닌 값은 미관측)을 가짜 소스로 확인한다. 제한속도가 없거나 비정상이면 기존 동작과 같아야 한다. */
class SpeedLimitTest {
    private fun s(t: Long, v: Float, limit: Int?) = DriveSample(t, v, limitKmh = limit)
    private fun run(limit: Int?, speed: Float): List<RiskKind> {
        val d = EventDetector()
        return (0..12).flatMap { d.feed(s(it * 1_000L, speed, limit)) }.map { it.kind }
    }

    @Test fun sanityRange() {
        assertNull(SpeedLimit.sane(0)); assertNull(SpeedLimit.sane(-30)); assertNull(SpeedLimit.sane(9)); assertNull(SpeedLimit.sane(151)); assertNull(SpeedLimit.sane(Int.MAX_VALUE)); assertNull(SpeedLimit.sane(null))
        assertEquals(10, SpeedLimit.sane(10)); assertEquals(150, SpeedLimit.sane(150)); assertEquals(60, SpeedLimit.sane(60))
    }

    @Test fun validLimit_overLimitHeldTenSecondsFiresHighSpeed() {
        assertEquals(listOf(RiskKind.HIGH_SPEED), run(60, 80f)) // 60 + 20
        assertEquals(emptyList<RiskKind>(), run(60, 79f))
    }

    @Test fun noLimit_orInvalidLimit_keepsLegacyBehavior() {
        assertEquals(emptyList<RiskKind>(), run(null, 80f)); assertEquals(emptyList<RiskKind>(), run(0, 80f)); assertEquals(emptyList<RiskKind>(), run(-1, 80f))
        assertEquals(emptyList<RiskKind>(), run(5, 80f)); assertEquals(emptyList<RiskKind>(), run(999, 80f))
        assertEquals(listOf(RiskKind.HIGH_SPEED), run(null, 135f)); assertEquals(listOf(RiskKind.HIGH_SPEED), run(0, 135f))
    }

    @Test fun limitDoesNotSuppressLegacyHighSpeed() {
        assertEquals(listOf(RiskKind.HIGH_SPEED), run(110, 135f))
    }

    @Test fun rearmWaitsUntilBelowLimitPlusTen() {
        val d = EventDetector()
        val ev = ArrayList<RiskKind>()
        var t = 0L
        fun feed(v: Float, n: Int) = repeat(n) { ev += d.feed(s(t, v, 60)).map { it.kind }.filter { it == RiskKind.HIGH_SPEED }; t += 1_000L }
        feed(80f, 12); feed(72f, 3) // 70 이상이면 재무장 안 됨
        feed(80f, 12)
        assertEquals(1, ev.size)
        feed(65f, 2); feed(80f, 12) // 70 미만 -> 재무장
        assertEquals(2, ev.size)
    }

    @Test fun limitFlickerDoesNotRefireHighSpeed() {
        val d = EventDetector()
        var n = 0
        for (i in 0..90) { // 제한 80, 속도 105(= 제한+25): 제한속도가 1초마다 있다/없다 반복
            val lim = if (i % 2 == 0) 80 else null
            n += d.feed(s(i * 1_000L, 105f, lim)).count { it.kind == RiskKind.HIGH_SPEED }
        }
        assertEquals(1, n)
    }

    @Test fun limitRemembersOnlyBriefly() {
        val d = EventDetector()
        var n = 0
        fun feed(t: Long, v: Float, lim: Int?) { n += d.feed(s(t, v, lim)).count { it.kind == RiskKind.HIGH_SPEED } }
        for (t in 0..12L) feed(t * 1_000L, 105f, 80)
        assertEquals(1, n)
        for (t in 13..60L) feed(t * 1_000L, 105f, null) // 제한속도 사라짐, 기억(30초) 지난 뒤에는 정상 재무장 -> 105 는 130 미만이라 이벤트 아님
        for (t in 61..75L) feed(t * 1_000L, 100f, null)
        assertEquals(1, n)
    }

    @Test fun sdkAdapterOnlyUsesAllowedApis_moduleWide() {
        val allowed = setOf("initialize", "addListener", "removeListener", "release")
        val files = File("src/main").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        for (f in files) {
            val text = f.readLines().filter { !it.trimStart().startsWith("*") && !it.trimStart().startsWith("/*") && !it.trimStart().startsWith("//") }.joinToString("\n")
            if (!text.contains("NaviHelper")) continue
            // NaviHelper 변수(h)에 대한 호출 이름을 모아 허용 목록과 비교한다
            val calls = Regex("\\b(?:h|helper)\\.(\\w+)\\(").findAll(text).map { it.groupValues[1] }.toSet()
            val offenders = calls - allowed
            assertEquals("${f.name}: 허용 목록 밖 NaviHelper 호출", emptySet<String>(), offenders)
        }
        assertEquals(true, files.any { it.readText().contains("NaviHelper") })
    }

    private class FakeSource(val values: List<Int>) : SpeedLimitSource { override fun limits(): Flow<Int> = flow { values.forEach { emit(it) } } }
    private class SilentSource : SpeedLimitSource { override fun limits(): Flow<Int> = flow { } }

    @Test fun fakeSource_emitsWhatItGets_andSilentEmitsNothing() = runTest {
        assertEquals(listOf(50, 0, -3, 80), FakeSource(listOf(50, 0, -3, 80)).limits().toList())
        assertEquals(emptyList<Int>(), SilentSource().limits().toList())
        assertEquals(listOf(50, 80), FakeSource(listOf(50, 0, -3, 80)).limits().toList().mapNotNull { SpeedLimit.sane(it) }) // 컨트롤러가 거르는 방식
    }

    @Test fun sdkAdapterIsReadOnly_noRequestsNoNewPermission() {
        val src = File("src/main/java/com/hmgcarapp/saferadar/vehicle/NaviSpeedLimit.kt").readLines().filter { !it.trimStart().startsWith("*") && !it.trimStart().startsWith("/*") && !it.trimStart().startsWith("//") }.joinToString("\n")
        Regex("\\.(requestRoute|requestTrip|addWaypoint|changeRouteOption|requestReRoute|cancelRoute|get\\w+Info)\\(").find(src)?.let { throw AssertionError("읽기 전용 위반: ${it.value}") }
        val m = File("src/main/AndroidManifest.xml").readText()
        assertEquals(false, m.contains("NAVI_"))
    }
}
