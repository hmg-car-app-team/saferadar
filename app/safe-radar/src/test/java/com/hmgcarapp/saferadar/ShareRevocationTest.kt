package com.hmgcarapp.saferadar

import com.hmgcarapp.saferadar.app.SharePump
import com.hmgcarapp.saferadar.app.ShareInput
import com.hmgcarapp.saferadar.domain.AnonIdRotator
import com.hmgcarapp.saferadar.domain.NearbyVehicle
import com.hmgcarapp.saferadar.domain.PrivacyText
import com.hmgcarapp.saferadar.domain.Report
import com.hmgcarapp.saferadar.domain.RiskGrade
import com.hmgcarapp.saferadar.domain.RiskKind
import com.hmgcarapp.saferadar.domain.TripTracker
import com.hmgcarapp.saferadar.net.ShareApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 공유 철회 시 전송 생략과 leave 보장 테스트. */
@OptIn(ExperimentalCoroutinesApi::class)
class ShareRevocationTest {
    private class Api : ShareApi {
        val reports = mutableListOf<Report>(); val leaves = mutableListOf<String>()
        var onReport: (() -> Unit)? = null; var leaveDelayMs = 0L
        override suspend fun report(r: Report): List<NearbyVehicle> { onReport?.invoke(); reports += r; return emptyList() }
        override suspend fun leave(id: String) { if (leaveDelayMs > 0) delay(leaveDelayMs); leaves += id }
    }
    private var now = 0L
    private fun pump(api: Api) = SharePump(api, "https://x.example", AnonIdRotator(monoClock = { now }))
    private fun input(lat: Double = 37.5, lon: Double = 127.03) = ShareInput(true, false, true, 60f, lat, lon, 90.0, RiskGrade.LOW, null)
    private fun veh(gh: String) = NearbyVehicle(gh, 2, RiskGrade.HIGH, RiskKind.LANE_CHANGE)
    private fun src(p: String) = File("src/main/java/com/hmgcarapp/saferadar/$p").readText()

    // 1) 철회 경합
    @Test fun revokeBetweenPrepareAndSendMeansNothingIsSent() = runTest {
        val api = Api(); val p = pump(api)
        val r = p.prepare(10_000, input())!!
        val gen = p.generation
        p.revoke()
        assertEquals(SharePump.SendResult.Skipped, p.send(r, gen) { true })
        assertTrue("report 미전송", api.reports.isEmpty())
        p.finish(false, gen)
        assertFalse("낡은 세대의 실패는 상태에 남기지 않는다", p.failed)
    }

    @Test fun consentOffAtSendTimeAlsoSkips() = runTest {
        val api = Api(); val p = pump(api)
        val r = p.prepare(10_000, input())!!
        assertEquals(SharePump.SendResult.Skipped, p.send(r, p.generation) { false })
        assertTrue(api.reports.isEmpty())
    }

    @Test fun revokeRightAfterResponseResendsLeaveForSameId() = runTest {
        val api = Api(); val p = pump(api)
        val r = p.prepare(10_000, input())!!
        api.onReport = { p.revoke() } // report 처리 도중 철회
        val res = p.send(r, p.generation) { true }
        assertEquals(1, api.reports.size)
        assertTrue((res as SharePump.SendResult.Ok).revoked)
        assertEquals("같은 ID 로 leave 재전송", listOf(r.id), api.leaves)
    }

    @Test fun leaveSurvivesCallerCancellation_butIsBoundedBy3s() = runTest {
        val api = Api().also { it.leaveDelayMs = 1_000 }; val p = pump(api)
        val job = launch { p.leave("abc") }
        runCurrent(); job.cancel()
        advanceUntilIdle()
        assertEquals("호출자가 취소돼도 끝까지", listOf("abc"), api.leaves)
        val slow = Api().also { it.leaveDelayMs = 10_000 }; val p2 = pump(slow)
        val j2 = launch { p2.leave("zzz") }
        runCurrent(); advanceTimeBy(3_001)
        assertTrue("3초 제한", j2.isCompleted); assertTrue(slow.leaves.isEmpty())
    }

