package com.hmgcarapp.saferadar.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import com.hmgcarapp.core.drive.DriveState
import com.hmgcarapp.core.drive.Gear
import com.hmgcarapp.core.ui.rememberDriveSnapshot
import com.hmgcarapp.pleos.designsystem.component.PleosButton
import com.hmgcarapp.pleos.designsystem.component.PleosButtonHierarchy
import com.hmgcarapp.pleos.designsystem.component.PleosListItem
import com.hmgcarapp.pleos.designsystem.component.PleosPanel
import com.hmgcarapp.pleos.designsystem.component.PleosPopup
import com.hmgcarapp.pleos.designsystem.component.PleosScaffold
import com.hmgcarapp.pleos.designsystem.component.PleosSegmentedTabs
import com.hmgcarapp.pleos.designsystem.component.PleosStatus
import com.hmgcarapp.pleos.designsystem.component.PleosStatusBadge
import com.hmgcarapp.pleos.designsystem.component.PleosSwitch
import com.hmgcarapp.pleos.designsystem.component.rememberPleosToastState
import com.hmgcarapp.pleos.designsystem.theme.PleosSpacing
import com.hmgcarapp.pleos.designsystem.theme.PleosTheme
import com.hmgcarapp.pleos.designsystem.theme.PleosWindowMode
import com.hmgcarapp.saferadar.app.Page
import com.hmgcarapp.saferadar.app.RadarController
import com.hmgcarapp.saferadar.app.TripReport
import com.hmgcarapp.saferadar.app.UiState
import com.hmgcarapp.saferadar.domain.Badge
import com.hmgcarapp.saferadar.domain.PrivacyText
import com.hmgcarapp.saferadar.domain.ShareStatus
import com.hmgcarapp.saferadar.domain.Trends
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
private fun Txt(t: String, style: TextStyle = PleosTheme.typography.bodyMedium, secondary: Boolean = false, modifier: Modifier = Modifier) =
    Text(t, style = style, color = if (secondary) PleosTheme.colors.textDarkSecondary else PleosTheme.colors.textDarkPrimary, modifier = modifier)

private fun shareStatusKind(s: ShareStatus) = when (s) {
    ShareStatus.ACTIVE -> PleosStatus.Positive
    ShareStatus.ERROR -> PleosStatus.Negative
    ShareStatus.DEMO -> PleosStatus.Autonomous
    else -> PleosStatus.Neutral
}

@Composable
fun SafeRadarApp(c: RadarController) {
    val s by c.state.collectAsState()
    val snap = rememberDriveSnapshot(c.signals)
    LaunchedEffect(snap.state) { c.onUiDriveState(snap.state) }
    DisposableEffect(Unit) { onDispose { c.onUiDriveState(null) } }
    // 첫 프레임에 정차 UI 가 잘못 보이지 않게 화면 쪽 스냅샷(초기값 DRIVING)과 컨트롤러 판정 중 하나라도 주행이면 주행 화면
    val driving = snap.state == DriveState.DRIVING || s.drive == DriveState.DRIVING
    val toast = rememberPleosToastState()
    // 안내 토스트는 정차 시에만. 근접 경고는 아래 상단 배너(주행 중에도 표시)가 맡는다.
    LaunchedEffect(s.message, driving) { if (!driving) s.message?.let { toast.show(it); c.clearMessage() } }
    LaunchedEffect(driving) { if (driving) toast.dismiss() }
    val notice = if (snap.inputs.gear == Gear.UNKNOWN && snap.inputs.uxRequiresDistractionOptimization == null) "차량 상태를 확인하지 못하면 주행 중으로 간주해요" else null
    val paneWidth = PleosTheme.window.appLandscapeWidth * 0.4f

    if (!driving && !s.loading && !s.p.settings.consentAsked && !s.demo) ConsentPopup(c)

    PleosScaffold(
        title = "세이프레이더",
        subtitle = (s.report?.summary?.score?.let { (if (s.report?.demo == true) "시연 점수 " else "최근 점수 ") + it } ?: "점수 준비 중") + " · " + (if (driving) "주행 중" else "정차 중"),
        toastState = toast,
        actions = { if (s.demo) PleosStatusBadge("시연", PleosStatus.Autonomous, Modifier.testTag("sim_badge")) },
        supportingPane = if (driving || s.loading) null else { m -> Column(m.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(PleosSpacing.m)) { Summary(s) } },
        supportingPaneWidth = paneWidth,
    ) { mode ->
        when {
            driving -> DrivingView(s, notice)
            s.loading -> PleosPanel(Modifier.fillMaxWidth()) { Txt("불러오는 중…", secondary = true, modifier = Modifier.testTag("loading")) }
            else -> Parked(c, s, mode)
        }
    }
}

