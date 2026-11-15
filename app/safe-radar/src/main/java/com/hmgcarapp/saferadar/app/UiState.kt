package com.hmgcarapp.saferadar.app

import com.hmgcarapp.core.drive.DriveState
import com.hmgcarapp.saferadar.domain.ShareStatus
import com.hmgcarapp.saferadar.domain.TripSummary
import com.hmgcarapp.saferadar.domain.WarnState

enum class Page(val label: String) { TODAY("오늘"), WEEK("주간·배지"), SHARE("근접 경고"), SETTINGS("설정") }

/** 마지막으로 끝난 주행의 리포트. [demo] 이면 시연 데이터이며 저장·통계에 반영하지 않는다. */
data class TripReport(val summary: TripSummary, val demo: Boolean, val endWallMs: Long)

/** 진행 중 주행의 미리보기. */
data class LiveTrip(val active: Boolean = false, val score: Int? = null, val events: Int = 0, val minutes: Int = 0, val km: Double = 0.0)

/** 주행 화면의 속도·제한속도·조향각. 최근 값이 없으면 null(화면에 지어내지 않는다). */
data class DriveMotion(val speedKmh: Float? = null, val limitKmh: Int? = null, val steerDeg: Float? = null)

/** 수신 중인 신호(없는 신호는 해당 이벤트를 판단하지 못한다: 화면에 정직하게 표시). */
data class SignalAvail(val speed: Boolean = false, val brake: Boolean = false, val turn: Boolean = false, val steer: Boolean = false, val location: Boolean = false)

/** 화면에 보이는 모든 상태(컨트롤러가 단일 소유). 시연이 켜져 있으면 [demo]. */
data class UiState(
    val p: Persisted = Persisted(),
    val loading: Boolean = true,
    val drive: DriveState = DriveState.DRIVING,
    val page: Page = Page.TODAY,
    val warn: WarnState = WarnState(driving = true), // drive 초기값(DRIVING, fail-safe)과 일치
    val live: LiveTrip = LiveTrip(),
    val motion: DriveMotion = DriveMotion(),
    val report: TripReport? = null,
    val share: ShareStatus = ShareStatus.NO_CONSENT,
    val serverConfigured: Boolean = false,
    val signals: SignalAvail = SignalAvail(),
    val demo: Boolean = false,
    val debuggable: Boolean = false,
    val saveHeld: Boolean = false,
    val corruptKept: Boolean = false,
    val message: String? = null,
)
