package com.hmgcarapp.saferadar.domain

import kotlin.math.roundToInt

/** 저장되는 주행 기록: 시각·점수·거리·시간·종류별 횟수만. 위치·경로·식별자는 없다. */
data class TripRecord(val endWallMs: Long, val score: Int, val distanceKm: Float, val durationMin: Int, val counts: List<Int>)

enum class Badge(val label: String, val how: String) {
    FIRST_TRIP("첫 안전 리포트", "점수가 산정되는 주행을 처음 마쳤어요"),
    CALM_90("부드러운 운전", "한 번의 주행에서 90점 이상"),
    STREAK_3("3연속 안전", "최근 3번의 주행이 모두 80점 이상"),
    IMPROVED("습관이 좋아졌어요", "이번 주 평균이 지난주보다 5점 이상 올랐어요"),
}

data class DayPoint(val dayIndex: Long, val avg: Int?, val trips: Int)

/** 주간 추이·배지(순수). 날짜 경계는 [tzOffsetMs]( 기기 시간대 오프셋) 기준 일 번호로 센다. */
object Trends {
    const val DAY_MS = 86_400_000L
    const val MAX_TRIPS = 60
    const val IMPROVE_POINTS = 5

    fun dayIndex(wallMs: Long, tzOffsetMs: Long): Long = Math.floorDiv(wallMs + tzOffsetMs, DAY_MS)

    /** 오늘을 마지막으로 하는 최근 7일(오래된 날 먼저)의 하루 평균 점수. 기록 없는 날은 avg=null. */
    fun last7Days(trips: List<TripRecord>, nowWallMs: Long, tzOffsetMs: Long, weeksAgo: Int = 0): List<DayPoint> {
        val today = dayIndex(nowWallMs, tzOffsetMs) - 7L * weeksAgo
        val byDay = trips.groupBy { dayIndex(it.endWallMs, tzOffsetMs) }
        return (today - 6..today).map { d ->
            val list = byDay[d].orEmpty()
            DayPoint(d, if (list.isEmpty()) null else list.map { it.score }.average().roundToInt(), list.size)
        }
    }

    /** 7일 구간의 주행 단위 평균(일 평균의 평균이 아니라 모든 주행의 평균). 주행이 없으면 null. */
    fun weekAverage(trips: List<TripRecord>, nowWallMs: Long, tzOffsetMs: Long, weeksAgo: Int = 0): Int? {
        val days = last7Days(trips, nowWallMs, tzOffsetMs, weeksAgo).map { it.dayIndex }.toSet()
        val list = trips.filter { dayIndex(it.endWallMs, tzOffsetMs) in days }
        return if (list.isEmpty()) null else list.map { it.score }.average().roundToInt()
    }

    fun weekCount(trips: List<TripRecord>, nowWallMs: Long, tzOffsetMs: Long, weeksAgo: Int = 0): Int {
        val days = last7Days(trips, nowWallMs, tzOffsetMs, weeksAgo).map { it.dayIndex }.toSet()
        return trips.count { dayIndex(it.endWallMs, tzOffsetMs) in days }
    }

    /** 이번 주 평균 - 지난주 평균. 어느 한쪽에 주행이 없으면 null. */
    fun weekDelta(trips: List<TripRecord>, nowWallMs: Long, tzOffsetMs: Long): Int? {
        val a = weekAverage(trips, nowWallMs, tzOffsetMs, 0) ?: return null
        val b = weekAverage(trips, nowWallMs, tzOffsetMs, 1) ?: return null
        return a - b
    }

    /** 기록에서 매번 계산하는 파생 값(저장하지 않는다: 기록 삭제 시 함께 사라짐). */
    fun badges(trips: List<TripRecord>, nowWallMs: Long, tzOffsetMs: Long): Set<Badge> {
        val out = LinkedHashSet<Badge>()
        if (trips.isNotEmpty()) out += Badge.FIRST_TRIP
        if (trips.any { it.score >= 90 }) out += Badge.CALM_90
        val sorted = trips.sortedBy { it.endWallMs }
        if (sorted.size >= 3 && sorted.takeLast(3).all { it.score >= 80 }) out += Badge.STREAK_3
        val delta = weekDelta(trips, nowWallMs, tzOffsetMs)
        if (delta != null && delta >= IMPROVE_POINTS &&
            weekCount(trips, nowWallMs, tzOffsetMs, 0) >= 2 && weekCount(trips, nowWallMs, tzOffsetMs, 1) >= 2) out += Badge.IMPROVED
        return out
    }
}
