package com.hmgcarapp.saferadar.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import com.hmgcarapp.core.drive.DriveState
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
import com.hmgcarapp.pleos.designsystem.theme.PleosRadius
import com.hmgcarapp.pleos.designsystem.theme.PleosSpacing
import com.hmgcarapp.pleos.designsystem.theme.PleosTheme
import com.hmgcarapp.pleos.designsystem.theme.PleosWindowMode
import com.hmgcarapp.saferadar.app.Page
import com.hmgcarapp.saferadar.app.RadarController
import com.hmgcarapp.saferadar.app.TripReport
import com.hmgcarapp.saferadar.app.UiState
import com.hmgcarapp.saferadar.domain.Badge
import com.hmgcarapp.saferadar.domain.Banner
import com.hmgcarapp.saferadar.domain.PrivacyText
import com.hmgcarapp.saferadar.domain.ShareStatus
import com.hmgcarapp.saferadar.domain.Trends
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

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
    val paneWidth = PleosTheme.window.appLandscapeWidth * 0.4f

    if (!driving && !s.loading && !s.p.settings.consentAsked && !s.demo) ConsentPopup(c)

    PleosScaffold(
        title = "세이프레이더",
        subtitle = if (driving) "주행 중" else "정차 중",
        toastState = toast,
        supportingPane = if (driving || s.loading) null else { m -> Column(m.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(PleosSpacing.m)) { Summary(s) } },
        supportingPaneWidth = paneWidth,
    ) { mode ->
        when {
            driving -> DrivingView(s)
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
        // description 은 maxLines=3 으로 말줄임되므로 쓰지 않고 content 슬롯에 짧은 고지 2문장을 넣는다(넘치면 본문만 스크롤)
        content = {
            Column(Modifier.heightIn(max = PleosTheme.dimensions.popupMaxHeight * 0.5f).verticalScroll(rememberScrollState()).testTag("consent_body"), verticalArrangement = Arrangement.spacedBy(PleosSpacing.s)) {
                PrivacyText.CONSENT_PARAGRAPHS.forEach { Txt(it, PleosTheme.typography.bodyMedium, secondary = true) }
            }
        },
    )
}

// ---------------- 주행 중: 읽기 전용 한 화면(조작 요소 없음) ----------------
@Composable
private fun DrivingView(s: UiState) {
    val banner = s.warn.banner
    val sceneDescription = banner?.text ?: "주변에 알릴 위험 차량이 없어요"
    Box(Modifier.fillMaxSize().clip(RoundedCornerShape(PleosRadius.xl)).testTag("driving_view")) {
        // 배경: 하늘색 그라디언트 위 원근 도로, 내 차(아이오닉 5), 내 차에서 퍼지는 파장, 위험 방향으로 번지는 빨간 빛
        RoadScene(
            speedKmh = s.motion.speedKmh, steerDeg = s.motion.steerDeg, alertDirection = s.warn.radar?.direction ?: banner?.direction,
            modifier = Modifier.fillMaxSize().semantics { contentDescription = sceneDescription }.testTag("road_scene"),
        )
        Column(Modifier.align(Alignment.TopCenter).padding(top = PleosSpacing.xxl), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(PleosSpacing.xs)) {
            Txt(s.motion.speedKmh?.roundToInt()?.toString() ?: "–", PleosTheme.typography.headlineLarge, modifier = Modifier.testTag("live_speed"))
            Txt("km/h", PleosTheme.typography.bodyMedium, secondary = true)
            // 근접 경고 3초 배너: 속도 바로 아래, 위험 빛과 같은 빨강 배지로 방향을 함께 읽게 한다
            banner?.let { AlertBanner(it) }
        }
        s.motion.limitKmh?.let { LimitSign(it, Modifier.align(Alignment.CenterStart).padding(start = PleosSpacing.xxxl)) }
        Row(Modifier.align(Alignment.BottomStart).padding(PleosSpacing.xl), horizontalArrangement = Arrangement.spacedBy(PleosSpacing.m), verticalAlignment = Alignment.CenterVertically) {
            HudChip {
                Txt("이번 주행", PleosTheme.typography.labelMedium, secondary = true)
                // 점수는 2분·0.5km 를 넘겨야 나온다. 그 전에는 빈 기호 대신 집계 중임을 알린다.
                s.live.score?.let { Txt("${it}점", PleosTheme.typography.titleLarge, modifier = Modifier.testTag("live_score")) }
                    ?: Txt(if (s.live.active) "집계 중" else "–", PleosTheme.typography.titleMedium, secondary = true, modifier = Modifier.testTag("live_score"))
                if (s.live.active) Txt("위험 이벤트 ${s.live.events}회 · ${s.live.minutes}분 · ${String.format(Locale.KOREA, "%.1f", s.live.km)}km", PleosTheme.typography.labelMedium, secondary = true, modifier = Modifier.testTag("live_detail"))
            }
        }
        PleosStatusBadge(s.share.label, shareStatusKind(s.share), Modifier.align(Alignment.BottomEnd).padding(PleosSpacing.xl).testTag("share_status"))
    }
}

