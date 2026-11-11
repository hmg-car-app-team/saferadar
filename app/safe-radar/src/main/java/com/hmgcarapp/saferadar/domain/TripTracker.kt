package com.hmgcarapp.saferadar.domain

/** 진행 중 주행의 저장용 스냅샷(프로세스 재시작 복원용). 위치·경로 없음. */
data class TripSnapshot(val distanceM: Double, val drivingMs: Long, val counts: List<Int>, val deductions: List<Deduction>)

/**
 * 주행 한 번의 누적(순수, 단조 시계 주입).
 * - 시작: 속도 [START_KMH] 이상으로 주행 판정이 된 첫 틱.
 * - 종료: 주행 판정이 아닌 상태(정차)가 [endParkedMs] 이어지면 요약을 돌려주고 초기화한다(P단 주차 후 리포트).
 * - 거리: 표본 간격이 [MAX_DT_MS] 이하일 때만 속도를 적분한다(끊김 구간 제외). 첫 관측은 누적하지 않는다.
 */
class TripTracker(private val endParkedMs: Long = END_PARKED_MS) {
    private var active = false
    private var startT = 0L
    private var lastT: Long? = null
    private var distanceM = 0.0
    private var drivingMs = 0L
    private var parkedSince: Long? = null
    private val counts = IntArray(RiskKind.entries.size)
    private val deductions = ArrayList<Deduction>()

    val isActive: Boolean get() = active
    val liveDistanceKm: Double get() = distanceM / 1000.0
    val liveMinutes: Int get() = (drivingMs / 60_000L).toInt()
    val liveCounts: List<Int> get() = counts.toList()

    fun liveScore(): Int? = ScoreCalc.score(counts.toList(), liveDistanceKm, liveMinutes)

    /** 틱마다 호출. 종료 조건이 충족되면 요약(없으면 null). */
    fun onTick(tMs: Long, driving: Boolean, speedKmh: Float?, events: List<RiskEvent>): TripSummary? {
        val prev = lastT
        lastT = tMs
        var dt = if (prev == null) 0L else tMs - prev
        if (!active) {
            if (driving && speedKmh != null && speedKmh >= START_KMH) { active = true; startT = tMs; parkedSince = null; dt = 0L } // 시작 이전 간격은 세지 않는다
            else return null
        }
        if (driving) {
            parkedSince = null
            if (dt in 1..MAX_DT_MS) {
                drivingMs += dt
                if (speedKmh != null && speedKmh.isFinite() && speedKmh > 0f) distanceM += speedKmh / 3.6 * dt / 1000.0
            }
        } else {
            val ps = parkedSince ?: tMs.also { parkedSince = it }
            if (tMs - ps >= endParkedMs) return finish()
        }
        events.forEach { add(it) }
        return null
    }

    private fun add(e: RiskEvent) {
        counts[e.kind.code]++
        deductions += Deduction(e.kind, ((e.tMs - startT).coerceAtLeast(0L) / 60_000L).toInt())
    }

    /** 정차 대기 없이 즉시 끝낸다(시연 종료·앱 종료 시). 진행 중이 아니면 null. */
    fun finishNow(): TripSummary? = if (active) finish() else null

    private fun finish(): TripSummary {
        val s = ScoreCalc.summarize(distanceM / 1000.0, (drivingMs / 60_000L).toInt(), counts.toList(), deductions.toList())
        resetAll()
        return s
    }

    private fun resetAll() {
        active = false; distanceM = 0.0; drivingMs = 0L; parkedSince = null; counts.fill(0); deductions.clear(); startT = 0L
    }

    fun snapshot(): TripSnapshot? = if (active) TripSnapshot(distanceM, drivingMs, counts.toList(), deductions.toList()) else null

    companion object {
        const val START_KMH = 5f
        const val END_PARKED_MS = 30_000L
        const val MAX_DT_MS = 3_000L

        /** 프로세스 재시작 뒤 남은 스냅샷을 요약으로 확정한다. 이벤트가 없고 너무 짧으면 점수는 null. */
        fun summarize(s: TripSnapshot): TripSummary =
            ScoreCalc.summarize(s.distanceM / 1000.0, (s.drivingMs / 60_000L).toInt(), s.counts, s.deductions)
    }
}
