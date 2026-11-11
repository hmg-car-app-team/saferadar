package com.hmgcarapp.saferadar.domain

/**
 * 이벤트 감지 임계값. **모두 미검증 초기값**이다(실차 데이터로 보정 전). 변경해도 테스트는 리터럴 기대값을 쓴다.
 */
data class DetectorConfig(
    /** 급감속: 1초 이상 간격 두 속도의 감속도(m/s²) 가 이 값 이상 */
    val hardBrakeMs2: Double = 3.5,
    /** 급가속: 가속도(m/s²) 가 이 값 이상 */
    val hardAccelMs2: Double = 3.0,
    /** 급감속·급가속 재무장 기준(m/s²): 이 값 미만으로 완화돼야 같은 종류를 다시 센다(한 번의 제동을 여러 번으로 세지 않기) */
    val rearmMs2: Double = 1.0,
    /** 급감속은 기준 속도가 이 이상일 때만(정차 직전 미세 변동 제외) */
    val brakeMinFromKmh: Float = 15f,
    val accelMinFromKmh: Float = 5f,
    /** 가속도 계산 기준: 현재보다 이 시간 이상 전의 가장 최근 표본 */
    val accelWindowMinMs: Long = 1_000L,
    /** 방향지시등 없는 차로변경 */
    val laneMinSpeedKmh: Float = 50f,
    val laneSwingDeg: Float = 6f,
    val laneWindowMs: Long = 2_000L,
    val signalLookbackMs: Long = 4_000L,
    val laneCooldownMs: Long = 6_000L,
    /** 고속 지속: 이 속도 이상이 holdMs 이어지면 1회, 재무장은 rearm 미만으로 내려갔을 때 */
    val highSpeedKmh: Float = 130f,
    val highSpeedRearmKmh: Float = 120f,
    val highSpeedHoldMs: Long = 10_000L,
    /** 급격한 고속 진입: surgeWindowMs 안에 최저 속도 대비 surgeGainKmh 이상 올라 surgeToKmh 이상 도달 */
    val surgeToKmh: Float = 100f,
    val surgeGainKmh: Float = 40f,
    val surgeWindowMs: Long = 10_000L,
    val surgeCooldownMs: Long = 20_000L,
    /** 표본 간격이 이보다 길면 신호 끊김으로 보고 기준을 새로 잡는다(끊긴 구간을 변화로 오인하지 않기) */
    val maxGapMs: Long = 3_000L,
)

/**
 * 순수 이벤트 감지기(시계는 표본의 [DriveSample.tMs], 단조 시계). 외부 상태·SDK 없음.
 * - 첫 표본(또는 끊김 뒤 첫 표본)은 기준만 잡고 이벤트를 만들지 않는다.
 * - 급감속: 속도 하락 + (브레이크 신호가 있는 경우) 창 안에서 페달이 밟힘.
 * - 차로변경: 방향지시등 신호가 한 번도 관측되지 않았으면 판단하지 않는다(알 수 없음 != 꺼짐).
 */
class EventDetector(private val cfg: DetectorConfig = DetectorConfig()) {
    private val history = ArrayDeque<DriveSample>()
    private var lastSignalAt: Long? = null
    private var turnKnown = false
    private var brakeArmed = true
    private var accelArmed = true
    private var lastLaneAt: Long? = null
    /** 이 시각까지의 조향 표본은 이미 판단에 쓰였다(쿨다운·방향지시등으로 무시된 흔들림이 나중에 다시 이벤트가 되지 않게). */
    private var laneConsumedAt = Long.MIN_VALUE
    private var lastSurgeAt: Long? = null
    private var highStart: Long? = null
    private var highFired = false

    fun reset() {
        history.clear(); lastSignalAt = null; turnKnown = false
        brakeArmed = true; accelArmed = true; lastLaneAt = null; laneConsumedAt = Long.MIN_VALUE; lastSurgeAt = null; highStart = null; highFired = false
    }