/** 첫 실행(정차 중) 옵트인 동의. 기본은 '동의하지 않음'이며 어느 쪽을 골라도 다시 묻지 않는다. */
@Composable
private fun ConsentPopup(c: RadarController, onDone: (() -> Unit)? = null, onAgree: () -> Unit = { c.answerConsent(true) }) {
    PleosPopup(
        title = "근접 위험 경고를 켤까요?",
        confirmLabel = "동의하고 켜기", onConfirm = { onAgree(); onDone?.invoke() },
        onDismissRequest = { if (onDone != null) onDone() else c.postponeConsent() }, cancelLabel = if (onDone != null) "취소" else "나중에",
        // description 은 maxLines=3 으로 말줄임되므로 쓰지 않고 content 슬롯에 전체 고지를 문단별로 넣는다(본문 높이를 팝업 최대 높이의 절반으로 제한하고 넘치면 본문만 스크롤해 버튼이 밀리지 않게 한다(ui 에서 weight 금지 규칙))
        content = {
            Column(Modifier.heightIn(max = PleosTheme.dimensions.popupMaxHeight * 0.5f).verticalScroll(rememberScrollState()).testTag("consent_body"), verticalArrangement = Arrangement.spacedBy(PleosSpacing.s)) {
                PrivacyText.CONSENT_PARAGRAPHS.forEach { Txt(it, PleosTheme.typography.bodyMedium, secondary = true) }
            }
        },
    )
}

// ---------------- 주행 중: 읽기 전용 한 화면(조작 요소 없음) ----------------
@Composable
private fun DrivingView(s: UiState, notice: String?) {
    Column(Modifier.fillMaxSize().testTag("driving_view"), verticalArrangement = Arrangement.spacedBy(PleosSpacing.m)) {
        // 상단 배너(3초): 근접 경고. 디자인시스템에 배너 컴포넌트가 없어 PleosPanel 로 대체(docs/apps/safe-radar.md 기록)
        s.warn.banner?.let { b ->
            PleosPanel(Modifier.fillMaxWidth().testTag("warn_banner"), title = "근접 경고") {
                Txt(b.text, PleosTheme.typography.titleMedium, modifier = Modifier.testTag("banner_msg"))
            }
        }
        PleosPanel(Modifier.fillMaxWidth()) {
            Txt("이번 주행", PleosTheme.typography.bodyMedium, secondary = true)
            Txt(s.live.score?.toString() ?: "–", PleosTheme.typography.headlineLarge, modifier = Modifier.testTag("live_score"))
            Txt(if (s.live.active) "위험 이벤트 ${s.live.events}회 · ${s.live.minutes}분 · ${String.format(Locale.KOREA, "%.1f", s.live.km)}km" else "주행 시작을 기다리는 중", PleosTheme.typography.bodyLarge, secondary = true, modifier = Modifier.testTag("live_detail"))
            Txt(s.share.label, PleosTheme.typography.bodyMedium, secondary = true, modifier = Modifier.testTag("share_status"))
            notice?.let { Txt(it, PleosTheme.typography.bodyMedium, secondary = true, modifier = Modifier.testTag("driving_notice")) }
            if (s.demo) Txt("시연 중: 가상 차량과 가상 신호예요", PleosTheme.typography.labelMedium, secondary = true)
        }
    }
}

