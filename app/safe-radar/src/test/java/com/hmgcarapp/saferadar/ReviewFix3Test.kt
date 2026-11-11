package com.hmgcarapp.saferadar

import com.hmgcarapp.saferadar.app.SharePump
import com.hmgcarapp.saferadar.app.ShareInput
import com.hmgcarapp.saferadar.domain.AnonIdRotator
import com.hmgcarapp.saferadar.domain.NearbyVehicle
import com.hmgcarapp.saferadar.domain.PrivacyText
import com.hmgcarapp.saferadar.domain.Report
import com.hmgcarapp.saferadar.domain.RiskGrade
import com.hmgcarapp.saferadar.domain.RiskKind
import com.hmgcarapp.saferadar.net.ShareApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class ReviewFix3Test {
    private class Api : ShareApi {
        val leaves = mutableListOf<String>(); var throwOnReport = false; var onReport: (() -> Unit)? = null
        override suspend fun report(r: Report): List<NearbyVehicle> { onReport?.invoke(); if (throwOnReport) throw java.io.IOException("x"); return emptyList() }
        override suspend fun leave(id: String) { leaves += id }
    }
    private var now = 0L
    private fun pump(api: Api) = SharePump(api, "https://x.example", AnonIdRotator(monoClock = { now }))
    private fun input(lat: Double = 37.5) = ShareInput(true, false, true, 60f, lat, 127.03, 90.0, RiskGrade.LOW, null)
    private fun src(p: String) = File("src/main/java/com/hmgcarapp/saferadar/$p").readText()

    // 1) 철회 즉시 저장
    @Test fun revocationIsPersistedWithoutDebounce() {
        val c = src("app/RadarController.kt")
        val revoke = c.substringAfter("private fun revokeLocal()").substringBefore("/** 서버에 남은 항목")
        assertTrue("revokeLocal 이 즉시 저장을 부른다", revoke.contains("persistRevocationNow()"))
        val persist = revoke.substringAfter("private fun persistRevocationNow()")
        assertTrue(persist.contains("saveJob?.cancel()") && persist.contains("NonCancellable") && persist.contains("stateStore.writeLatest"))
        assertFalse("디바운스 없음", persist.contains("delay(") || persist.contains("scheduleSave"))
        assertTrue(persist.contains("WriteResult.FAILED") && persist.contains("WriteResult.HELD") && persist.contains("PrivacyText.REVOKE_NOT_SAVED"))
        // 두 철회 경로 모두 revokeLocal 을 거친다
        assertTrue(c.contains("if (!agree) revokeLocal()") && c.contains("if (!on) revokeLocal()"))
    }

    @Test fun revocationFailureMessageIsHonest() {
        assertEquals("동의 철회가 저장되지 않았어요. 앱을 다시 켜면 동의가 복원될 수 있어요", PrivacyText.REVOKE_NOT_SAVED)
    }

    // 2) 오래된 셀은 prevOwn 에 넣지 않는다
    @Test fun staleCellsAreDroppedBeforeRotation() {
        val p = pump(Api())
        p.prepare(10_000, input(37.5))!!; p.finish(true) // 셀 A
        now = 610_000                                      // 15초 넘게 정차 후 10분 경과 -> 교체
        val r2 = p.prepare(610_000, input(37.51))!!; p.finish(true)
        val realInA = NearbyVehicle("wydm6ds", 2, RiskGrade.HIGH, RiskKind.LANE_CHANGE)
        assertEquals("15초 창 밖의 셀 A 는 걸러내기 대상이 아니라 같은 셀·방향 실차량도 경고된다", listOf(realInA), p.filterSelf(listOf(realInA), 611_000))
        assertNotNull(r2)
    }

    @Test fun recentCellsStillFilteredWhenRotating() {
        val p = pump(Api())
        p.prepare(595_000, input(37.5))!!; p.finish(true) // 교체 15초 이내 보고
        now = 610_000
        p.prepare(610_000, input(37.51))!!; p.finish(true)
        val ghost = NearbyVehicle("wydm6ds", 2, RiskGrade.HIGH, RiskKind.LANE_CHANGE)
        assertEquals(emptyList<NearbyVehicle>(), p.filterSelf(listOf(ghost), 611_000))
    }

    // 3) 예외 + 철회 -> leave
    @Test fun reportExceptionAfterRevokeStillSendsLeave() = runTest {
        val api2 = Api().also { it.throwOnReport = true }; val p2 = pump(api2)
        val r2 = p2.prepare(10_000, input())!!
        val gen = p2.generation
        api2.onReport = { p2.revoke() }
        assertEquals(SharePump.SendResult.Failed, p2.send(r2, gen) { true })
        assertEquals(listOf(r2.id), api2.leaves)
    }

    @Test fun reportExceptionWithoutRevokeSendsNoLeave() = runTest {
        val api = Api().also { it.throwOnReport = true }; val p = pump(api)
        val r = p.prepare(10_000, input())!!
        assertEquals(SharePump.SendResult.Failed, p.send(r, p.generation) { true })
        assertTrue(api.leaves.isEmpty())
    }

    // 4·5) 고지 문구
    @Test fun consentTextsMatchTabAndItems() {
        assertTrue(PrivacyText.CONSENT_OUTRO.startsWith("근접 경고 탭에서 언제든 끌 수 있어요"))
        assertFalse(PrivacyText.CONSENT_BODY.contains("설정에서 언제든"))
        assertTrue(PrivacyText.CONSENT_INTRO.contains("위치(약 150m 격자)·진행 방향·위험 등급과 주된 위험 유형을"))
        File("README.md").takeIf { it.exists() }?.readText()?.let { readme ->
            assertTrue(readme.contains("근접 경고 탭의 스위치를 끄면") && !readme.contains("설정/근접 경고 탭"))
            assertTrue(readme.contains("주된 유형 코드"))
        }
        assertTrue(src("ui/Screens.kt").contains("주된"))
    }
}
