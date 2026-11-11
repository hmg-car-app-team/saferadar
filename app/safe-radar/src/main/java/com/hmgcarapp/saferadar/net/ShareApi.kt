package com.hmgcarapp.saferadar.net

import com.hmgcarapp.saferadar.domain.NearbyVehicle
import com.hmgcarapp.saferadar.domain.Report
import com.hmgcarapp.saferadar.domain.Wire
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** 근접 매칭 서버 클라이언트 포트. 구현은 예외를 던져도 되며 호출부가 실패로 처리한다. */
interface ShareApi {
    /** 내 상태를 올리고 주변 위험 등급 차량을 받는다. */
    suspend fun report(r: Report): List<NearbyVehicle>
    /** 동의 철회 시 서버에 남은 내 항목을 즉시 지우도록 요청(best effort). */
    suspend fun leave(id: String)
}

/**
 * HTTP(S) 구현. 주소는 [com.hmgcarapp.saferadar.domain.ServerUrlPolicy] 를 통과한 값만 받는다.
 * 요청 본문은 [Report] 한 건뿐이며 인증 토큰·쿠키·기기 식별 헤더를 보내지 않는다. 응답은 [Wire.MAX_RESPONSE_BYTES] 로 제한한다.
 * 로그에는 좌표·ID 를 남기지 않는다(호출부도 예외 종류만 기록).
 */
class HttpShareApi(private val baseUrl: String, private val timeoutMs: Int = 3_000) : ShareApi {
    override suspend fun report(r: Report): List<NearbyVehicle> =
        Wire.parseNearby(post("/v1/report", Wire.encodeReport(r)))

    override suspend fun leave(id: String) { post("/v1/leave", Wire.encodeLeave(id)) }

    private fun post(path: String, body: String): String {
        val c = URL(baseUrl + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs
            c.instanceFollowRedirects = false
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            val bytes = body.toByteArray(Charsets.UTF_8)
            c.setFixedLengthStreamingMode(bytes.size)
            c.outputStream.use { it.write(bytes) }
            val code = c.responseCode
            if (code !in 200..299) throw java.io.IOException("HTTP $code")
            return readCapped(c.inputStream)
        } finally { c.disconnect() }
    }

    private fun readCapped(input: java.io.InputStream): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(2048)
        input.use { s ->
            while (true) {
                val n = s.read(buf)
                if (n < 0) break
                if (out.size() + n > Wire.MAX_RESPONSE_BYTES) throw java.io.IOException("응답이 너무 큼")
                out.write(buf, 0, n)
            }
        }
        return out.toString(Charsets.UTF_8.name())
    }
}
