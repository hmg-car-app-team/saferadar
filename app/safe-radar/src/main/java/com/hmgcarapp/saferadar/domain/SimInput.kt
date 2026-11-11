package com.hmgcarapp.saferadar.domain

import com.hmgcarapp.core.drive.Gear

/** 시연 broadcast extra 해석(순수). 형식·범위 오류는 null 로 돌려 무시한다. */
object SimInput {
    fun number(raw: Any?): Double? = (raw as? Number)?.toDouble()?.takeIf { it.isFinite() }

    fun speed(raw: Any?): Float? = number(raw)?.toFloat()?.takeIf { it in 0f..300f }

    fun gear(s: String?): Gear? = when (s?.uppercase()) { "P", "PARK" -> Gear.PARK; "D", "DRIVE" -> Gear.DRIVE; "R", "REVERSE" -> Gear.REVERSE; "N", "NEUTRAL" -> Gear.NEUTRAL; else -> null }
}
