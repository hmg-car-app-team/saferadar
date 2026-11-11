package com.hmgcarapp.saferadar

import com.hmgcarapp.core.drive.DriveState
import com.hmgcarapp.saferadar.app.SharePump
import com.hmgcarapp.saferadar.app.ShareInput
import com.hmgcarapp.saferadar.app.UiState
import com.hmgcarapp.saferadar.domain.AnonIdRotator
import com.hmgcarapp.saferadar.domain.Badge
import com.hmgcarapp.saferadar.domain.Candidate
import com.hmgcarapp.saferadar.domain.Direction
import com.hmgcarapp.saferadar.domain.DriveResolver
import com.hmgcarapp.saferadar.domain.DriveSample
import com.hmgcarapp.saferadar.domain.EventDetector
import com.hmgcarapp.saferadar.domain.NearbyVehicle
import com.hmgcarapp.saferadar.domain.NotifText
import com.hmgcarapp.saferadar.domain.RiskGrade
import com.hmgcarapp.saferadar.domain.RiskKind
import com.hmgcarapp.saferadar.domain.SavePolicy
import com.hmgcarapp.saferadar.domain.ShareGate
import com.hmgcarapp.saferadar.domain.ShareStatus
import com.hmgcarapp.saferadar.domain.Trends
import com.hmgcarapp.saferadar.domain.TripRecord
import com.hmgcarapp.saferadar.domain.TripTracker
import com.hmgcarapp.saferadar.domain.TripVoice
import com.hmgcarapp.saferadar.domain.TurnSignal
import com.hmgcarapp.saferadar.domain.WarnEffect
import com.hmgcarapp.saferadar.domain.WarnEvent
import com.hmgcarapp.saferadar.domain.WarnFlow
import com.hmgcarapp.saferadar.net.ShareApi
import com.hmgcarapp.saferadar.domain.Report
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 리뷰 1라운드 수정 항목별 테스트. */
class ReviewFix1Test {
    private var now = 0L
    private fun pump() = SharePump(object : ShareApi {
        override suspend fun report(r: Report) = emptyList<NearbyVehicle>()
        override suspend fun leave(id: String) {}
    }, "https://x.example", AnonIdRotator(monoClock = { now }))
    private fun input(lat: Double? = 37.5, lon: Double? = 127.03, hd: Double? = 90.0) =
        ShareInput(true, false, true, 60f, lat, lon, hd, RiskGrade.LOW, null)
    private fun ghost(gh: String = "wydm6ds", hd: Int = 2) = NearbyVehicle(gh, hd, RiskGrade.HIGH, RiskKind.LANE_CHANGE)

    // 1) 익명 ID 교체 유령
    @Test fun rotationQueuesLeaveForOldId_andSuppressesOwnGhostFor15s() {
        val p = pump()
        val r1 = p.prepare(10_000, input())!!; p.finish(true)
        assertNull("첫 전송에는 지울 이전 ID 가 없다", p.takePendingLeave())
        now = 599_000; p.prepare(604_000, input())!!; p.finish(true) // 교체 6초 전까지 계속 보고(15초 창 안)
        now = 610_000 // 10분 경과 -> ID 교체
        val r2 = p.prepare(610_000, input())!!; p.finish(true)
        assertTrue(r1.id != r2.id)
        assertEquals("교체 직전 ID 로 leave", r1.id, p.takePendingLeave())
        assertNull("한 번만", p.takePendingLeave())
        val other = ghost("wydm6dk", 2)
        assertEquals("서버에 남은 내 이전 항목(같은 셀·방향)은 버리고 다른 차량은 남긴다", listOf(other), p.filterSelf(listOf(ghost(), other), 611_000))
        assertEquals("15초가 지나면 걸러내지 않는다", 2, p.filterSelf(listOf(ghost(), other), 625_001).size)
    }

    @Test fun noSuppressionAtFirstStartOrAfterRevoke_realVehicleWarns() {
        val p = pump()
        p.prepare(10_000, input())!!; p.finish(true)
        assertEquals("최초 시작: 이전 ID 가 없어 걸러낼 대상이 없다", 1, p.filterSelf(listOf(ghost()), 12_000).size)
        assertNotNull(p.revoke())
        p.prepare(20_000, input())!!; p.finish(true) // 재동의
        assertEquals(1, p.filterSelf(listOf(ghost()), 21_000).size)
    }