// ---------------- 정차 ----------------
@Composable
private fun Summary(s: UiState) {
    PleosPanel(Modifier.fillMaxWidth(), title = if (s.report?.demo == true) "시연 주행 점수(가상)" else "최근 주행 점수") {
        Txt(s.report?.summary?.score?.toString() ?: "–", PleosTheme.typography.headlineMedium, modifier = Modifier.testTag("report_score"))
        PleosStatusBadge(s.share.label, shareStatusKind(s.share), Modifier.testTag("share_badge"))
        Txt("차량 신호 기반 추정이며 임계값은 미검증 초기값이에요", PleosTheme.typography.labelMedium, secondary = true)
    }
}

@Composable
private fun Parked(c: RadarController, s: UiState, mode: PleosWindowMode) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(PleosSpacing.m)) {
        if (mode == PleosWindowMode.Portrait) Summary(s)
        PleosSegmentedTabs(
            items = Page.entries.map { it.label }, selectedIndex = s.page.ordinal,
            onSelect = { c.setPage(Page.entries[it]) }, modifier = Modifier.fillMaxWidth().testTag("page_tabs"),
        )
        when (s.page) {
            Page.TODAY -> TodayPage(s)
            Page.WEEK -> WeekPage(c, s)
            Page.SHARE -> SharePage(c, s)
            Page.SETTINGS -> SettingsPage(c, s)
        }
    }
}

@Composable
private fun TodayPage(s: UiState) {
    val r: TripReport? = s.report
    PleosPanel(Modifier.fillMaxWidth(), title = "주행 리포트") {
        if (r == null) Txt("아직 주행 기록이 없어요. 주행을 마치고 주차(P단)하면 리포트가 만들어져요.", secondary = true, modifier = Modifier.testTag("no_report"))
        else {
            if (r.demo) PleosStatusBadge("시연 데이터(저장하지 않음)", PleosStatus.Autonomous)
            val sc = r.summary
            Txt(sc.score?.let { "오늘의 안전점수 ${it}점" } ?: "너무 짧은 주행이라 점수를 계산하지 않았어요", PleosTheme.typography.titleMedium, modifier = Modifier.testTag("report_title"))
            Txt("${String.format(Locale.KOREA, "%.1f", sc.distanceKm)}km · ${sc.durationMin}분 · 위험 이벤트 ${sc.totalEvents}회", secondary = true)
            com.hmgcarapp.saferadar.domain.RiskKind.entries.filter { sc.count(it) > 0 }.forEach { k ->
                PleosListItem(title = k.label, trailing = { Txt("${sc.count(k)}회", modifier = Modifier.testTag("count_${k.name}")) })
            }
            Txt("개선 팁: ${sc.tip}", modifier = Modifier.testTag("report_tip"))
        }
    }
    if (r != null && r.summary.deductions.isNotEmpty()) PleosPanel(Modifier.fillMaxWidth(), title = "감점 구간") {
        r.summary.deductions.take(5).forEach { d -> PleosListItem(title = "주행 ${d.atMin}분 무렵", description = d.kind.label) }
        if (r.summary.deductions.size > 5) Txt("그 밖에 ${r.summary.deductions.size - 5}건", PleosTheme.typography.labelMedium, secondary = true)
    }
    PleosPanel(Modifier.fillMaxWidth(), title = "받고 있는 차량 신호") {
        val a = s.signals
        fun mark(ok: Boolean) = if (ok) "수신 중" else "없음"
        Txt("차속 ${mark(a.speed)} · 위치 ${mark(a.location)} · 브레이크 ${mark(a.brake)} · 방향지시등 ${mark(a.turn)} · 조향각 ${mark(a.steer)}", PleosTheme.typography.labelMedium, secondary = true, modifier = Modifier.testTag("signals_line"))
        Txt("방향지시등 신호가 없으면 차로변경 이벤트는 판단하지 않아요. 급감속은 브레이크 신호가 있으면 함께 확인해요. 가속페달 신호는 이 차량 플랫폼에 없어 속도 변화로 급가속을 추정해요.", PleosTheme.typography.labelMedium, secondary = true)
    }
}

