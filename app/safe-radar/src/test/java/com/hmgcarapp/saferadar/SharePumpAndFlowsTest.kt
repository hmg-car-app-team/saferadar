package com.hmgcarapp.saferadar

import com.hmgcarapp.saferadar.app.SharePump
import com.hmgcarapp.saferadar.app.ShareInput
import com.hmgcarapp.saferadar.domain.AnonIdRotator
import com.hmgcarapp.saferadar.domain.NearbyVehicle
import com.hmgcarapp.saferadar.domain.Report
import com.hmgcarapp.saferadar.domain.RiskGrade
import com.hmgcarapp.saferadar.domain.RiskKind
import com.hmgcarapp.saferadar.domain.ShareStatus
import com.hmgcarapp.saferadar.domain.TurnSignal
import com.hmgcarapp.saferadar.net.ShareApi
import com.hmgcarapp.saferadar.vehicle.VehiclePort
import com.hmgcarapp.saferadar.vehicle.portFlowOf
import com.hmgcarapp.saferadar.vehicle.toDomain
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SharePumpAndFlowsTest {
    private class FakeApi : ShareApi {
        val reports = mutableListOf<Report>(); val leaves = mutableListOf<String>(); var fail = false
        override suspend fun report(r: Report): List<NearbyVehicle> { reports += r; if (fail) throw java.io.IOException("x"); return listOf(NearbyVehicle("wydm6dk", 0, RiskGrade.HIGH, RiskKind.LANE_CHANGE)) }
        override suspend fun leave(id: String) { leaves += id }
    }

    private var now = 0L
    private fun pump(api: ShareApi? = FakeApi(), url: String? = "https://x.example") = SharePump(api, url, AnonIdRotator(monoClock = { now }))
    private fun input(consent: Boolean = true, demo: Boolean = false, driving: Boolean = true, speed: Float? = 60f, pos: Boolean = true, hd: Double? = 90.0) =
        ShareInput(consent, demo, driving, speed, if (pos) 37.5 else null, if (pos) 127.03 else null, if (pos) hd else null, RiskGrade.HIGH, RiskKind.HARD_BRAKE)

    // ---------------- 전송 관문 ----------------
    @Test fun sendsNothingWithoutConsent_noMatterWhat() {
        val p = pump()
        assertNull(p.prepare(10_000, input(consent = false)))
        assertEquals(ShareStatus.NO_CONSENT, p.status(input(consent = false)))
    }

    @Test fun sendsNothingInDemoOrWithoutServerOrWhenParkedOrSlowOrNoPosition() {
        assertNull(pump().prepare(10_000, input(demo = true)))
        assertNull(pump(api = null, url = null).prepare(10_000, input()))
        assertNull(pump().prepare(10_000, input(driving = false)))
        assertNull(pump().prepare(10_000, input(speed = 19.9f)))
        assertNull(pump().prepare(10_000, input(speed = null)))
        assertNull(pump().prepare(10_000, input(pos = false)))
        assertNull(pump().prepare(10_000, input(hd = null)))
    }

    @Test fun reportHasOnlyCellHeadingGradeKindAndId() {
        val r = pump().prepare(10_000, input())!!
        assertEquals("wydm6ds", r.geohash)      // 37.5, 127.03 의 7자리 셀(정확 좌표 아님)
        assertEquals(2, r.heading8)             // 90° = 동쪽 섹터 2
        assertEquals(RiskGrade.HIGH, r.grade); assertEquals(RiskKind.HARD_BRAKE, r.kind)
        assertTrue(r.id.matches(Regex("[0-9a-f]{32}")))
    }

    @Test fun oneInFlightAndEvery3Seconds() {
        val p = pump()
        assertNotNull(p.prepare(10_000, input()))
        assertNull("전송 중에는 다음 건을 만들지 않는다", p.prepare(20_000, input()))
        p.finish(true)
        assertNull("3초 간격 미만", p.prepare(12_999, input()))
        assertNotNull(p.prepare(13_000, input()))
    }

    @Test fun backsOffTo60SecondsAfterFiveConsecutiveFailures() {
        val p = pump(); var t = 10_000L
        repeat(5) { assertNotNull(p.prepare(t, input())); p.finish(false); t += 15_000 }
        assertEquals(5, p.consecutiveFailures)
        val last = t - 15_000
        assertNull(p.prepare(last + 59_999, input()))
        assertNotNull(p.prepare(last + 60_000, input())); p.finish(true)
        assertEquals(0, p.consecutiveFailures)
    }

    @Test fun failureIsRememberedAndClearedOnSuccess() {
        val p = pump()
        p.prepare(10_000, input()); p.finish(false)
        assertTrue(p.failed); assertEquals(ShareStatus.ERROR, p.status(input()))
        assertNull("실패 뒤에는 3초가 아니라 15초 간격(재시도 폭주 방지)", p.prepare(13_000, input()))
        assertNull(p.prepare(24_999, input()))
        assertNotNull(p.prepare(25_000, input())); p.finish(true)
        assertFalse(p.failed); assertEquals(ShareStatus.ACTIVE, p.status(input()))
    }

    @Test fun serverUnavailableFallsBackToNoSendAndDemoStillWorks() {
        // 서버 주소가 없으면(정책 거부 포함) 어떤 입력에도 보내지 않고, 시연은 서버와 무관
        val none = SharePump(null, null, AnonIdRotator(monoClock = { now }))
        assertNull(none.prepare(10_000, input()))
        assertEquals(ShareStatus.NO_SERVER, none.status(input()))
        assertEquals(ShareStatus.DEMO, none.status(input().copy(demo = true)))
        // 동의 전에는 서버가 있어도 보내지 않는다
        assertNull(pump().prepare(10_000, input().copy(consent = false)))
    }

    @Test fun revokeReturnsOldIdAndNextReportUsesNewId() {
        val p = pump()
        val first = p.prepare(10_000, input())!!; p.finish(true)
        val old = p.revoke()
        assertEquals(first.id, old)
        assertNull("철회 직후 아직 보낸 적 없는 ID 는 없다", p.revoke())
        val next = p.prepare(20_000, input())!!
        assertNotEquals(first.id, next.id)
    }

    @Test fun sendAndLeaveGoThroughApi() = runTest {
        val api = FakeApi()
        val p = pump(api)
        val r = p.prepare(10_000, input())!!
        val ok = p.send(r, p.generation) { true } as SharePump.SendResult.Ok
        assertEquals(1, ok.list.size); assertFalse(ok.revoked)
        assertEquals(listOf(r), api.reports)
        api.fail = true
        assertEquals("예외는 실패로 바뀐다", SharePump.SendResult.Failed, p.send(r, p.generation) { true })
        p.leave("abc"); assertEquals(listOf("abc"), api.leaves)
    }

    // ---------------- Vehicle SDK 포트 흐름 ----------------
    private class FakePort(var value: Boolean = true, val failFirstQuery: Boolean = false) : VehiclePort<Boolean> {
        var initialized = 0; var released = 0; var unregistered = 0; var queries = 0
        var push: ((Boolean) -> Unit)? = null
        override fun initialize() { initialized++ }
        override fun queryCurrent(onValue: (Boolean) -> Unit, onError: (Throwable) -> Unit) {
            queries++
            if (failFirstQuery && queries == 1) onError(IllegalStateException("service died")) else onValue(value)
        }
        override fun register(onValue: (Boolean) -> Unit, onError: (Throwable) -> Unit): () -> Unit { push = onValue; return { unregistered++ } }
        override fun release() { released++ }
    }

    @Test fun portFlowEmitsInitialPushAndPolledValues_andReleasesOnCancel() = runTest {
        val v = FakePort()
        val out = mutableListOf<Boolean>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            portFlowOf("T", { v }, pollMs = 500, nowMs = { testScheduler.currentTime }).collect { out += it }
        }
        advanceTimeBy(1_100)
        assertEquals("연결 직후 질의 1회 + 500ms 주기 질의 2회", listOf(true, true, true), out)
        v.push!!(false)
        assertEquals(false, out.last())
        assertEquals(1, v.initialized)
        job.cancel(); advanceTimeBy(1)
        assertEquals(1, v.unregistered); assertEquals(1, v.released)
    }

    @Test fun portFlowReplacesConnectionAfterFailure() = runTest {
        val vs = mutableListOf<FakePort>()
        val out = mutableListOf<Boolean>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            portFlowOf("T", { FakePort(value = true, failFirstQuery = vs.isEmpty()).also { vs += it } }, pollMs = 500, nowMs = { testScheduler.currentTime }).collect { out += it }
        }
        advanceTimeBy(10_000)
        assertTrue("연결이 교체돼야 한다: ${vs.size}", vs.size >= 2)
        assertEquals("끊긴 연결은 정리된다", 1, vs[0].released)
        assertTrue(out.isNotEmpty() && out.all { it })
    }

    @Test fun turnSignalMapping() {
        assertEquals(TurnSignal.LEFT, TurnSignalValue.LEFT.toDomain())
        assertEquals(TurnSignal.RIGHT, TurnSignalValue.RIGHT.toDomain())
        assertEquals(TurnSignal.OFF, TurnSignalValue.OFF.toDomain())
    }
}
