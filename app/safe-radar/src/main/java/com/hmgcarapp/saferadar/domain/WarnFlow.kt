package com.hmgcarapp.saferadar.domain

/** 경고 후보: 내 기준 상대 방향·거리와 위험 유형. */
data class Candidate(
    val direction: Direction, val kind: RiskKind?, val grade: RiskGrade, val distanceM: Double,
    /** 내 진행 방향 기준 상대 방위(도, 0=정면, 오른쪽 +, -180..180). 모르거나 불확실하면 null. */
    val relBearingDeg: Double? = null,
)

/** '접근 중' 판정(순수). 내 진행 방향 앞쪽 [AHEAD_HALF_ANGLE_DEG] 이내일 때만 참. 방향 정보가 없으면 판정하지 않는다(null). */
object Approach {
    const val AHEAD_HALF_ANGLE_DEG = 45.0
    /** 셀 중심 오차(±수십 m) 때문에 이 거리보다 가까우면 방위를 믿지 않는다. */
    const val MIN_BEARING_DIST_M = 40.0

    fun relativeBearing(along: Double, lateral: Double): Double? =
        if (Math.hypot(along, lateral) < MIN_BEARING_DIST_M) null else Math.toDegrees(Math.atan2(lateral, along))

    /** true=앞쪽에서 접근, false=옆·뒤, null=알 수 없음. */
    fun isAhead(relBearingDeg: Double?): Boolean? {
        if (relBearingDeg == null || !relBearingDeg.isFinite()) return null
        return Math.abs(GeoMath.norm360(relBearingDeg + 180.0) - 180.0) <= AHEAD_HALF_ANGLE_DEG
    }
}

data class Banner(val text: String, val untilMs: Long)

data class WarnState(
    val driving: Boolean = false,
    val banner: Banner? = null,
    val lastSpokeAt: Long? = null,
    /** "방향 묶음|유형" -> 마지막 경고 시각(같은 상황 반복 경고 방지; 묶음은 [Direction.group]) */
    val lastBySignature: Map<String, Long> = emptyMap(),
)

sealed interface WarnEvent {
    data class Nearby(val tMs: Long, val candidates: List<Candidate>) : WarnEvent
    data class Tick(val tMs: Long) : WarnEvent
    data class DriveChanged(val driving: Boolean) : WarnEvent
}

sealed interface WarnEffect {
    data class Speak(val text: String) : WarnEffect
    data object CancelSpeech : WarnEffect
}

/**
 * 근접 경고 상태 전이(순수 reducer). 주행 중에만 경고하고(정차 중에는 음성도 배너도 없음), 경고는 음성(TTS)과
 * 3초 배너뿐이다. 같은 상황은 [SIGNATURE_COOLDOWN_MS], 어떤 경고든 [MIN_GAP_MS] 간격을 둔다.
 */
object WarnFlow {
    const val BANNER_MS = 3_000L
    const val SIGNATURE_COOLDOWN_MS = 30_000L
    const val MIN_GAP_MS = 8_000L
    const val MAX_SIGNATURES = 16

    fun reduce(st: WarnState, e: WarnEvent): Pair<WarnState, List<WarnEffect>> = when (e) {
        is WarnEvent.DriveChanged ->
            if (e.driving) st.copy(driving = true) to emptyList()
            else st.copy(driving = false, banner = null) to listOf(WarnEffect.CancelSpeech)
        is WarnEvent.Tick ->
            (if (st.banner != null && e.tMs >= st.banner.untilMs) st.copy(banner = null) else st) to emptyList()
        is WarnEvent.Nearby -> nearby(st, e)
    }

    private fun nearby(st: WarnState, e: WarnEvent.Nearby): Pair<WarnState, List<WarnEffect>> {
        if (!st.driving) return st to emptyList()
        st.lastSpokeAt?.let { if (e.tMs - it < MIN_GAP_MS) return st to emptyList() }
        val pick = e.candidates
            .filter { it.grade == RiskGrade.HIGH }
            .filter { c -> st.lastBySignature[sig(c)]?.let { e.tMs - it >= SIGNATURE_COOLDOWN_MS } ?: true }
            .minWithOrNull(compareBy<Candidate> { it.distanceM }.thenBy { it.direction.ordinal }) ?: return st to emptyList()
        val sigs = (st.lastBySignature + (sig(pick) to e.tMs)).entries.sortedByDescending { it.value }.take(MAX_SIGNATURES).associate { it.key to it.value }
        val next = st.copy(banner = Banner(WarnText.banner(pick), e.tMs + BANNER_MS), lastSpokeAt = e.tMs, lastBySignature = sigs)
        return next to listOf(WarnEffect.Speak(WarnText.voice(pick)))
    }

    private fun sig(c: Candidate) = "${c.direction.group}|${c.kind?.name}"
}

object WarnText {
    private fun who(c: Candidate) = c.kind?.let { "${it.phrase} 차량" } ?: "위험 등급 차량"

    fun voice(c: Candidate): String = when (c.direction) {
        Direction.FRONT, Direction.FRONT_LEFT, Direction.FRONT_RIGHT -> "${c.direction.label}에 ${who(c)}이 있습니다. 차간 거리를 넉넉히 두세요."
        Direction.NEAR -> "가까운 곳에 ${who(c)}이 있습니다. 주의하세요."
        else -> "${c.direction.label}에 ${who(c)}이 있습니다. 차로 변경에 주의하세요."
    }

    fun banner(c: Candidate): String =
        "${c.direction.label} · ${c.kind?.label?.let { "$it 주의" } ?: "위험 차량 주의"}"
}

/** 서버 응답(셀 단위 위치) + 내 위치·진행 방향 -> 경고 후보. 반경 밖·반대 방향은 제외한다. */
object NearbyMapper {
    const val RADIUS_M = 200.0
    /** 상대 위치가 셀 중심뿐이라 생기는 오차 허용치 */
    const val CELL_SLACK_M = 100.0

    fun candidates(myLat: Double, myLon: Double, headingDeg: Double, list: List<NearbyVehicle>): List<Candidate> {
        val mySector = GeoMath.headingSector(headingDeg)
        return list.mapNotNull { v ->
            if (!GeoMath.sameSector(mySector, v.heading8)) return@mapNotNull null
            val cell = Geohash.decode(v.geohash) ?: return@mapNotNull null
            val (along, lateral) = GeoMath.alongLateral(myLat, myLon, headingDeg, cell.centerLat, cell.centerLon)
            val dist = GeoMath.distanceM(along to lateral)
            if (dist > RADIUS_M + CELL_SLACK_M) null
            else Candidate(DirectionCalc.relative(along, lateral), v.kind, v.grade, dist, Approach.relativeBearing(along, lateral))
        }
    }
}