/**
 * 근접 경고 3초 배너: 반투명 유리(리퀴드 글래스) 상자. 바탕이 반쯤 비쳐 뒤의 도로 장면이 보이고,
 * 윗면 광택과 빛을 받는 테두리(왼쪽 위 흰빛 -> 오른쪽 아래 붉은빛), 경고 배지 쪽의 옅은 붉은 기운으로 경고임을 알린다.
 */
@Composable
private fun AlertBanner(b: Banner) {
    val c = PleosTheme.colors
    val shape = RoundedCornerShape(percent = 50)
    val light = c.basicStaticLight100
    val sheen = if (c.isDark) 0.14f else 0.50f
    Row(
        Modifier.widthIn(max = PleosTheme.dimensions.toastMaxWidth)
            .clip(shape)
            .background(Brush.verticalGradient(
                0f to c.surfaceBasic.copy(alpha = if (c.isDark) 0.58f else 0.66f),
                1f to c.surfaceBasic.copy(alpha = if (c.isDark) 0.38f else 0.44f),
            ))
            .drawBehind {
                drawRect(Brush.radialGradient(0f to c.regulationRed.copy(alpha = 0.22f), 1f to Color.Transparent, center = Offset(size.height * 0.9f, size.height / 2f), radius = size.height * 2.2f))
                drawRect(Brush.verticalGradient(0f to light.copy(alpha = sheen), 0.5f to light.copy(alpha = 0f)))
            }
            .border(
                PleosSpacing.xxs / 2,
                Brush.linearGradient(0f to light.copy(alpha = if (c.isDark) 0.45f else 0.9f), 0.45f to light.copy(alpha = 0.06f), 1f to c.regulationRed.copy(alpha = 0.55f)),
                shape,
            )
            .padding(horizontal = PleosSpacing.xl, vertical = PleosSpacing.m).testTag("warn_banner"),
        horizontalArrangement = Arrangement.spacedBy(PleosSpacing.m), verticalAlignment = Alignment.CenterVertically,
    ) {
        PleosStatusBadge("주의", PleosStatus.Negative)
        Txt(b.text, PleosTheme.typography.titleMedium, modifier = Modifier.testTag("banner_msg"))
    }
}

/** 국내 규제 표지 모양(흰 바탕 빨간 테두리 원). 제한속도를 모르면 그리지 않는다. */
@Composable
private fun LimitSign(limitKmh: Int, modifier: Modifier = Modifier) {
    val c = PleosTheme.colors
    Box(
        modifier.size(PleosSpacing.xxxl * 2).clip(CircleShape).background(c.basicStaticLight100).border(PleosSpacing.xs, c.regulationRed, CircleShape)
            .semantics { contentDescription = "제한속도 ${limitKmh}킬로미터" }.testTag("limit_sign"),
        contentAlignment = Alignment.Center,
    ) { Text("$limitKmh", style = PleosTheme.typography.titleLarge, color = c.basicStaticDark100) }
}

@Composable
private fun HudChip(content: @Composable () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(PleosRadius.l)).background(PleosTheme.colors.surfaceBasic.copy(alpha = 0.86f))
            .padding(horizontal = PleosSpacing.l, vertical = PleosSpacing.m),
        verticalArrangement = Arrangement.spacedBy(PleosSpacing.xxs),
    ) { content() }
}

