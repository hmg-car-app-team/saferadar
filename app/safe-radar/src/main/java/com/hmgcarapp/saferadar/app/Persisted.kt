package com.hmgcarapp.saferadar.app

import com.hmgcarapp.saferadar.domain.Deduction
import com.hmgcarapp.saferadar.domain.RiskKind
import com.hmgcarapp.saferadar.domain.Trends
import com.hmgcarapp.saferadar.domain.TripRecord
import com.hmgcarapp.saferadar.domain.TripSnapshot
import org.json.JSONArray
import org.json.JSONObject

/**
 * 설정. [consent] 는 **기본 꺼짐**이며 동의 화면에서 사용자가 직접 켠 경우에만 true.
 * [consentAsked] 는 동의 화면을 이미 보여줬는지(나중에 선택 시 반복해서 띄우지 않기).
 */
data class Settings(val consent: Boolean = false, val consentAsked: Boolean = false, val background: Boolean = true, val voiceWarn: Boolean = true)

/** 디스크에 쓰는 전부. 위치·좌표·경로·익명 ID·기기 식별자는 없다. */
data class Persisted(
    val settings: Settings = Settings(),
    val trips: List<TripRecord> = emptyList(),
    /** 진행 중이던 주행(프로세스 종료 시 복원 때 기록으로 확정) */
    val pending: TripSnapshot? = null,
    val savedAtWallMs: Long = 0L,
)

object StateCodec {
    const val VERSION = 1
    private val KINDS = RiskKind.entries.size

    data class Parsed(val p: Persisted, val corrupt: Boolean)

    private fun counts(a: JSONArray?): List<Int> = List(KINDS) { i -> (a?.optInt(i, 0) ?: 0).coerceIn(0, 100_000) }

    fun encode(p: Persisted): JSONObject = JSONObject()
        .put("v", VERSION)
        .put("consent", p.settings.consent).put("consentAsked", p.settings.consentAsked)
        .put("background", p.settings.background).put("voiceWarn", p.settings.voiceWarn)
        .put("savedAt", p.savedAtWallMs)
        .put("trips", JSONArray().also { a ->
            p.trips.takeLast(Trends.MAX_TRIPS).forEach { t ->
                a.put(JSONObject().put("at", t.endWallMs).put("s", t.score).put("km", t.distanceKm.toDouble()).put("m", t.durationMin).put("c", JSONArray(t.counts)))
            }
        })
        .also { o ->
            p.pending?.let { s ->
                o.put("pending", JSONObject().put("dm", s.distanceM).put("ms", s.drivingMs).put("c", JSONArray(s.counts))
                    .put("d", JSONArray().also { a -> s.deductions.take(200).forEach { a.put(JSONArray().put(it.kind.code).put(it.atMin)) } }))
            }
        }

    /** 읽을 수 없는 JSON 이면 corrupt=true + 기본값. 값은 범위로 보정(sanitize)한다. */
    fun parse(text: String): Parsed = try {
        val o = JSONObject(text)
        val trips = ArrayList<TripRecord>()
        o.optJSONArray("trips")?.let { a ->
            for (i in 0 until a.length()) a.optJSONObject(i)?.let { t ->
                trips += TripRecord(
                    t.optLong("at", 0L).coerceAtLeast(0L), t.optInt("s", 0).coerceIn(0, 100),
                    t.optDouble("km", 0.0).toFloat().coerceIn(0f, 100_000f), t.optInt("m", 0).coerceIn(0, 24 * 60 * 7), counts(t.optJSONArray("c")),
                )
            }
        }
        val pending = o.optJSONObject("pending")?.let { s ->
            val ded = ArrayList<Deduction>()
            s.optJSONArray("d")?.let { a ->
                for (i in 0 until minOf(a.length(), 200)) a.optJSONArray(i)?.let { d ->
                    RiskKind.fromCode(d.optInt(0, -1))?.let { k -> ded += Deduction(k, d.optInt(1, 0).coerceIn(0, 24 * 60)) }
                }
            }
            TripSnapshot(s.optDouble("dm", 0.0).coerceIn(0.0, 1.0e8), s.optLong("ms", 0L).coerceIn(0L, 7L * 24 * 3_600_000L), counts(s.optJSONArray("c")), ded)
        }
        Parsed(
            Persisted(
                Settings(o.optBoolean("consent", false), o.optBoolean("consentAsked", false), o.optBoolean("background", true), o.optBoolean("voiceWarn", true)),
                trips.takeLast(Trends.MAX_TRIPS), pending, o.optLong("savedAt", 0L).coerceAtLeast(0L),
            ),
            corrupt = false,
        )
    } catch (t: Throwable) { Parsed(Persisted(), corrupt = true) }
}