    @Test fun ghostAfterRotationProducesNoWarning() {
        val p = pump()
        p.prepare(10_000, input())!!; p.finish(true)
        now = 599_000; p.prepare(604_000, input())!!; p.finish(true)
        now = 610_000; p.prepare(610_000, input())!!; p.finish(true)
        val visible = p.filterSelf(listOf(ghost()), 611_000)
        val cands = visible.map { Candidate(Direction.NEAR, it.kind, it.grade, 20.0) }
        val (_, fx) = WarnFlow.reduce(UiState().warn, WarnEvent.Nearby(611_000, cands))
        assertEquals(emptyList<WarnEffect>(), fx)
    }

    // 2) 경고 상태 초기값
    @Test fun startingWhileDrivingStillWarns() {
        val ui = UiState()
        assertEquals(DriveState.DRIVING, ui.drive)
        assertTrue("초기 WarnState 는 drive(DRIVING)와 일치", ui.warn.driving)
        val c = Candidate(Direction.REAR_LEFT, RiskKind.LANE_CHANGE, RiskGrade.HIGH, 120.0)
        val (st, fx) = WarnFlow.reduce(ui.warn, WarnEvent.Nearby(1_000, listOf(c)))
        assertEquals(1, fx.size); assertNotNull(st.banner)
    }

    // 3) 화면 없음(uiDrive=null) 이면 core 판정만
    @Test fun noScreenUsesCoreOnly_tripEndsAndShareStopsWhenCoreParked() {
        assertEquals(DriveState.PARKED, DriveResolver.combine(DriveState.PARKED, null))
        assertEquals(DriveState.DRIVING, DriveResolver.combine(DriveState.PARKED, DriveState.DRIVING))
        assertEquals(DriveState.DRIVING, DriveResolver.combine(DriveState.DRIVING, null))
        val t = TripTracker()
        t.onTick(0, true, 36f, emptyList())
        (1..120).forEach { t.onTick(it * 1_000L, true, 36f, emptyList()) }
        val parked = DriveResolver.combine(DriveState.PARKED, null) == DriveState.DRIVING
        val end = (121..151).mapNotNull { t.onTick(it * 1_000L, parked, 0f, emptyList()) }
        assertEquals("화면이 사라진 뒤 core 가 PARKED 면 주행이 확정된다", 1, end.size)
        assertEquals(ShareStatus.WAITING, ShareGate.status(true, "https://x", false, parked, true, 0f, false))
    }

    // 4) 저장 스레드 안전: IO 람다는 캡처 값만
    @Test fun saveLambdasUseCapturedPendingOnly() {
        val c = File("src/main/java/com/hmgcarapp/saferadar/app/RadarController.kt").readText()
        assertFalse("persistedCopy() 무인자 호출 금지", c.contains("persistedCopy()"))
        assertTrue(c.contains("private fun persistedCopy(pending: TripSnapshot?)"))
        Regex("""(writeLatest|clearAll)\s*\{[^}]*\}""").findAll(c).forEach { assertFalse("저장 람다에서 tracker 직접 접근 금지: ${it.value}", it.value.contains("tracker")) }
        assertEquals("tracker.snapshot() 은 capturePending 한 곳에서만", 1, Regex("tracker\\.snapshot\\(\\)").findAll(c).count())
    }

    // 5) 알림 문구
    @Test fun notificationTextSaysSharingOnlyWhenSharing() {
        assertEquals("익명 위치(약 150m 격자)를 근접 경고용으로 공유 중이에요", NotifText.body(ShareStatus.ACTIVE))
        assertEquals(NotifText.SHARING, NotifText.body(ShareStatus.ERROR))
        listOf(ShareStatus.NO_CONSENT, ShareStatus.NO_SERVER, ShareStatus.DEMO, ShareStatus.WAITING).forEach { assertEquals("안전운전 점수를 이 기기에서 계산하고 있어요", NotifText.body(it)) }
    }

    @Test fun privacyNoticesAreCorrected() {
        val ui = File("src/main/java/com/hmgcarapp/saferadar/ui/Screens.kt").readText()
        assertTrue(ui.contains("약 2분 10초"))
        // 단독 fork 에는 앱 모듈 README 가 없다(루트 README 로 합쳐짐) — 모노레포에서만 검사
        File("README.md").takeIf { it.exists() }?.readText()?.let { readme ->
            assertTrue(readme.contains(com.hmgcarapp.saferadar.domain.PrivacyText.ROUTE))
            assertTrue(readme.contains("재동의가 필요하지만 이 버전에는 구현돼 있지 않다"))
        }
    }

