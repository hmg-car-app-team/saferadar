package com.hmgcarapp.saferadar.domain

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/** Geohash(표준 base32). 서버(Python)와 같은 테스트 벡터로 일치를 확인한다. */
object Geohash {
    private const val ALPHABET = "0123456789bcdefghjkmnpqrstuvwxyz"
    /** 전송 정밀도: 7자 ≈ 153m x 153m 셀(위도 방향 약 ±76m). 낮은 정밀도로 정확 좌표를 보내지 않는다. */
    const val SEND_PRECISION = 7

    data class Cell(val latMin: Double, val latMax: Double, val lonMin: Double, val lonMax: Double) {
        val centerLat: Double get() = (latMin + latMax) / 2
        val centerLon: Double get() = (lonMin + lonMax) / 2
    }

    fun isValid(s: String, precision: Int = SEND_PRECISION): Boolean = s.length == precision && s.all { it in ALPHABET }

    fun encode(lat: Double, lon: Double, precision: Int = SEND_PRECISION): String {
        require(lat in -90.0..90.0 && lon in -180.0..180.0) { "범위 밖 좌표" }
        var latLo = -90.0; var latHi = 90.0; var lonLo = -180.0; var lonHi = 180.0
        val sb = StringBuilder()
        var bit = 0; var ch = 0; var evenBit = true
        while (sb.length < precision) {
            if (evenBit) {
                val mid = (lonLo + lonHi) / 2
                if (lon >= mid) { ch = ch * 2 + 1; lonLo = mid } else { ch *= 2; lonHi = mid }
            } else {
                val mid = (latLo + latHi) / 2
                if (lat >= mid) { ch = ch * 2 + 1; latLo = mid } else { ch *= 2; latHi = mid }
            }
            evenBit = !evenBit
            if (++bit == 5) { sb.append(ALPHABET[ch]); bit = 0; ch = 0 }
        }
        return sb.toString()
    }

    fun decode(hash: String): Cell? {
        if (hash.isEmpty() || !hash.all { it in ALPHABET }) return null
        var latLo = -90.0; var latHi = 90.0; var lonLo = -180.0; var lonHi = 180.0
        var evenBit = true
        for (c in hash) {
            val v = ALPHABET.indexOf(c)
            for (i in 4 downTo 0) {
                val b = (v shr i) and 1
                if (evenBit) { val mid = (lonLo + lonHi) / 2; if (b == 1) lonLo = mid else lonHi = mid }
                else { val mid = (latLo + latHi) / 2; if (b == 1) latLo = mid else latHi = mid }
                evenBit = !evenBit
            }
        }
        return Cell(latLo, latHi, lonLo, lonHi)
    }
}

/** 상대 방향(내 진행 방향 기준). */
enum class Direction(val label: String) {
    FRONT("앞쪽"), FRONT_LEFT("왼쪽 앞"), FRONT_RIGHT("오른쪽 앞"), LEFT("왼쪽 옆"), RIGHT("오른쪽 옆"),
    REAR_LEFT("왼쪽 뒤"), REAR_RIGHT("오른쪽 뒤"), REAR("뒤쪽"), NEAR("바로 근처");

    /**
     * 반복 경고 억제용 묶음. 상대 위치가 셀 중심뿐이라 같은 차량도 앞뒤·옆 표기가 시간에 따라 흔들리므로
     * 왼쪽/오른쪽/앞/뒤/근처로 묶어 같은 상황으로 본다.
     */
    val group: String
        get() = when (this) { FRONT_LEFT, LEFT, REAR_LEFT -> "LEFT"; FRONT_RIGHT, RIGHT, REAR_RIGHT -> "RIGHT"; else -> name }
}

object GeoMath {
    const val EARTH_M = 6_371_000.0
    private const val M_PER_DEG_LAT = 111_320.0

    fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
        val dp = p2 - p1; val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * EARTH_M * atan2(sqrt(a), sqrt(1 - a))
    }

    /** 내 위치 기준 상대 위치(미터): along = 진행 방향(앞 +), lateral = 오른쪽 +. 근거리 평면 근사. */
    fun alongLateral(myLat: Double, myLon: Double, headingDeg: Double, lat: Double, lon: Double): Pair<Double, Double> {
        val dy = (lat - myLat) * M_PER_DEG_LAT
        val dx = (lon - myLon) * M_PER_DEG_LAT * cos(Math.toRadians(myLat))
        val h = Math.toRadians(headingDeg)
        val along = dx * sin(h) + dy * cos(h)
        val lateral = dx * cos(h) - dy * sin(h)
        return along to lateral
    }

    /** 각도를 [0, 360) 로. */
    fun norm360(deg: Double): Double = ((deg % 360.0) + 360.0) % 360.0

    /** 진행 방향 0..7 (45° 단위, 0=북). */
    fun headingSector(deg: Double): Int = (((norm360(deg) + 22.5) / 45.0).toInt()) % 8

    /** 두 8방위 섹터가 같은 방향(인접 포함)인지. */
    fun sameSector(a: Int, b: Int): Boolean { val d = abs(a - b) % 8; return minOf(d, 8 - d) <= 1 }

    /** 두 지점 사이 진행 방위(도, 북 0 시계방향). */
    fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dy = lat2 - lat1; val dx = (lon2 - lon1) * cos(Math.toRadians(lat1))
        return norm360(Math.toDegrees(atan2(dx, dy)))
    }

    fun distanceM(a: Pair<Double, Double>): Double = hypot(a.first, a.second)
}

/**
 * 상대 방향 판정(순수). 상대 차량은 Geohash 셀 중심으로만 알 수 있어 위치 오차가 ±수십 m 이다.
 * 그래서 좌우는 **옆 간격이 [SIDE_MIN_M] 이상일 때만** 구분하고, 앞뒤 구분도 [ALONG_MIN_M] 이상일 때만 한다(차로 단위 판정은 불가).
 */
object DirectionCalc {
    const val SIDE_MIN_M = 40.0
    const val ALONG_MIN_M = 40.0

    fun relative(along: Double, lateral: Double): Direction {
        val side = when { lateral <= -SIDE_MIN_M -> -1; lateral >= SIDE_MIN_M -> 1; else -> 0 }
        val fb = when { along >= ALONG_MIN_M -> 1; along <= -ALONG_MIN_M -> -1; else -> 0 }
        return when {
            side == 0 && fb == 0 -> Direction.NEAR
            side == 0 -> if (fb > 0) Direction.FRONT else Direction.REAR
            fb == 0 -> if (side < 0) Direction.LEFT else Direction.RIGHT
            fb > 0 -> if (side < 0) Direction.FRONT_LEFT else Direction.FRONT_RIGHT
            else -> if (side < 0) Direction.REAR_LEFT else Direction.REAR_RIGHT
        }
    }
}
