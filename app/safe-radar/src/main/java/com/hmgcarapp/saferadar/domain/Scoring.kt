package com.hmgcarapp.saferadar.domain

import kotlin.math.roundToInt

/** 감점 구간: 주행 시작 후 [atMin]분 시점의 [kind] 이벤트. */
data class Deduction(val kind: RiskKind, val atMin: Int)

/**
 * 한 번의 주행 결과(리포트용). 위치·좌표는 포함하지 않는다.
 * [score] 가 null 이면 점수 산정 대상이 아닌 짧은 주행([ScoreCalc.MIN_KM]/[ScoreCalc.MIN_MIN] 미만).
 */
data class TripSummary(
    val distanceKm: Double,
    val durationMin: Int,
    val counts: List<Int>,
    val deductions: List<Deduction>,
    val score: Int?,
    val tip: String,
) {
    fun count(k: RiskKind) = counts.getOrElse(k.code) { 0 }
    val totalEvents: Int get() = counts.sum()
}

/**
 * 안전운전 점수(0~100) = 100 - 감점. 감점 = Σ(이벤트 횟수 × [RiskKind.weight]) × 10 / max(10, 주행 거리 km).
 * 10km 이상 주행은 10km 당 감점으로 환산하고, 그보다 짧으면 환산하지 않는다(짧은 주행에서 횟수를 희석하지 않기).
 * **가중치·환산은 미검증 초기값**이다.
 */
object ScoreCalc {
    const val MIN_KM = 0.5
    const val MIN_MIN = 2
    const val KM_BASIS = 10.0

    fun penalty(counts: List<Int>, distanceKm: Double): Double {
        val raw = RiskKind.entries.sumOf { k -> counts.getOrElse(k.code) { 0 } * k.weight }
        return raw * KM_BASIS / maxOf(KM_BASIS, distanceKm)
    }

    fun score(counts: List<Int>, distanceKm: Double, durationMin: Int): Int? {
        if (distanceKm < MIN_KM || durationMin < MIN_MIN) return null
        return (100.0 - penalty(counts, distanceKm)).roundToInt().coerceIn(0, 100)
    }

    /** 감점 기여가 가장 큰 종류의 팁. 동률은 번호가 낮은 종류. 이벤트가 없으면 칭찬 문구. */
    fun tip(counts: List<Int>): String {
        val top = RiskKind.entries
            .map { it to counts.getOrElse(it.code) { 0 } * it.weight }
            .filter { it.second > 0 }
            .maxWithOrNull(compareBy<Pair<RiskKind, Int>> { it.second }.thenByDescending { it.first.code })
        return top?.first?.tip ?: "급한 조작 없이 부드럽게 운전했어요. 지금처럼 유지해 보세요."
    }

    fun summarize(distanceKm: Double, durationMin: Int, counts: List<Int>, deductions: List<Deduction>) =
        TripSummary(distanceKm, durationMin, counts, deductions, score(counts, distanceKm, durationMin), tip(counts))
}

/** 공유용 실시간 위험 등급: 최근 [WINDOW_MS] 안 이벤트의 가중 합으로 판정(미검증 초기값). */
object GradeCalc {
    const val WINDOW_MS = 10 * 60_000L
    const val MID_POINTS = 6
    const val HIGH_POINTS = 12

    fun points(events: List<RiskEvent>, nowMs: Long): Int =
        events.filter { nowMs - it.tMs in 0..WINDOW_MS }.sumOf { it.kind.weight }

    fun grade(events: List<RiskEvent>, nowMs: Long): RiskGrade {
        val p = points(events, nowMs)
        return when { p >= HIGH_POINTS -> RiskGrade.HIGH; p >= MID_POINTS -> RiskGrade.MID; else -> RiskGrade.LOW }
    }

    /** 최근 창에서 가중 합이 가장 큰 종류(동률은 번호가 낮은 쪽). 이벤트가 없으면 null. */
    fun dominant(events: List<RiskEvent>, nowMs: Long): RiskKind? =
        events.filter { nowMs - it.tMs in 0..WINDOW_MS }.groupBy { it.kind }
            .mapValues { (k, v) -> v.size * k.weight }
            .entries.maxWithOrNull(compareBy<Map.Entry<RiskKind, Int>> { it.value }.thenByDescending { it.key.code })?.key
}
