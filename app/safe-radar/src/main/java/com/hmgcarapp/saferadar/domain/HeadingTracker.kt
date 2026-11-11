package com.hmgcarapp.saferadar.domain

/**
 * 진행 방향 추정(순수). SDK 가 방위를 주면([reported]) 그대로 쓰고, 없거나 범위 밖이면 이전 위치에서 [MIN_MOVE_M] 이상 움직인 경우에만
 * 이동 방위를 계산한다. 움직임이 작으면 마지막으로 알려진 방위를 유지(없으면 null).
 */
class HeadingTracker {
    private var lastLat: Double? = null
    private var lastLon: Double? = null
    private var heading: Double? = null

    fun reset() { lastLat = null; lastLon = null; heading = null }

    fun update(lat: Double, lon: Double, reported: Float?): Double? {
        val r = reported?.takeIf { it.isFinite() && it >= 0f && it < 360f }?.toDouble()
        val pl = lastLat; val po = lastLon
        if (r != null) {
            heading = r; lastLat = lat; lastLon = lon
        } else if (pl != null && po != null) {
            if (GeoMath.haversineM(pl, po, lat, lon) >= MIN_MOVE_M) { heading = GeoMath.bearingDeg(pl, po, lat, lon); lastLat = lat; lastLon = lon }
        } else { lastLat = lat; lastLon = lon }
        return heading
    }

    companion object { const val MIN_MOVE_M = 8.0 }
}