    // 8) 지시등 소스 끊김
    private fun s(t: Long, turn: TurnSignal?, steer: Float) = DriveSample(t, 80f, null, turn, steer)
    @Test fun turnSignalDropoutSuspendsLaneJudgement() {
        val d = EventDetector()
        val out = listOf(s(0, TurnSignal.OFF, 0f), s(500, TurnSignal.OFF, 0f), s(1_000, null, 4f), s(1_500, null, -4f), s(2_000, TurnSignal.OFF, 0f), s(2_500, TurnSignal.OFF, 0f)).flatMap { d.feed(it) }
        assertEquals("끊긴 동안의 흔들림은 판단하지 않고 복구 뒤에도 되살리지 않는다", emptyList<Any>(), out)
        val again = listOf(s(3_000, TurnSignal.OFF, 4f), s(3_500, TurnSignal.OFF, -4f)).flatMap { d.feed(it) }
        assertEquals(listOf(RiskKind.LANE_CHANGE), again.map { it.kind })
    }

    // 10) 시연 음성·표기
    @Test fun voiceLinePrefixesDemo() {
        assertEquals("오늘 안전점수 72점, 위험 이벤트 5회입니다.", TripVoice.line(72, 5, false))
        assertEquals("시연입니다. 오늘 안전점수 72점, 위험 이벤트 5회입니다.", TripVoice.line(72, 5, true))
        assertEquals("시연입니다. 이번 주행은 짧아 점수를 계산하지 않았어요.", TripVoice.line(null, 0, true))
    }

    // 11) 고속 지속 경로
    private fun v(d: EventDetector, fromS: Int, toS: Int, kmh: Float) = (fromS..toS).flatMap { d.feed(DriveSample(it * 1_000L, kmh)) }
    @Test fun highSpeedDropBelow130BreaksTheRun() {
        val d = EventDetector()
        val a = v(d, 0, 0, 130f) + v(d, 1, 9, 125f) + v(d, 10, 20, 131f)
        assertEquals("130 1초 -> 125 9초 -> 131 은 10초 지속이 아니다(131 지속은 10에서 시작해 20초에 발생)", listOf(20_000L), a.map { it.tMs })
        val d2 = EventDetector()
        assertEquals(emptyList<Any>(), v(d2, 0, 0, 130f) + v(d2, 1, 9, 125f) + v(d2, 10, 19, 131f))
    }

    // 12) 연속 배지는 최근 3건
    @Test fun streakUsesLatestThreeOnly() {
        val day = Trends.DAY_MS
        fun t(i: Int, sc: Int) = TripRecord(20_000L * day + i * 3_600_000L, sc, 10f, 20, listOf(0, 0, 0, 0, 0))
        val old = listOf(t(1, 90), t(2, 90), t(3, 90), t(4, 30), t(5, 20), t(6, 40))
        assertFalse(Badge.STREAK_3 in Trends.badges(old, 20_000L * day + 10 * 3_600_000L, 0))
        assertTrue(Badge.STREAK_3 in Trends.badges(listOf(t(0, 10), t(1, 80), t(2, 85), t(3, 90)), 20_000L * day + 10 * 3_600_000L, 0))
    }

    // 13) prepare 입력 검증
    @Test fun invalidCoordinatesNeverLeaveSendingState() {
        val p = pump()
        listOf(input(lat = Double.NaN), input(lat = 91.0), input(lon = -181.0), input(lon = Double.POSITIVE_INFINITY), input(hd = Double.NaN)).forEach {
            assertNull(p.prepare(10_000, it))
        }
        assertNotNull("검증 실패가 전송 중 상태를 남기지 않는다", p.prepare(10_000, input()))
    }

    // 14) 주기 저장 정책
    @Test fun periodicSaveOnlyWithActiveRealTrip() {
        assertTrue(SavePolicy.periodic(tripActive = true, demo = false))
        assertFalse(SavePolicy.periodic(tripActive = false, demo = false))
        assertFalse(SavePolicy.periodic(tripActive = true, demo = true))
    }
}
