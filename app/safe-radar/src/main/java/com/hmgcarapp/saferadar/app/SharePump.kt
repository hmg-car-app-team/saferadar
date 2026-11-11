package com.hmgcarapp.saferadar.app

import com.hmgcarapp.saferadar.domain.AnonIdRotator
import com.hmgcarapp.saferadar.domain.Geohash
import com.hmgcarapp.saferadar.domain.GeoMath
import com.hmgcarapp.saferadar.domain.NearbyVehicle
import com.hmgcarapp.saferadar.domain.Report
import com.hmgcarapp.saferadar.domain.RiskGrade
import com.hmgcarapp.saferadar.domain.RiskKind
import com.hmgcarapp.saferadar.domain.ShareGate
import com.hmgcarapp.saferadar.domain.ShareStatus
import com.hmgcarapp.saferadar.net.ShareApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** [SharePump] 판단에 필요한 현재 상태. 위치는 최근 값이 있을 때만 채운다(없으면 null). */
data class ShareInput(
    val consent: Boolean, val demo: Boolean, val driving: Boolean, val speedKmh: Float?,
    val lat: Double?, val lon: Double?, val headingDeg: Double?,
    val grade: RiskGrade, val kind: RiskKind?,
)

/**
 * 서버 전송의 유일한 관문(코루틴·SDK 없이 테스트 가능). 동의·서버·주행·속도·최근 위치가 모두 맞아야 [Report] 를 만들고,
 * 3초 간격·동시 1건으로 제한하며, 실패/철회를 상태로 기억한다. 호출은 모두 메인 스레드(컨트롤러)에서 한다.
 */