    fun feed(s: DriveSample): List<RiskEvent> {
        val prev = history.lastOrNull()
        if (prev != null && (s.tMs - prev.tMs > cfg.maxGapMs || s.tMs < prev.tMs)) reset()
        if (!s.speedKmh.isFinite() || s.speedKmh < 0f) { reset(); return emptyList() }
        val first = history.isEmpty()
        history.addLast(s)
        val keep = maxOf(cfg.surgeWindowMs, cfg.laneWindowMs, cfg.signalLookbackMs) + 2_000L
        while (history.size > 1 && s.tMs - history.first().tMs > keep) history.removeFirst()
        // 지시등 소스가 끊겨 null 이 오면 '꺼짐'이 아니라 '알 수 없음'이므로 차로변경 판단을 보류한다
        if (s.turn != null) { turnKnown = true; if (s.turn != TurnSignal.OFF) lastSignalAt = s.tMs } else turnKnown = false
        if (first) { highSpeedEvent(s); return emptyList() } // 첫 관측은 이벤트를 만들지 않지만 고속 '지속'의 시작 시각은 잡는다

        val out = ArrayList<RiskEvent>(2)
        accelEvents(s)?.let { out += it }
        laneEvent(s)?.let { out += it }
        highSpeedEvent(s)?.let { out += it }
        surgeEvent(s)?.let { out += it }
        return out
    }

    private fun accelEvents(s: DriveSample): RiskEvent? {
        val ref = history.lastOrNull { s.tMs - it.tMs >= cfg.accelWindowMinMs } ?: return null
        val dtS = (s.tMs - ref.tMs) / 1000.0
        val rate = (s.speedKmh - ref.speedKmh) / 3.6 / dtS // m/s², 양수 = 가속
        var ev: RiskEvent? = null
        if (rate <= -cfg.hardBrakeMs2 && ref.speedKmh >= cfg.brakeMinFromKmh && brakeArmed && brakeConfirmed(ref.tMs, s.tMs)) {
            ev = RiskEvent(RiskKind.HARD_BRAKE, s.tMs); brakeArmed = false
        }
        if (rate > -cfg.rearmMs2) brakeArmed = true
        if (ev == null && rate >= cfg.hardAccelMs2 && ref.speedKmh >= cfg.accelMinFromKmh && accelArmed) {
            ev = RiskEvent(RiskKind.HARD_ACCEL, s.tMs); accelArmed = false
        }
        if (rate < cfg.rearmMs2) accelArmed = true
        return ev
    }

    /** 브레이크 신호가 있는 환경이면 [from, to] 안에서 페달이 밟혔어야 한다. 신호가 전혀 없으면(null) 속도 하락만으로 판단. */
    private fun brakeConfirmed(from: Long, to: Long): Boolean {
        val win = history.filter { it.tMs in from..to }
        if (win.none { it.brake != null }) return true
        return win.any { it.brake == true }
    }

    private fun laneEvent(s: DriveSample): RiskEvent? {
        if (!turnKnown) { laneConsumedAt = s.tMs; return null } // 지시등 상태를 모르는 동안의 흔들림은 나중에도 판단하지 않는다
        if (s.steerDeg == null || s.speedKmh < cfg.laneMinSpeedKmh) return null
        val angles = history.filter { it.tMs > laneConsumedAt && s.tMs - it.tMs <= cfg.laneWindowMs && it.steerDeg != null }.map { it.steerDeg!! }
        if (angles.size < 2 || angles.max() - angles.min() < cfg.laneSwingDeg) return null
        laneConsumedAt = s.tMs // 이 흔들림은 아래 어느 경우든 한 번만 판단한다
        val last = lastLaneAt
        if (last != null && s.tMs - last < cfg.laneCooldownMs) return null
        val sig = lastSignalAt
        if (sig != null && s.tMs - sig <= cfg.signalLookbackMs) return null // 신호를 켜고 바꾼 정상 차로변경
        lastLaneAt = s.tMs
        return RiskEvent(RiskKind.LANE_CHANGE, s.tMs)
    }

    private fun highSpeedEvent(s: DriveSample): RiskEvent? {
        if (s.speedKmh >= cfg.highSpeedKmh) {
            if (highStart == null) highStart = s.tMs
            if (!highFired && s.tMs - highStart!! >= cfg.highSpeedHoldMs) { highFired = true; return RiskEvent(RiskKind.HIGH_SPEED, s.tMs) }
        } else {
            highStart = null // 130 미만으로 내려가면 '지속'이 끊긴다
            if (s.speedKmh < cfg.highSpeedRearmKmh) highFired = false // 재무장은 120 미만
        }
        return null
    }

    private fun surgeEvent(s: DriveSample): RiskEvent? {
        if (s.speedKmh < cfg.surgeToKmh) return null
        lastSurgeAt?.let { if (s.tMs - it < cfg.surgeCooldownMs) return null }
        val low = history.filter { s.tMs - it.tMs <= cfg.surgeWindowMs }.minOf { it.speedKmh }
        if (s.speedKmh - low < cfg.surgeGainKmh) return null
        lastSurgeAt = s.tMs
        return RiskEvent(RiskKind.SPEED_SURGE, s.tMs)
    }
}