@Composable
private fun WeekPage(c: RadarController, s: UiState) {
    val now = c.nowWall(); val tz = c.tzOffsetMs()
    val trips = s.p.trips
    val avg = Trends.weekAverage(trips, now, tz)
    val delta = Trends.weekDelta(trips, now, tz)
    val fmt = remember { SimpleDateFormat("M/d (E)", Locale.KOREA) }
    PleosPanel(Modifier.fillMaxWidth(), title = "이번 주 추이") {
        Txt(avg?.let { "이번 주 평균 ${it}점 (${Trends.weekCount(trips, now, tz)}회)" } ?: "이번 주 기록이 없어요", PleosTheme.typography.titleMedium, modifier = Modifier.testTag("week_avg"))
        Txt(delta?.let { if (it >= 0) "지난주보다 ${it}점 올랐어요" else "지난주보다 ${-it}점 내려갔어요" } ?: "지난주 기록이 없어 비교할 수 없어요", secondary = true, modifier = Modifier.testTag("week_delta"))
        Trends.last7Days(trips, now, tz).asReversed().forEach { d ->
            PleosListItem(
                title = fmt.format(Date(d.dayIndex * Trends.DAY_MS - tz + Trends.DAY_MS / 2)),
                trailing = { Txt(d.avg?.let { "${it}점 · ${d.trips}회" } ?: "기록 없음", secondary = d.avg == null) },
            )
        }
    }
    PleosPanel(Modifier.fillMaxWidth(), title = "배지") {
        val earned = Trends.badges(trips, now, tz)
        Badge.entries.forEach { b ->
            PleosListItem(
                title = b.label, description = b.how,
                trailing = { PleosStatusBadge(if (b in earned) "획득" else "미획득", if (b in earned) PleosStatus.Positive else PleosStatus.Neutral, Modifier.testTag("badge_${b.name}")) },
            )
        }
    }
}

@Composable
private fun SharePage(c: RadarController, s: UiState) {
    val st = s.p.settings
    var confirmConsent by remember { mutableStateOf(false) }
    if (confirmConsent && !s.p.settings.consent) ConsentPopup(c, onDone = { confirmConsent = false }, onAgree = { c.setConsent(true) })
    PleosPanel(Modifier.fillMaxWidth(), title = "근접 위험 경고(익명 공유)") {
        PleosStatusBadge(s.share.label, shareStatusKind(s.share), Modifier.testTag("share_badge_page"))
        PleosSwitch(checked = st.consent, onCheckedChange = { if (it) confirmConsent = true else c.setConsent(false) }, label = "내 대략 위치를 익명으로 공유", modifier = Modifier.testTag("set_consent"))
        Txt(
            when {
                !st.consent -> "꺼져 있어요(기본). 켜기 전에는 위치를 어디로도 보내지 않아요."
                !s.serverConfigured -> "켜 두었지만 이 빌드에는 서버 주소가 없어 아무것도 보내지 않아요. 시연으로 전체 흐름을 볼 수 있어요."
                else -> "주행 중(시속 20km 이상)에만 3초마다 보내요. 끄면 즉시 멈추고 서버에 남은 내 항목도 지우도록 요청해요."
            },
            PleosTheme.typography.labelMedium, secondary = true, modifier = Modifier.testTag("consent_note"),
        )
    }
    PleosPanel(Modifier.fillMaxWidth(), title = "서버로 보내는 것") {
        PleosListItem(title = "익명 번호", description = "10분마다 바뀌는 무작위 값(기기·계정과 무관, 저장하지 않음)")
        PleosListItem(title = "대략 위치", description = "약 150m 격자(Geohash 7자리). 정확한 좌표는 보내지 않아요")
        PleosListItem(title = "진행 방향", description = "8방위 중 하나")
        PleosListItem(title = "위험 등급·유형", description = "최근 10분 이벤트로 계산한 등급(양호/주의/위험)과 주된 유형")
    }
    PleosPanel(Modifier.fillMaxWidth(), title = "보내지 않는 것") {
        Txt(PrivacyText.SHARE_PAGE_BODY, secondary = true)
    }
}

