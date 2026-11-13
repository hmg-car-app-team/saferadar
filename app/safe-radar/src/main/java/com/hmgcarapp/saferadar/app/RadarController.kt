package com.hmgcarapp.saferadar.app

import android.content.BroadcastReceiver
import android.content.Context
import android.os.SystemClock
import com.hmgcarapp.core.CarSignals
import com.hmgcarapp.core.drive.DriveSnapshot
import com.hmgcarapp.core.drive.DriveState
import com.hmgcarapp.core.drive.Gear
import com.hmgcarapp.core.location.GeoPoint
import com.hmgcarapp.core.log.HmgLog
import com.hmgcarapp.core.sim.SimulationHub
import com.hmgcarapp.core.store.LocalJsonStore
import com.hmgcarapp.core.voice.PleosSpeaker
import com.hmgcarapp.core.voice.Speaker
import com.hmgcarapp.saferadar.BuildConfig
import com.hmgcarapp.saferadar.domain.AnonIdRotator
import com.hmgcarapp.saferadar.domain.DemoScript
import com.hmgcarapp.saferadar.domain.DriveResolver
import com.hmgcarapp.saferadar.domain.PrivacyText
import com.hmgcarapp.saferadar.domain.SavePolicy
import com.hmgcarapp.saferadar.domain.ShareStatus
import com.hmgcarapp.saferadar.domain.TripVoice
import com.hmgcarapp.saferadar.domain.TripSnapshot
import com.hmgcarapp.saferadar.domain.DriveSample
import com.hmgcarapp.saferadar.domain.EventDetector
import com.hmgcarapp.saferadar.domain.GradeCalc
import com.hmgcarapp.saferadar.domain.HeadingTracker
import com.hmgcarapp.saferadar.domain.NearbyMapper
import com.hmgcarapp.saferadar.domain.NearbyVehicle
import com.hmgcarapp.saferadar.domain.RiskEvent
import com.hmgcarapp.saferadar.domain.ServerUrlPolicy
import com.hmgcarapp.saferadar.domain.Trends
import com.hmgcarapp.saferadar.domain.TripRecord
import com.hmgcarapp.saferadar.domain.TripSummary
import com.hmgcarapp.saferadar.domain.TripTracker
import com.hmgcarapp.saferadar.domain.TurnSignal
import com.hmgcarapp.saferadar.domain.WarnEffect
import com.hmgcarapp.saferadar.domain.WarnEvent
import com.hmgcarapp.saferadar.net.HttpShareApi
import com.hmgcarapp.saferadar.net.ShareApi
import com.hmgcarapp.saferadar.vehicle.BrakeSource
import com.hmgcarapp.saferadar.vehicle.PleosBrakeSource
import com.hmgcarapp.saferadar.vehicle.PleosSteeringSource
import com.hmgcarapp.saferadar.vehicle.PleosTurnSignalSource
import com.hmgcarapp.saferadar.vehicle.SteeringSource
import com.hmgcarapp.saferadar.vehicle.PleosSpeedLimitSource
import com.hmgcarapp.saferadar.vehicle.SpeedLimitSource
import com.hmgcarapp.saferadar.domain.SpeedLimit
import com.hmgcarapp.saferadar.vehicle.TurnSignalSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.TimeZone

/**
 * 화면 상태의 단일 소유자이자 SDK/IO 배선. 프로세스 수명([RadarRuntime]). 모든 상태 변경은 메인 스레드(Dispatchers.Main.immediate).
 * 이벤트 감지·점수·경고 판단은 순수 클래스([EventDetector]·[TripTracker]·[com.hmgcarapp.saferadar.domain.WarnFlow])가 하고 여기서는 배선만 한다.
 * 시계: 지속시간·간격·쿨다운은 [monoClock](단조), 기록 시각·저장 만료·날짜 경계만 [wallClock].
 *
 * 개인정보: 서버로 보내는 것은 [Report] 한 건(익명 ID·Geohash7·방향 8분할·등급·유형)뿐이며, 동의([Settings.consent])·서버 주소·
 * 실제 주행 중이 모두 맞을 때만 보낸다([ShareGate]). 위치·좌표·익명 ID 는 저장하지 않고 로그에도 남기지 않는다.
 */