class SharePump(
    private val api: ShareApi?,
    private val serverUrl: String?,
    private val idRotator: AnonIdRotator,
    private val everyMs: Long = EVERY_MS,
) {
    var failed: Boolean = false; private set
    private var inFlight = false
    private var lastAt: Long? = null
    /** 마지막으로 보낸 익명 ID: 바뀌면 이전 ID 를 서버에서 지우도록 [takePendingLeave] 로 넘긴다. */
    private var lastId: String? = null
    private var pendingLeave: String? = null
    /** 현재 ID 로 보고한 (셀, 방향, 시각). ID 가 바뀌는 순간 [prevOwn] 으로 넘어간다(현재 ID 보고는 걸러내기 대상이 아니다). */
    private val curOwn = ArrayDeque<Triple<String, Int, Long>>()
    /** 교체 직전까지 이전 ID 로 보고한 (셀, 방향): 서버에 남은 이전 항목이 응답에 되돌아오는 것을 걸러내는 데 쓴다. */
    private var prevOwn: List<Pair<String, Int>> = emptyList()
    private var suppressUntil: Long? = null
    /** 철회마다 올라가는 세대: 전송 도중 철회를 알아채는 데 쓴다(IO 스레드에서도 읽는다). */
    @Volatile var generation: Int = 0; private set

    fun status(i: ShareInput): ShareStatus = ShareGate.status(
        consent = i.consent, serverUrl = serverUrl, demo = i.demo, driving = i.driving,
        hasFreshPosition = i.lat != null && i.lon != null && i.headingDeg != null, speedKmh = i.speedKmh, lastFailed = failed,
    )

    /** 지금 보내야 하면 보낼 [Report](익명 ID·셀·방향·등급·유형뿐), 아니면 null. 반환 시 전송 중 상태가 된다 -> [finish] 필수. */
    fun prepare(nowMs: Long, i: ShareInput): Report? {
        if (api == null || !ShareGate.mayConnect(status(i)) || inFlight) return null
        lastAt?.let { if (nowMs - it < everyMs) return null }
        val lat = i.lat ?: return null; val lon = i.lon ?: return null; val hd = i.headingDeg ?: return null
        // 값 검증과 변환은 전송 중 상태로 바꾸기 전에: 잘못된 좌표(NaN·범위 밖)가 와도 다음 틱에 다시 시도할 수 있다
        if (!lat.isFinite() || !lon.isFinite() || !hd.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        val gh = Geohash.encode(lat, lon)
        val sector = GeoMath.headingSector(hd)
        val id = idRotator.current()
        lastAt = nowMs; inFlight = true
        val prev = lastId
        if (prev != null && prev != id) {
            pendingLeave = prev // 교체 직전 ID 는 서버에서 지운다
            curOwn.removeAll { nowMs - it.third > SELF_WINDOW_MS } // 15초 창 밖의 오래된 셀은 걸러내기 대상에서 뺀다
            prevOwn = curOwn.map { it.first to it.second }.distinct() // 이전 ID 의 최근 보고 위치만 걸러내기 대상
            curOwn.clear()
            suppressUntil = nowMs + SELF_WINDOW_MS
        }
        lastId = id
        curOwn.addLast(Triple(gh, sector, nowMs)) // 새 ID 보고는 억제 대상에 넣지 않는다
        while (curOwn.isNotEmpty() && nowMs - curOwn.first().third > SELF_WINDOW_MS) curOwn.removeFirst()
        return Report(id, gh, sector, i.grade, i.kind)
    }

    /** ID 교체로 서버에서 지워야 할 이전 ID(한 번만 돌려준다). */
    fun takePendingLeave(): String? = pendingLeave.also { pendingLeave = null }

    /** ID 교체 직후 [SELF_WINDOW_MS] 동안, 이전 ID 로 보고했던 (셀, 방향) 과 같은 응답 항목은 서버에 남은 내 이전 항목으로 보고 버린다. */
    fun filterSelf(list: List<NearbyVehicle>, nowMs: Long): List<NearbyVehicle> {
        val until = suppressUntil ?: return list
        if (nowMs > until) return list
        return list.filterNot { v -> prevOwn.any { it.first == v.geohash && it.second == v.heading8 } }
    }

    /** [send] 결과. */
    sealed interface SendResult {
        /** 전송 직전 철회를 봐서 보내지 않았다. */
        data object Skipped : SendResult
        data object Failed : SendResult
        /** [revoked] = 응답을 받은 직후 철회가 확인됨(이때 같은 ID 로 leave 를 한 번 더 보냈다; 결과는 쓰지 않는다). */
        data class Ok(val list: List<NearbyVehicle>, val revoked: Boolean) : SendResult
    }

    /**
     * 전송 한 건을 수행한다(IO 스레드). [gen] 은 prepare 시점의 [generation], [consent] 는 현재 동의 여부.
     * 전송 직전에 철회(세대 변경·동의 꺼짐)를 보면 보내지 않고, 응답 직후 철회를 보면(report 가 이미 서버에 닿았을 수 있어) 같은 ID 로 leave 를 다시 보낸다.
     */
    suspend fun send(r: Report, gen: Int, consent: () -> Boolean): SendResult {
        val client = api ?: return SendResult.Skipped
        if (gen != generation || !consent()) return SendResult.Skipped
        val list = try { client.report(r) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (t: Throwable) {
            if (gen != generation || !consent()) leave(r.id) // 예외여도 서버에 닿았을 수 있다
            return SendResult.Failed
        }
        val revoked = gen != generation || !consent()
        if (revoked) leave(r.id)
        return SendResult.Ok(list, revoked)
    }

    /** [gen] 이 이미 낡았으면(철회 이후) 실패 표시를 남기지 않는다. */
    fun finish(ok: Boolean, gen: Int = generation) { failed = !ok && gen == generation; inFlight = false }

    /** 동의 철회: 현재 익명 ID 를 폐기하고, 서버에서 지워야 할 이전 ID 를 돌려준다(없으면 null). 다음 전송은 새 ID 로 시작. */
    fun revoke(): String? {
        val old = idRotator.peek()
        idRotator.clear()
        failed = false; generation++
        lastId = null; pendingLeave = null; suppressUntil = null; curOwn.clear(); prevOwn = emptyList()
        return old
    }

    /** 삭제 요청: 호출자가 취소돼도(release·scope.cancel) 끝까지, 단 [LEAVE_TIMEOUT_MS] 안에서만 시도한다. */
    suspend fun leave(id: String) {
        val client = api ?: return
        withContext(NonCancellable) {
            try { withTimeoutOrNull(LEAVE_TIMEOUT_MS) { client.leave(id) } } catch (t: Throwable) { }
        }
    }

    companion object { const val EVERY_MS = 3_000L
        /** 서버 TTL(15초)과 같은 길이 */
        const val SELF_WINDOW_MS = 15_000L
        const val LEAVE_TIMEOUT_MS = 3_000L }
}