// ---------------- 정차 ----------------
@Composable
private fun Summary(s: UiState) {
    PleosPanel(Modifier.fillMaxWidth(), title = "최근 주행 점수") {
        Txt(s.report?.summary?.score?.toString() ?: "–", PleosTheme.typography.headlineMedium, modifier = Modifier.testTag("report_score"))
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
        if (r == null) Txt("–", secondary = true, modifier = Modifier.testTag("no_report"))
        else {
            val sc = r.summary
            sc.score?.let { Txt("오늘의 안전점수 ${it}점", PleosTheme.typography.titleMedium, modifier = Modifier.testTag("report_title")) }
            Txt("${String.format(Locale.KOREA, "%.1f", sc.distanceKm)}km · ${sc.durationMin}분 · 위험 이벤트 ${sc.totalEvents}회", secondary = true)
            com.hmgcarapp.saferadar.domain.RiskKind.entries.filter { sc.count(it) > 0 }.forEach { k ->
                PleosListItem(title = k.label, trailing = { Txt("${sc.count(k)}회", modifier = Modifier.testTag("count_${k.name}")) })
            }
            Txt("개선 팁: ${sc.tip}", modifier = Modifier.testTag("report_tip"))
        }
    }
    if (r != null && r.summary.deductions.isNotEmpty()) PleosPanel(Modifier.fillMaxWidth(), title = "감점 구간") {
        r.summary.deductions.take(5).forEach { d -> PleosListItem(title = "주행 ${d.atMin}분 무렵", description = d.kind.label) }
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
        avg?.let { Txt("이번 주 평균 ${it}점 (${Trends.weekCount(trips, now, tz)}회)", PleosTheme.typography.titleMedium, modifier = Modifier.testTag("week_avg")) }
        delta?.let { Txt(if (it >= 0) "지난주보다 ${it}점 올랐어요" else "지난주보다 ${-it}점 내려갔어요", secondary = true, modifier = Modifier.testTag("week_delta")) }
        Trends.last7Days(trips, now, tz).asReversed().forEach { d ->
            PleosListItem(
                title = fmt.format(Date(d.dayIndex * Trends.DAY_MS - tz + Trends.DAY_MS / 2)),
                trailing = { Txt(d.avg?.let { "${it}점 · ${d.trips}회" } ?: "–", secondary = d.avg == null) },
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
    }
}

@Composable
private fun SettingsPage(c: RadarController, s: UiState) {
    val st = s.p.settings
    PleosPanel(Modifier.fillMaxWidth(), title = "음성") {
        PleosSwitch(checked = st.voiceWarn, onCheckedChange = { c.setVoiceWarn(it) }, label = "음성 경고·점수 안내", modifier = Modifier.testTag("set_voice"))
        PleosSwitch(checked = st.background, onCheckedChange = { c.setBackground(it) }, label = "백그라운드 수집", modifier = Modifier.testTag("set_background"))
    }
    PleosPanel(Modifier.fillMaxWidth()) {
        PleosButton(if (s.demo) "시연 중" else "시연 시작", onClick = { c.startDemo() }, enabled = !s.demo, modifier = Modifier.testTag("btn_demo"))
    }
    PleosPanel(Modifier.fillMaxWidth(), title = "기록") {
        var ask by remember { mutableStateOf(false) }
        PleosButton("저장된 주행 기록 모두 삭제", onClick = { ask = true }, hierarchy = PleosButtonHierarchy.Secondary, modifier = Modifier.testTag("clear_all"))
        if (ask) PleosPopup(
            title = "저장된 주행 기록을 모두 삭제할까요?",
            description = "점수·주간 추이·배지가 삭제돼요.",
            confirmLabel = "삭제", onConfirm = { ask = false; c.clearAllData() },
            onDismissRequest = { ask = false }, cancelLabel = "취소",
        )
    }
    if (c.debuggable) PleosPanel(Modifier.fillMaxWidth(), title = "시연 상태 (debug)") {
        Txt("adb: am broadcast -a com.hmgcarapp.saferadar.SIM --ez demo true", PleosTheme.typography.labelSmall, secondary = true)
    }
}
