package com.hmgcarapp.saferadar

import com.hmgcarapp.saferadar.domain.AnonIdRotator
import com.hmgcarapp.saferadar.domain.Report
import com.hmgcarapp.saferadar.domain.RiskGrade
import com.hmgcarapp.saferadar.domain.RiskKind
import com.hmgcarapp.saferadar.domain.ServerUrlPolicy
import com.hmgcarapp.saferadar.domain.ShareGate
import com.hmgcarapp.saferadar.domain.ShareStatus
import com.hmgcarapp.saferadar.domain.Wire
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareTest {
    // ---------------- 익명 ID 교체 ----------------
    private class Rand { var n = 0; fun next(): ByteArray { n++; return ByteArray(16) { n.toByte() } } }

    @Test fun anonIdRotatesEvery10Minutes() {
        var now = 0L
        val r = Rand()
        val rot = AnonIdRotator(monoClock = { now }, random = r::next)
        val a = rot.current()
        assertEquals("01010101010101010101010101010101", a)
        now = 599_999; assertEquals("10분 전에는 같은 값", a, rot.current())
        now = 600_000
        val b = rot.current()
        assertEquals("02020202020202020202020202020202", b)
        assertNotEquals(a, b)
        now = 1_199_999; assertEquals(b, rot.current())
        now = 1_200_000; assertEquals("03030303030303030303030303030303", rot.current())
        assertEquals("원천은 호출마다 한 번씩만", 3, r.n)
    }

    @Test fun anonIdFormatPeekClearAndRotateNow() {
        var now = 0L
        val rot = AnonIdRotator(monoClock = { now })
        assertNull("사용 전에는 없음", rot.peek())
        val a = rot.current()
        assertTrue(Wire.ID_REGEX.matches(a))
        assertEquals(a, rot.peek())
        val b = rot.rotateNow()
        assertNotEquals(a, b)
        rot.clear(); assertNull(rot.peek())
        assertNotEquals(b, rot.current())
    }

    @Test fun anonIdRejectsWrongRandomLength() {
        val rot = AnonIdRotator(monoClock = { 0L }, random = { ByteArray(8) })
        try { rot.current(); throw AssertionError("길이가 다르면 거부") } catch (e: IllegalArgumentException) { }
    }

    // ---------------- 전송 본문: 정해진 5개 키뿐 ----------------
    @Test fun reportBodyContainsOnlyAllowedKeys() {
        val id = "0123456789abcdef0123456789abcdef"
        val j = JSONObject(Wire.encodeReport(Report(id, "wydm6ds", 3, RiskGrade.HIGH, RiskKind.LANE_CHANGE)))
        assertEquals(setOf("id", "gh", "hd", "g", "k"), j.keys().asSequence().toSet())
        assertEquals(id, j.getString("id")); assertEquals("wydm6ds", j.getString("gh"))
        assertEquals(3, j.getInt("hd")); assertEquals(2, j.getInt("g")); assertEquals(2, j.getInt("k"))
        assertEquals(-1, JSONObject(Wire.encodeReport(Report(id, "wydm6ds", 0, RiskGrade.LOW, null))).getInt("k"))
        assertEquals(setOf("id"), JSONObject(Wire.encodeLeave(id)).keys().asSequence().toSet())
        assertTrue("본문은 서버 한도(256B) 이내", Wire.encodeReport(Report(id, "wydm6ds", 7, RiskGrade.HIGH, RiskKind.SPEED_SURGE)).length <= 256)
    }

    @Test fun parseNearbyKeepsValidDropsInvalid() {
        val text = """{"near":[
            {"gh":"wydm6dk","hd":0,"g":2,"k":2},
            {"gh":"wydm6da","hd":0,"g":2,"k":2},
            {"gh":"wydm6dk","hd":8,"g":2,"k":2},
            {"gh":"wydm6dk","hd":0,"g":3,"k":2},
            {"gh":"wydm6du","hd":7,"g":1,"k":9},
            "문자열", 5]}"""
        val got = Wire.parseNearby(text)
        assertEquals(2, got.size)
        assertEquals("wydm6dk", got[0].geohash); assertEquals(RiskGrade.HIGH, got[0].grade); assertEquals(RiskKind.LANE_CHANGE, got[0].kind)
        assertEquals("알 수 없는 유형 코드는 유형만 null", null, got[1].kind); assertEquals(RiskGrade.MID, got[1].grade); assertEquals(7, got[1].heading8)
    }

    @Test fun parseNearbyIsRobust() {
        assertEquals(emptyList<Any>(), Wire.parseNearby("not json"))
        assertEquals(emptyList<Any>(), Wire.parseNearby("{}"))
        assertEquals(emptyList<Any>(), Wire.parseNearby("""{"near":"x"}"""))
        val many = (1..50).joinToString(",", "{\"near\":[", "]}") { """{"gh":"wydm6dk","hd":0,"g":2,"k":0}""" }
        assertEquals("상한 20개", Wire.MAX_NEARBY, Wire.parseNearby(many).size)
    }

    // ---------------- 서버 주소 정책 ----------------
    @Test fun serverUrlPolicy() {
        assertEquals("https://example.com", ServerUrlPolicy.validate("https://example.com", false))
        assertEquals("https://example.com:8443", ServerUrlPolicy.validate(" https://example.com:8443/ ", false))
        assertEquals("http://10.0.2.2:8765", ServerUrlPolicy.validate("http://10.0.2.2:8765", true))
        assertEquals("http://localhost:8765", ServerUrlPolicy.validate("http://localhost:8765", true))
        assertEquals("http://127.0.0.1:8765", ServerUrlPolicy.validate("http://127.0.0.1:8765", true))
        // release: 루프백(adb reverse)만 평문 허용, 에뮬레이터 주소(10.0.2.2)·그 밖의 평문은 금지
        assertEquals("http://127.0.0.1:8765", ServerUrlPolicy.validate("http://127.0.0.1:8765", false))
        assertEquals("http://localhost:8765", ServerUrlPolicy.validate("http://localhost:8765", false))
        assertNull(ServerUrlPolicy.validate("http://10.0.2.2:8765", false))
        assertNull(ServerUrlPolicy.validate("http://192.168.0.5:8765", false))
        assertNull(ServerUrlPolicy.validate("http://example.com", false))
        assertNull(ServerUrlPolicy.validate("http://127.0.0.1.evil.com:8765", false))
        assertNull(ServerUrlPolicy.validate("http://[::1]:8765", false))
        // debug 라도 로컬 개발 주소 외 평문 금지
        assertNull(ServerUrlPolicy.validate("http://example.com", true))
        assertNull(ServerUrlPolicy.validate("http://127.0.0.1.evil.com:8765", true))
        assertNull(ServerUrlPolicy.validate("http://10.0.2.2@" + "evil.com", true))
        assertNull(ServerUrlPolicy.validate("http://192.168.0.5:8765", true))
        // 형식 오류·부가 요소
        listOf("", "   ", "10.0.2.2:8765", "ftp://example.com", "https://", "https://example.com/path", "https://example.com?x=1", "https://example.com#f", "https://user:pw@" + "example.com", "javascript:alert(1)", "https://exa mple.com")
            .forEach { assertNull(it, ServerUrlPolicy.validate(it, true)) }
        assertNull(ServerUrlPolicy.validate(null, true))
        // 정규화·경계
        assertEquals("https://example.com", ServerUrlPolicy.validate("HTTPS://Example.COM", false))
        assertEquals("http://localhost:8765", ServerUrlPolicy.validate("http://LocalHost:8765", false))
        assertNull(ServerUrlPolicy.validate("https://example.com.", false))
        assertNull(ServerUrlPolicy.validate("http://127.0.0.1:", false))
        assertNull(ServerUrlPolicy.validate("http://127.0.0.1:0", false))
        assertNull(ServerUrlPolicy.validate("http://127.0.0.1:65536", false))
        assertEquals("http://127.0.0.1:65535", ServerUrlPolicy.validate("http://127.0.0.1:65535", false))
        assertNull(ServerUrlPolicy.validate("http://2130706433:8765", false))
        assertNull(ServerUrlPolicy.validate("https://2130706433", false))
        assertEquals("127.0.0.1:8765", ServerUrlPolicy.hostPort("http://127.0.0.1:8765"))
        assertNull(ServerUrlPolicy.hostPort(null))
        assertNull(ServerUrlPolicy.validate("https://" + "a".repeat(250) + ".com", true))
    }

    // ---------------- 전송 허용 판정 ----------------
    private fun st(consent: Boolean = true, url: String? = "https://x", demo: Boolean = false, driving: Boolean = true, pos: Boolean = true, speed: Float? = 60f, failed: Boolean = false) =
        ShareGate.status(consent, url, demo, driving, pos, speed, failed)

    @Test fun neverConnectsWithoutConsent() {
        assertEquals(ShareStatus.NO_CONSENT, st(consent = false))
        assertFalse(ShareGate.mayConnect(st(consent = false)))
        assertEquals("동의가 없으면 서버 주소가 있어도 주행 중이어도 보내지 않는다", ShareStatus.NO_CONSENT, st(consent = false, url = "https://x", driving = true, speed = 100f))
    }

    @Test fun gateOrdering() {
        assertEquals(ShareStatus.NO_SERVER, st(url = null))
        assertEquals("시연 데이터는 동의·서버와 무관하게 보내지 않는다", ShareStatus.DEMO, st(demo = true))
        assertEquals(ShareStatus.DEMO, st(consent = false, demo = true))
        assertFalse(ShareGate.mayConnect(ShareStatus.DEMO))
        assertEquals(ShareStatus.WAITING, st(driving = false))
        assertEquals(ShareStatus.WAITING, st(pos = false))
        assertEquals(ShareStatus.WAITING, st(speed = null))
        assertEquals("20km/h 미만은 보내지 않음", ShareStatus.WAITING, st(speed = 19.9f))
        assertEquals(ShareStatus.ACTIVE, st(speed = 20f))
        assertEquals(ShareStatus.ERROR, st(failed = true))
        assertTrue(ShareGate.mayConnect(ShareStatus.ACTIVE)); assertTrue(ShareGate.mayConnect(ShareStatus.ERROR))
        assertFalse(ShareGate.mayConnect(ShareStatus.NO_SERVER)); assertFalse(ShareGate.mayConnect(ShareStatus.WAITING))
    }
}