@Composable
private fun SettingsPage(c: RadarController, s: UiState) {
    val st = s.p.settings
    PleosPanel(Modifier.fillMaxWidth(), title = "안내") {
        PleosSwitch(checked = st.voiceWarn, onCheckedChange = { c.setVoiceWarn(it) }, label = "음성 경고·점수 안내", modifier = Modifier.testTag("set_voice"))
        Txt("경고는 음성과 화면 상단 3초 배너로만 알려요. 주행 중에는 화면을 조작할 필요가 없어요.", PleosTheme.typography.labelMedium, secondary = true)
        PleosSwitch(checked = st.background, onCheckedChange = { c.setBackground(it) }, label = "백그라운드 수집(앱 실행 후)", modifier = Modifier.testTag("set_background"))
        Txt("켜면 앱을 닫아도 알림이 표시되는 동안 이 기기에서 점수를 계산해요. 시동과 함께 자동 실행되지는 않아요.", PleosTheme.typography.labelMedium, secondary = true)
    }
    PleosPanel(Modifier.fillMaxWidth(), title = "시연 모드") {
        Txt("가상 차량과 가상 주행 신호로 약 2분 10초 동안 급감속·차로변경·근접 경고·리포트까지 한 번에 보여줘요. 서버로 보내지 않고 기록에도 남기지 않아요.", PleosTheme.typography.labelMedium, secondary = true)
        PleosButton(if (s.demo) "시연 중…" else "시연 시작", onClick = { c.startDemo() }, enabled = !s.demo, modifier = Modifier.testTag("btn_demo"))
    }
    PleosPanel(Modifier.fillMaxWidth(), title = "저장된 기록과 개인정보") {
        Txt("이 기기 안에만 저장해요: 주행별 점수·거리·시간·이벤트 횟수, 설정(평문). 위치·경로·익명 번호·음성은 저장하지 않아요.", PleosTheme.typography.labelMedium, secondary = true)
        if (s.corruptKept) Txt("저장 파일이 손상돼 처음 상태로 시작했어요. 아래 삭제로 지울 수 있어요.", PleosTheme.typography.labelMedium, secondary = true, modifier = Modifier.testTag("corrupt_kept"))
        if (s.saveHeld) Txt("저장하지 못해 이번 실행에서는 저장하지 않아요. 앱을 다시 시작하거나 삭제하면 다시 저장해요.", PleosTheme.typography.labelMedium, secondary = true, modifier = Modifier.testTag("save_held"))
        var ask by remember { mutableStateOf(false) }
        PleosButton("저장된 주행 기록 모두 삭제", onClick = { ask = true }, hierarchy = PleosButtonHierarchy.Secondary, modifier = Modifier.testTag("clear_all"))
        if (ask) PleosPopup(
            title = "저장된 주행 기록을 모두 삭제할까요?",
            description = "점수·주간 추이·배지가 삭제돼요.\n되돌릴 수 없어요. 공유 동의 설정은 그대로예요.",
            confirmLabel = "삭제", onConfirm = { ask = false; c.clearAllData() },
            onDismissRequest = { ask = false }, cancelLabel = "취소",
        )
    }
    PleosPanel(Modifier.fillMaxWidth(), title = "알아두세요") {
        Txt("점수 가중치와 이벤트 임계값은 실차 검증 전의 초기값이에요. 제한속도 데이터가 없어 과속은 '급격한 고속 진입/고속 지속'으로 대신해요. 근접 경고는 주변 차량 위치를 약 150m 격자로만 알 수 있어 차로 단위 정확도는 아니에요. 이 앱은 운전 보조 도구이며 전방 주시와 안전 운전은 운전자의 책임이에요.", PleosTheme.typography.labelMedium, secondary = true)
    }
    if (c.debuggable) PleosPanel(Modifier.fillMaxWidth(), title = "시연 상태 (debug)") {
        Txt("adb: am broadcast -a com.hmgcarapp.saferadar.SIM --ez demo true", PleosTheme.typography.labelSmall, secondary = true)
    }
}