class RadarController(
    private val context: Context,
    val signals: CarSignals,
    private val steering: SteeringSource = PleosSteeringSource(context),
    private val brake: BrakeSource = PleosBrakeSource(context),
    private val turn: TurnSignalSource = PleosTurnSignalSource(context),
    private val speedLimit: SpeedLimitSource = PleosSpeedLimitSource(context),
    private val speaker: Speaker = PleosSpeaker(context),
    private val apiFactory: (String) -> ShareApi = { HttpShareApi(it) },
    serverUrlRaw: String = BuildConfig.RADAR_SERVER_URL,
    private val serviceControl: (Boolean) -> Unit = {},
    private val shareListener: (ShareStatus) -> Unit = {},
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val monoClock: () -> Long = SystemClock::elapsedRealtime,
    val tzOffsetMs: () -> Long = { TimeZone.getDefault().getOffset(System.currentTimeMillis()).toLong() },
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, t -> HmgLog.w("App", "코루틴 예외(무시): ${t.javaClass.simpleName}") })
    private val leaveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stateStore = StateStore(LocalJsonStore.forApp(context))
    val debuggable: Boolean = SimulationHub.isDebuggable(context)
    private val serverUrl: String? = ServerUrlPolicy.validate(serverUrlRaw, debuggable)
    private val idRotator = AnonIdRotator(monoClock = monoClock)
    private val pump = SharePump(serverUrl?.let(apiFactory), serverUrl, idRotator)

    private val _state = MutableStateFlow(UiState(debuggable = debuggable, serverConfigured = serverUrl != null))
    val state: StateFlow<UiState> = _state

    private val detector = EventDetector()
    private val tracker = TripTracker()
    private val heading = HeadingTracker()
    private val recent = ArrayList<RiskEvent>()

    private var lastSpeed: Float? = null; private var lastSpeedAt = 0L
    private var lastBrake: Boolean? = null; private var lastBrakeAt = 0L
    private var lastTurn: TurnSignal? = null; private var lastTurnAt = 0L
    private var lastSteer: Float? = null; private var lastSteerAt = 0L
    private var lastLimit: Int? = null; private var lastLimitAt = 0L
    private var lastLoc: GeoPoint? = null; private var lastLocAt = 0L
    private var lastHeading: Double? = null
    private var coreDrive = DriveState.DRIVING
    private var uiDrive: DriveState? = null // null = 화면 없음
    private var saveJob: Job? = null
    private var demoJob: Job? = null
    private var simReceiver: BroadcastReceiver? = null
    private var lastPersistTick = 0L
    private val restored: Boolean get() = !_state.value.loading

    init {
        // 시연 상태는 저장하지 않는다: 앱 시작은 항상 실신호.
        signals.hub.setDriveOverride(null); signals.hub.setEnabled(false)
        scope.launch { restore() }
        scope.launch {
            signals.driveState.catch { HmgLog.w("Drive", "flow 실패 ${it.javaClass.simpleName}") }.collect { d: DriveSnapshot -> coreDrive = d.state; applyDrive() }
        }
        scope.launch {
            signals.speedKmh.catch { HmgLog.w("Speed", "flow 실패 ${it.javaClass.simpleName}") }.collect { r ->
                lastSpeed = r.value; lastSpeedAt = monoClock()
            }
        }
        scope.launch { steering.angles().catch { HmgLog.w("Steering", "flow 실패 ${it.javaClass.simpleName}") }.collect { if (demoJob == null) { lastSteer = it; lastSteerAt = monoClock() } } }
        scope.launch { brake.pressed().catch { HmgLog.w("Brake", "flow 실패 ${it.javaClass.simpleName}") }.collect { if (demoJob == null) { lastBrake = it; lastBrakeAt = monoClock() } } }
        scope.launch { speedLimit.limits().catch { HmgLog.w("Limit", "flow 실패 ${it.javaClass.simpleName}") }.collect { if (demoJob == null) { lastLimit = SpeedLimit.sane(it); lastLimitAt = monoClock() } } }
        scope.launch { turn.states().catch { HmgLog.w("Turn", "flow 실패 ${it.javaClass.simpleName}") }.collect { if (demoJob == null) { lastTurn = it; lastTurnAt = monoClock() } } }
        scope.launch {
            // 위치는 메모리에서만 쓰고 저장·로그하지 않는다.
            signals.location.catch { HmgLog.w("Location", "flow 실패 ${it.javaClass.simpleName}") }.collect { g: GeoPoint ->
                lastLoc = g; lastLocAt = monoClock()
                lastHeading = heading.update(g.latitude, g.longitude, g.heading)
            }
        }
        scope.launch { while (true) { delay(TICK_MS); tick() } }
        if (debuggable) simReceiver = SimReceiver.register(context, this, monoClock)
    }

    // ---------- 주행 판정 ----------
    private fun applyDrive() {
        val d = DriveResolver.combine(coreDrive, uiDrive)
        if (_state.value.drive == d) return
        _state.update { it.copy(drive = d) }
        dispatchWarn(WarnEvent.DriveChanged(d == DriveState.DRIVING))
    }

    /** 화면 쪽 UX 제한(provider)까지 반영한 주행 판정. 사용자 조작이 아닌 시스템 상태 반영. */
    fun onUiDriveState(d: DriveState?) { uiDrive = d; applyDrive() }

    private inline fun parkedOnly(block: () -> Unit) { if (_state.value.drive == DriveState.DRIVING) return; block() }

    // ---------- 1틱: 감지 -> 주행 누적 -> 경고 만료 -> 공유 ----------
    private fun tick() {
        if (!restored) return
        val now = monoClock()
        val speedFresh = lastSpeed != null && now - lastSpeedAt <= FRESH_MS
        val speed = if (speedFresh) lastSpeed else null
        val events = if (speed != null) detector.feed(DriveSample(now, speed, fresh(lastBrake, lastBrakeAt, now), fresh(lastTurn, lastTurnAt, now), fresh(lastSteer, lastSteerAt, now, STEER_FRESH_MS), fresh(lastLimit, lastLimitAt, now, LIMIT_FRESH_MS)))
        else { detector.reset(); emptyList() }
        if (events.isNotEmpty()) { recent += events; HmgLog.i("Event", "감지 ${events.joinToString { it.kind.name }}") }
        recent.removeAll { now - it.tMs > GradeCalc.WINDOW_MS }

        val driving = _state.value.drive == DriveState.DRIVING
        tracker.onTick(now, driving, speed, events)?.let { finishTrip(it, demo = demoJob != null) }
        dispatchWarn(WarnEvent.Tick(now))

        val live = LiveTrip(tracker.isActive, tracker.liveScore(), tracker.liveCounts.sum(), tracker.liveMinutes, tracker.liveDistanceKm)
        val avail = SignalAvail(speedFresh, fresh(lastBrake, lastBrakeAt, now) != null, fresh(lastTurn, lastTurnAt, now) != null, fresh(lastSteer, lastSteerAt, now, STEER_FRESH_MS) != null, lastLoc != null && now - lastLocAt <= LOC_FRESH_MS)
        _state.update { if (it.live == live && it.signals == avail) it else it.copy(live = live, signals = avail) }

        shareTick(now, speed)
        if (now - lastPersistTick >= PERSIST_EVERY_MS) { lastPersistTick = now; if (SavePolicy.periodic(tracker.isActive, demoJob != null)) scheduleSave() }
    }

    private fun <T> fresh(v: T?, at: Long, now: Long, maxAge: Long = FRESH_MS): T? = if (v != null && now - at <= maxAge) v else null

    private fun finishTrip(s: TripSummary, demo: Boolean) {
        val wall = wallClock()
        _state.update { it.copy(report = TripReport(s, demo, wall)) }
        val score = s.score
        if (!demo && score != null) {
            val rec = TripRecord(wall, score, s.distanceKm.toFloat(), s.durationMin, s.counts)
            updateP { it.copy(trips = (it.trips + rec).takeLast(Trends.MAX_TRIPS)) }
        }
        scheduleSave() // 점수 없는 짧은 주행도 디스크의 진행 중 스냅샷을 비운다
        HmgLog.i("Trip", "주행 종료 점수=${score ?: "없음"} 이벤트=${s.totalEvents}${if (demo) " (시연: 저장 안 함)" else ""}")
        // 정차했을 때만 말한다(주행 중에는 안내 외 음성 없음)
        if (_state.value.drive == DriveState.PARKED && _state.value.p.settings.voiceWarn) {
            TripVoice.line(score, s.totalEvents, demo)?.let { speaker.speak(it) }
        }
    }

    // ---------- 경고 ----------
    private fun dispatchWarn(e: WarnEvent) {
        var fx: List<WarnEffect> = emptyList()
        _state.update { st -> com.hmgcarapp.saferadar.domain.WarnFlow.reduce(st.warn, e).let { (w, f) -> fx = f; st.copy(warn = w) } }
        fx.forEach {
            when (it) {
                is WarnEffect.Speak -> if (_state.value.p.settings.voiceWarn) speaker.speak(it.text)
                WarnEffect.CancelSpeech -> speaker.stop()
            }
        }
    }

    private fun onNearby(list: List<NearbyVehicle>, lat: Double, lon: Double, headingDeg: Double) {
        val c = NearbyMapper.candidates(lat, lon, headingDeg, list)
        dispatchWarn(WarnEvent.Nearby(monoClock(), c))
    }

    // ---------- 서버 공유(옵트인) ----------
    private fun shareTick(now: Long, speed: Float?) {
        val st = _state.value
        val loc = lastLoc?.takeIf { now - lastLocAt <= LOC_FRESH_MS }
        val hd = lastHeading
        val input = ShareInput(
            consent = st.p.settings.consent, demo = demoJob != null, driving = st.drive == DriveState.DRIVING, speedKmh = speed,
            lat = loc?.latitude, lon = loc?.longitude, headingDeg = hd,
            grade = GradeCalc.grade(recent, now), kind = GradeCalc.dominant(recent, now),
        )
        val status = pump.status(input)
        if (st.share != status) { _state.update { it.copy(share = status) }; shareListener(status) }
        val report = pump.prepare(now, input) ?: return
        pump.takePendingLeave()?.let { old -> leaveAsync(old) }
        val lat = loc!!.latitude; val lon = loc.longitude; val headingDeg = hd!!
        val gen = pump.generation
        scope.launch {
            var ok = false
            try {
                val res = withContext(Dispatchers.IO) { pump.send(report, gen) { _state.value.p.settings.consent } }
                ok = res !is SharePump.SendResult.Failed
                when (res) {
                    // 동의가 그 사이 철회됐으면 결과를 쓰지 않는다(Ok.revoked 면 같은 ID 로 leave 를 이미 다시 보냈다)
                    is SharePump.SendResult.Ok -> if (!res.revoked && _state.value.p.settings.consent && demoJob == null) onNearby(pump.filterSelf(res.list, monoClock()), lat, lon, headingDeg)
                    SharePump.SendResult.Failed -> HmgLog.w("Share", "전송 실패") // 좌표·ID·주소는 기록하지 않는다
                    SharePump.SendResult.Skipped -> Unit
                }
            } finally { pump.finish(ok, gen) }
        }
    }

    // ---------- 사용자 조작 (상태를 바꾸는 공개 메서드는 모두 DRIVING 가드) ----------
    private fun updateP(f: (Persisted) -> Persisted) {
        if (!restored) return
        _state.update { it.copy(p = f(it.p)) }
        scheduleSave()
    }

    fun setPage(p: Page) = parkedOnly { _state.update { it.copy(page = p) } }

    /** 동의 화면의 선택. true = 동의(공유 시작), false = 동의하지 않음. 어느 쪽이든 화면은 다시 묻지 않는다. */
    fun answerConsent(agree: Boolean) = parkedOnly {
        updateP { it.copy(settings = it.settings.copy(consent = agree, consentAsked = true)) }
        if (!agree) revokeLocal()
    }

    /** 동의 화면에서 '나중에': 공유는 꺼진 채 두고 이 화면을 다시 띄우지 않는다(설정 > 근접 경고에서 언제든 켤 수 있음). */
    fun postponeConsent() = parkedOnly { updateP { it.copy(settings = it.settings.copy(consentAsked = true)) } }

    /** 동의 철회: 공유 즉시 중단 + 익명 ID 폐기 + 서버에 내 항목 삭제 요청(best effort). */
    /** 고지용 '호스트:포트'(서버 주소가 없으면 null). */
    fun serverHostPort(): String? = ServerUrlPolicy.hostPort(serverUrl)

    fun setConsent(on: Boolean) = parkedOnly {
        updateP { it.copy(settings = it.settings.copy(consent = on, consentAsked = true)) }
        if (!on) revokeLocal()
    }

    private fun revokeLocal() {
        pump.revoke()?.let { leaveAsync(it) }
        persistRevocationNow()
    }

    /** 동의 철회는 300ms 디바운스(scheduleSave) 없이 즉시 쓴다. 저장이 실패/보류면 정직하게 알린다(정차 중에만 호출되므로 토스트가 보인다). */
    private fun persistRevocationNow() {
        saveJob?.cancel()
        val pending = capturePending()
        scope.launch {
            val r = withContext(Dispatchers.IO + NonCancellable) { stateStore.writeLatest { persistedCopy(pending) } }
            onWriteResult(r)
            if (r == WriteResult.FAILED || r == WriteResult.HELD) _state.update { it.copy(message = PrivacyText.REVOKE_NOT_SAVED) }
        }
    }

    /** 서버에 남은 항목 삭제 요청(best effort). 교체 직전 ID·동의 철회 모두 같은 경로. */
    private fun leaveAsync(id: String) {
        // release()·scope.cancel 에 취소되지 않는 별도 범위에서(pump.leave 는 NonCancellable + 3초 제한)
        leaveScope.launch {
            try { pump.leave(id) } catch (t: Throwable) { HmgLog.w("Share", "삭제 요청 실패: ${t.javaClass.simpleName}") }
        }
    }

    fun setBackground(on: Boolean) = parkedOnly { updateP { it.copy(settings = it.settings.copy(background = on)) }; serviceControl(on) }
    fun setVoiceWarn(on: Boolean) = parkedOnly { updateP { it.copy(settings = it.settings.copy(voiceWarn = on)) } }
    fun nowWall(): Long = wallClock()
    fun clearMessage() { _state.update { it.copy(message = null) } }

    /** 저장된 주행 기록(점수·추이·배지)을 지운다. 설정(동의 포함)은 그대로. */
    fun clearAllData() = parkedOnly {
        if (!restored) return@parkedOnly
        saveJob?.cancel()
        tracker.finishNow() // 진행 중이던 주행도 버린다(삭제 뒤 pending 스냅샷이 되써지지 않게)
        detector.reset(); recent.clear()
        _state.update { it.copy(p = Persisted(settings = it.p.settings), report = null) }
        val pending = capturePending()
        scope.launch {
            val ok = withContext(Dispatchers.IO) { stateStore.clearAll { persistedCopy(pending) } }
            _state.update { it.copy(saveHeld = !stateStore.writable, corruptKept = false, message = if (ok) "저장된 주행 기록을 모두 지웠어요" else "삭제하지 못했어요") }
        }
    }

    // ---------- 시연 (가상 차량·가상 신호: 서버로 보내지 않고 저장하지 않는다) ----------
    fun startDemo() = parkedOnly { beginDemo() }

    internal fun beginDemo() {
        if (demoJob != null || !restored) return
        // 진행 중이던 실제 주행이 있으면 먼저 확정해 시연 데이터와 섞지 않는다
        tracker.finishNow()?.let { finishTrip(it, demo = false) }
        detector.reset(); recent.clear(); heading.reset(); lastHeading = null
        lastBrake = false; lastTurn = TurnSignal.OFF
        val h = signals.hub
        h.setEnabled(true); h.setGear(Gear.DRIVE); h.setSpeedKmh(0f)
        _state.update { it.copy(demo = true, report = null) }
        val t0 = monoClock()
        var lastOthersAt: Long? = null
        demoJob = scope.launch {
            while (true) {
                val t = monoClock() - t0
                val f = DemoScript.frameAt(t)
                val now = monoClock()
                h.setSpeedKmh(f.speedKmh)
                h.setLocation(GeoPoint(f.lat, f.lon, f.headingDeg, wallClock()))
                lastSteer = f.steerDeg; lastSteerAt = now
                lastBrake = f.brake; lastBrakeAt = now
                lastTurn = f.turn; lastTurnAt = now
                if (f.others.isNotEmpty() && lastOthersAt.let { it == null || now - it >= DEMO_NEARBY_EVERY_MS }) {
                    lastOthersAt = now
                    onNearby(f.others, f.lat, f.lon, f.headingDeg.toDouble())
                }
                if (f.finished) break
                delay(DEMO_STEP_MS)
            }
            h.setSpeedKmh(0f); h.setGear(Gear.PARK)
            delay(1_500L) // 정차 상태가 반영된 뒤 리포트를 낸다
            tracker.finishNow()?.let { finishTrip(it, demo = true) }
            endDemo()
        }
    }

    internal fun endDemo() {
        demoJob?.cancel(); demoJob = null
        signals.hub.reset()
        recent.clear(); detector.reset(); heading.reset(); lastHeading = null
        lastBrake = null; lastTurn = null; lastSteer = null; lastLimit = null
        _state.update { it.copy(demo = false) }
        dispatchWarn(WarnEvent.DriveChanged(_state.value.drive == DriveState.DRIVING))
    }

    /** 시연 중단(adb 전용 경로 포함). 진행 중이던 시연 주행은 확정하지 않고 버린다. */
    internal fun abortDemo() {
        if (demoJob == null) return
        tracker.finishNow()
        endDemo()
    }

    // ---------- adb 시연(SimReceiver 용: debuggable 전용, 시스템 주도라 주행 가드 없음) ----------
    internal fun simHub(enabled: Boolean?, speed: Float?, gear: Gear?, loc: GeoPoint?) {
        if (!debuggable) return
        val h = signals.hub
        enabled?.let { h.setEnabled(it) }
        speed?.let { h.setSpeedKmh(it) }
        gear?.let { h.setGear(it) }
        loc?.let { h.setLocation(it) }
    }

    // ---------- 저장/복원 ----------
    private suspend fun restore() {
        var res = StateStore.Restored(Persisted(), corrupt = false, quarantined = false, readError = false)
        try {
            res = withContext(Dispatchers.IO) { stateStore.restore(wallClock()) }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            HmgLog.w("Store", "복원 실패 -> 기본값, 이번 세션 저장 보류: ${t.javaClass.simpleName}")
            stateStore.holdWrites()
            res = res.copy(readError = true)
        } finally {
            applyRestored(res)
        }
    }

    private fun applyRestored(res: StateStore.Restored) {
        if (res.corrupt) HmgLog.w("Store", "저장 파일 손상 -> 기본값으로 시작(보존=${res.quarantined})")
        if (res.readError) HmgLog.w("Store", "저장 파일 읽기 오류 -> 기본값으로 동작, 이번 세션 저장 보류(원본 유지)")
        var p = res.p
        // 프로세스가 죽을 때 진행 중이던 주행은 마지막 저장 시각의 기록으로 확정한다
        p.pending?.let { snap ->
            val s = TripTracker.summarize(snap)
            val score = s.score
            p = p.copy(pending = null, trips = if (score != null) (p.trips + TripRecord(p.savedAtWallMs.takeIf { it > 0 } ?: wallClock(), score, s.distanceKm.toFloat(), s.durationMin, s.counts)).takeLast(Trends.MAX_TRIPS) else p.trips)
        }
        val last = p.trips.lastOrNull()
        _state.update {
            it.copy(
                p = p, loading = false, saveHeld = res.readError, corruptKept = res.corrupt && res.quarantined,
                report = last?.let { r -> TripReport(com.hmgcarapp.saferadar.domain.ScoreCalc.summarize(r.distanceKm.toDouble(), r.durationMin, r.counts, emptyList()).copy(score = r.score), demo = false, endWallMs = r.endWallMs) },
            )
        }
        if (res.p.pending != null) scheduleSave()
        serviceControl(p.settings.background)
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(300)
            if (!restored) return@launch
            val pending = capturePending() // 메인 스레드에서 먼저 캡처, IO 람다는 값만 쓴다
            onWriteResult(withContext(Dispatchers.IO + NonCancellable) { stateStore.writeLatest { persistedCopy(pending) } })
        }
    }

    private fun onWriteResult(r: WriteResult) {
        when (r) {
            WriteResult.WROTE -> _state.update { if (it.corruptKept) it.copy(corruptKept = false) else it }
            WriteResult.FAILED -> _state.update { it.copy(saveHeld = true, message = "저장하지 못했어요") }
            WriteResult.UNCHANGED, WriteResult.HELD -> Unit
        }
    }

    /** 디스크에 쓸 사본. 시연 중 데이터는 쓰지 않는다. 위치·좌표·익명 ID 는 포함하지 않는다. */
    private fun persistedCopy(pending: TripSnapshot?): Persisted =
        _state.value.p.copy(pending = pending, savedAtWallMs = wallClock())

    /** 메인 스레드 전용: 가변 상태인 추적기에서 불변 스냅샷을 만든다(시연 중에는 없음). */
    private fun capturePending(): TripSnapshot? = if (demoJob == null) tracker.snapshot() else null

    fun saveNow() {
        if (!restored) return
        saveJob?.cancel()
        val pending = capturePending()
        onWriteResult(stateStore.writeLatest { persistedCopy(pending) })
    }

    /** 권한 결과 등으로 서비스 필요 여부를 다시 적용한다. */
    fun onAppStart() { if (restored) serviceControl(_state.value.p.settings.background) }

    fun release() {
        saveNow()
        simReceiver?.let { runCatching { context.unregisterReceiver(it) } }
        demoJob?.cancel()
        signals.hub.reset()
        speaker.release()
        scope.cancel()
    }

    companion object {
        const val TICK_MS = 500L
        const val FRESH_MS = 3_000L
        const val STEER_FRESH_MS = 2_000L
        /** 제한속도는 초당 전달(문서 기준)이라 이 시간 넘게 없으면 '모름'으로 되돌린다. */
        const val LIMIT_FRESH_MS = 10_000L
        const val LOC_FRESH_MS = 5_000L
        const val PERSIST_EVERY_MS = 30_000L
        const val DEMO_STEP_MS = 100L
        const val DEMO_NEARBY_EVERY_MS = 1_000L
    }
}
