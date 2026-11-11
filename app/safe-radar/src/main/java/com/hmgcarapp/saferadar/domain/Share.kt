package com.hmgcarapp.saferadar.domain

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/**
 * 주기적으로 교체되는 익명 ID. 기기·계정과 무관한 무작위 값이며 **저장하지 않는다**(프로세스가 죽으면 새 값).
 * [rotateMs] 가 지나면(단조 시계) 새 무작위 값으로 바뀌고, 동의 철회 때는 [rotateNow] 로 즉시 바꾼다.
 */
class AnonIdRotator(
    private val rotateMs: Long = ROTATE_MS,
    private val monoClock: () -> Long,
    private val random: () -> ByteArray = { ByteArray(16).also { java.security.SecureRandom().nextBytes(it) } },
) {
    private var id: String? = null
    private var since = 0L

    @Synchronized fun current(): String {
        val now = monoClock()
        val cur = id
        if (cur == null || now - since >= rotateMs) return renew(now)
        return cur
    }

    @Synchronized fun rotateNow(): String = renew(monoClock())

    @Synchronized fun clear() { id = null }

    /** 새로 만들지 않고 현재 값만 본다(없으면 null). 동의 철회 시 서버 항목 삭제 요청에 쓴다. */
    @Synchronized fun peek(): String? = id

    private fun renew(now: Long): String {
        val bytes = random()
        require(bytes.size == 16) { "익명 ID 원천은 16바이트" }
        val next = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        id = next; since = now
        return next
    }

    companion object { const val ROTATE_MS = 10 * 60_000L }
}

/** 서버로 보내는 전부. 식별정보·정확 좌표·속도·점수 이력은 없다. */
data class Report(val id: String, val geohash: String, val heading8: Int, val grade: RiskGrade, val kind: RiskKind?)

/** 서버가 돌려주는 주변 위험 등급 차량(익명, 셀 단위 위치). */
data class NearbyVehicle(val geohash: String, val heading8: Int, val grade: RiskGrade, val kind: RiskKind?)

object Wire {
    const val MAX_RESPONSE_BYTES = 16 * 1024
    const val MAX_NEARBY = 20
    val ID_REGEX = Regex("[0-9a-f]{32}")

    fun encodeReport(r: Report): String = JSONObject()
        .put("id", r.id).put("gh", r.geohash).put("hd", r.heading8).put("g", r.grade.code).put("k", r.kind?.code ?: -1)
        .toString()

    fun encodeLeave(id: String): String = JSONObject().put("id", id).toString()

    /** 형식이 어긋난 항목은 버린다(최대 [MAX_NEARBY]개). 전체가 JSON 이 아니면 빈 목록. */
    fun parseNearby(text: String): List<NearbyVehicle> = try {
        val arr = JSONObject(text).optJSONArray("near") ?: JSONArray()
        (0 until minOf(arr.length(), MAX_NEARBY)).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val gh = o.optString("gh", "")
            val hd = o.optInt("hd", -1)
            val g = RiskGrade.fromCode(o.optInt("g", -1))
            if (!Geohash.isValid(gh) || hd !in 0..7 || g == null) null
            else NearbyVehicle(gh, hd, g, RiskKind.fromCode(o.optInt("k", -1)))
        }
    } catch (t: Throwable) { emptyList() }
}

/** 서버 주소 검증. https 는 허용, http 는 debuggable 빌드에서 로컬 개발 주소(10.0.2.2·localhost·127.0.0.1)만 허용한다. */
object ServerUrlPolicy {
    private val LOCAL_HOSTS = setOf("10.0.2.2", "localhost", "127.0.0.1")

    /** 정상이면 끝 슬래시를 뗀 기본 주소, 아니면 null(= 전송 안 함). */
    fun validate(raw: String?, debuggable: Boolean): String? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty() || s.length > 200) return null
        val u = try { URI(s) } catch (t: Throwable) { return null }
        val scheme = u.scheme?.lowercase() ?: return null
        val host = u.host?.lowercase() ?: return null
        if (u.userInfo != null || u.query != null || u.fragment != null || u.rawPath.orEmpty().let { it.isNotEmpty() && it != "/" }) return null
        val ok = when (scheme) {
            "https" -> true
            "http" -> debuggable && host in LOCAL_HOSTS
            else -> false
        }
        if (!ok) return null
        return s.trimEnd('/')
    }
}

enum class ShareStatus(val label: String) {
    NO_CONSENT("동의 전: 위치를 보내지 않아요"),
    NO_SERVER("서버 미설정: 위치를 보내지 않아요"),
    DEMO("시연 중: 서버로 보내지 않아요"),
    WAITING("대기 중: 위치 또는 주행 확인 전"),
    ACTIVE("공유 중(익명)"),
    ERROR("서버에 연결하지 못했어요"),
}

/** 서버 전송 허용 판정(순수). 이 중 하나라도 어긋나면 위치를 보내지 않는다. */
object ShareGate {
    /** 이 속도(km/h) 미만에서는 보내지 않는다(정차·주차 중 위치가 서버에 머물지 않게). */
    const val MIN_SPEED_KMH = 20f

    fun status(consent: Boolean, serverUrl: String?, demo: Boolean, driving: Boolean, hasFreshPosition: Boolean, speedKmh: Float?, lastFailed: Boolean): ShareStatus = when {
        demo -> ShareStatus.DEMO // 시연 데이터는 동의 여부와 무관하게 서버로 가지 않는다
        !consent -> ShareStatus.NO_CONSENT
        serverUrl == null -> ShareStatus.NO_SERVER
        !driving || !hasFreshPosition || speedKmh == null || speedKmh < MIN_SPEED_KMH -> ShareStatus.WAITING
        lastFailed -> ShareStatus.ERROR
        else -> ShareStatus.ACTIVE
    }

    fun mayConnect(s: ShareStatus) = s == ShareStatus.ACTIVE || s == ShareStatus.ERROR
}
