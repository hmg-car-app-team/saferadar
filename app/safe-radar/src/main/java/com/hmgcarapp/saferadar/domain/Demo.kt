package com.hmgcarapp.saferadar.domain

/** 시연 프레임: 시연 중 주입되는 가상 차량 신호·위치와 가상 주변 차량. 모두 가짜 데이터다. */
data class DemoFrame(
    val speedKmh: Float,
    val steerDeg: Float,
    val brake: Boolean,
    val turn: TurnSignal,
    val lat: Double,
    val lon: Double,
    val headingDeg: Float,
    val finished: Boolean,
    val others: List<NearbyVehicle>,
)

/**
 * 시연 시나리오(순수, 시각 t 의 함수). 가상 좌표에서 북쪽으로 달리며 차례로 다음을 일으킨다:
 * 방향지시등 없는 차로변경(~25초) -> 급감속(~33초) -> 급가속·급격한 고속 진입(~41초) -> 고속 지속(~64초) -> 완만한 감속 -> 정차(~123초). 전체 약 2분 12초(점수 산정 최소 2분을 넘기기 위함).
 * 가상 차량 A(왼쪽 뒤 셀, 급차선변경)는 36~48초, B(앞쪽 셀, 급제동)는 58~66초에 나타난다.
 * 가상 차량은 실제와 같은 경로(Geohash 셀 -> 셀 중심 -> 상대 방향)를 거치도록 셀 문자열로 만든다.
 */
object DemoScript {
    const val LAT0 = 37.5000
    const val LON0 = 127.0300
    const val DURATION_MS = 132_000L
    private const val M_PER_DEG = 111_320.0

    /** (초, km/h) 키프레임: 구간 사이는 선형. */
    private val SPEED = listOf(0.0 to 0.0, 10.0 to 60.0, 25.0 to 100.0, 31.0 to 100.0, 34.0 to 60.0, 40.0 to 60.0, 43.0 to 100.0, 55.0 to 135.0, 70.0 to 135.0, 90.0 to 100.0, 105.0 to 100.0, 123.0 to 0.0, 132.0 to 0.0)

    fun speedKmhAt(tMs: Long): Double {
        val t = tMs / 1000.0
        if (t <= SPEED.first().first) return SPEED.first().second
        for (i in 1 until SPEED.size) {
            val (t1, v1) = SPEED[i]
            if (t <= t1) { val (t0, v0) = SPEED[i - 1]; return v0 + (v1 - v0) * (t - t0) / (t1 - t0) }
        }
        return SPEED.last().second
    }

    /** 누적 주행 거리(m): 선형 구간의 사다리꼴 적분(해석적). */
    fun distanceMAt(tMs: Long): Double {
        val t = (tMs / 1000.0).coerceAtLeast(0.0)
        var d = 0.0
        for (i in 1 until SPEED.size) {
            val (t0, v0) = SPEED[i - 1]; val (t1, v1) = SPEED[i]
            if (t <= t0) break
            val te = minOf(t, t1)
            val ve = v0 + (v1 - v0) * (te - t0) / (t1 - t0)
            d += (v0 + ve) / 2.0 / 3.6 * (te - t0)
        }
        return d
    }

    /** 조향각: 24.5s +8°, 25.5s -8° (방향지시등 없음). */
    private fun steerAt(t: Double): Double = when {
        t < 24.0 || t > 26.6 -> 0.0
        t < 24.5 -> 16.0 * (t - 24.0)
        t < 25.5 -> 8.0 - 16.0 * (t - 24.5)
        else -> -8.0 + 8.0 * (t - 25.5) / 1.1
    }

    fun frameAt(tMs: Long): DemoFrame {
        val t = tMs / 1000.0
        val dist = distanceMAt(tMs)
        val lat = LAT0 + dist / M_PER_DEG
        val others = ArrayList<NearbyVehicle>()
        if (t in 36.0..48.0) others += vehicleAt(lat, LON0, dLat = -1, dLon = -1, kind = RiskKind.LANE_CHANGE)
        if (t in 58.0..66.0) others += vehicleAt(lat, LON0, dLat = 1, dLon = 0, kind = RiskKind.HARD_BRAKE)
        return DemoFrame(
            speedKmh = speedKmhAt(tMs).toFloat(),
            steerDeg = steerAt(t).toFloat(),
            brake = t in 31.5..35.0,
            turn = TurnSignal.OFF,
            lat = lat, lon = LON0, headingDeg = 0f,
            finished = tMs >= DURATION_MS,
            others = others,
        )
    }

    /**
     * 내 Geohash 셀을 기준으로 (위도 방향 [dLat]칸, 경도 방향 [dLon]칸) 떨어진 셀 중심에 가상 차량을 둔다.
     * 상대 위치는 셀 중심으로만 전달되므로(차로 단위 불가) 셀 칸 수로 지정하면 방향 판정이 내 셀 안 위치와 무관하게 결정적이다
     * (북향 주행: dLat=-1,dLon=-1 -> 왼쪽 뒤, dLat=+1,dLon=0 -> 앞쪽).
     */
    private fun vehicleAt(myLat: Double, myLon: Double, dLat: Int, dLon: Int, kind: RiskKind): NearbyVehicle {
        val cell = Geohash.decode(Geohash.encode(myLat, myLon))!!
        val lat = cell.centerLat + dLat * (cell.latMax - cell.latMin)
        val lon = cell.centerLon + dLon * (cell.lonMax - cell.lonMin)
        return NearbyVehicle(Geohash.encode(lat, lon), 0, RiskGrade.HIGH, kind)
    }
}