    @Test fun controllerUsesNonCancelledLeaveScope() {
        val c = src("app/RadarController.kt")
        assertTrue(c.contains("leaveScope.launch"))
        assertTrue(src("app/SharePump.kt").contains("withContext(NonCancellable)"))
    }

    // 2) 고지 문구 한 곳
    @Test fun consentPopupUsesShortConstants_readmeKeepsRouteSentences() {
        val ui = src("ui/Screens.kt")
        assertTrue(ui.contains("PrivacyText.CONSENT_PARAGRAPHS.forEach")) // 팝업은 description(3줄 말줄임)이 아닌 content 슬롯
        assertFalse("근접 경고 탭에 별도 고지 패널을 두지 않는다", ui.contains("SHARE_PAGE_BODY") || ui.contains("서버로 보내는 것") || ui.contains("보내지 않는 것"))
        assertFalse("화면에 고지 문장을 직접 쓰지 않는다(상수만)", ui.contains("접속 IP") || ui.contains("로컬 테스트 서버"))
        File("README.md").takeIf { it.exists() }?.readText()?.let { readme ->
            assertTrue(readme.contains(PrivacyText.ROUTE)); assertTrue(readme.contains(PrivacyText.SERVER))
        }
    }

    // 3) filterSelf: 새 ID 보고는 걸러내지 않는다
    @Test fun afterRotationOnlyOldIdCellsAreFiltered_realVehicleInNewCellWarns() {
        val api = Api(); val p = pump(api)
        p.prepare(10_000, input())!!; p.finish(true)               // 이전 ID, 셀 A(wydm6ds)
        now = 599_000; p.prepare(604_000, input())!!; p.finish(true)
        now = 610_000
        val r2 = p.prepare(610_000, input(lat = 37.51))!!; p.finish(true) // 새 ID, 셀 B
        val a = veh("wydm6ds"); val b = veh(r2.geohash)
        assertEquals("이전 ID 잔상(셀 A)은 걸러내고 새 셀 B 의 진짜 차량은 남긴다", listOf(b), p.filterSelf(listOf(a, b), 611_000))
        assertEquals(2, p.filterSelf(listOf(a, b), 625_001).size)
    }

    // 4) 데이터 삭제는 진행 중 주행도 버린다
    @Test fun clearAllDiscardsActiveTripAndPending() {
        val t = TripTracker()
        t.onTick(0, true, 36f, emptyList())
        (1..120).forEach { t.onTick(it * 1_000L, true, 36f, emptyList()) }
        (121..140).forEach { t.onTick(it * 1_000L, false, 0f, emptyList()) } // 주차 20초
        assertNotNull(t.snapshot())
        assertNotNull(t.finishNow()) // clearAllData 가 결과를 버린다
        assertNull("삭제 뒤 pending 스냅샷 없음", t.snapshot())
        assertEquals("30초가 지나도 기록이 부활하지 않는다", emptyList<Any>(), (141..180).mapNotNull { t.onTick(it * 1_000L, false, 0f, emptyList()) })
        val c = src("app/RadarController.kt")
        val clear = c.substringAfter("fun clearAllData()").substringBefore("// ----------")
        assertTrue(clear.indexOf("tracker.finishNow()") in 0 until clear.indexOf("capturePending()"))
    }

    // 5) 소스 정독 단언
    @Test fun sourceLinesPinned() {
        val c = src("app/RadarController.kt")
        assertTrue(c.contains("if (SavePolicy.periodic(tracker.isActive, demoJob != null)) scheduleSave()"))
        assertTrue(c.contains("private var uiDrive: DriveState? = null"))
        assertTrue(c.contains("DriveResolver.combine(coreDrive, uiDrive)"))
    }
}
