package com.hmgcarapp.saferadar

import com.hmgcarapp.core.store.LocalJsonStore
import com.hmgcarapp.saferadar.app.Persisted
import com.hmgcarapp.saferadar.app.Settings
import com.hmgcarapp.saferadar.app.StateCodec
import com.hmgcarapp.saferadar.app.StateStore
import com.hmgcarapp.saferadar.app.WriteResult
import com.hmgcarapp.saferadar.domain.Candidate
import com.hmgcarapp.saferadar.domain.Deduction
import com.hmgcarapp.saferadar.domain.DemoScript
import com.hmgcarapp.saferadar.domain.DriveSample
import com.hmgcarapp.saferadar.domain.EventDetector
import com.hmgcarapp.saferadar.domain.NearbyMapper
import com.hmgcarapp.saferadar.domain.RiskKind
import com.hmgcarapp.saferadar.domain.TripRecord
import com.hmgcarapp.saferadar.domain.TripSnapshot
import com.hmgcarapp.saferadar.domain.TripTracker
import com.hmgcarapp.saferadar.domain.WarnEffect
import com.hmgcarapp.saferadar.domain.WarnEvent
import com.hmgcarapp.saferadar.domain.WarnFlow
import com.hmgcarapp.saferadar.domain.WarnState
import com.hmgcarapp.saferadar.domain.SimInput
import com.hmgcarapp.core.drive.Gear
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DemoAndPersistTest {
    @get:Rule val tmp = TemporaryFolder()

    // ---------------- 시연 시나리오 ----------------
    @Test fun demoSpeedKeyframesAndDistance() {
        assertEquals(0.0, DemoScript.speedKmhAt(0), 1e-9)
        assertEquals(60.0, DemoScript.speedKmhAt(10_000), 1e-9)
        assertEquals("10s 60 -> 25s 100: 15초는 1/3 지점", 73.333, DemoScript.speedKmhAt(15_000), 0.001)
        assertEquals(100.0, DemoScript.speedKmhAt(25_000), 1e-9)
        assertEquals("31s 100 -> 34s 60: 32.5초는 1.5/3 지점", 80.0, DemoScript.speedKmhAt(32_500), 1e-9)
        assertEquals(135.0, DemoScript.speedKmhAt(60_000), 1e-9)
        assertEquals(0.0, DemoScript.speedKmhAt(200_000), 1e-9)
        assertEquals("0~10초 0->60km/h 의 사다리꼴", 83.333, DemoScript.distanceMAt(10_000), 0.001)
        assertEquals("10~20초 60->86.7km/h 추가", 287.037, DemoScript.distanceMAt(20_000), 0.001)
        assertEquals("전체 약 3.09km(키프레임 사다리꼴 합)", 3090.278, DemoScript.distanceMAt(DemoScript.DURATION_MS), 0.01)
    }

    @Test fun demoFramesAtKeyMoments() {
        assertEquals(8.0f, DemoScript.frameAt(24_500).steerDeg, 1e-4f)
        assertEquals(-8.0f, DemoScript.frameAt(25_500).steerDeg, 1e-4f)
        assertEquals(0.0f, DemoScript.frameAt(30_000).steerDeg, 0f)
        assertTrue(DemoScript.frameAt(33_000).brake); assertFalse(DemoScript.frameAt(36_000).brake)
        assertEquals(1, DemoScript.frameAt(40_000).others.size)
        assertEquals(RiskKind.LANE_CHANGE, DemoScript.frameAt(40_000).others.single().kind)
        assertTrue(DemoScript.frameAt(50_000).others.isEmpty())
        assertEquals(RiskKind.HARD_BRAKE, DemoScript.frameAt(60_000).others.single().kind)
        assertFalse(DemoScript.frameAt(131_999).finished); assertTrue(DemoScript.frameAt(132_000).finished)
        assertEquals(0f, DemoScript.frameAt(0).headingDeg, 0f)
    }

    @Test fun demoDrivesTheRealDetectorToTheExpectedEvents() {
        val d = EventDetector()
        val seen = ArrayList<Pair<RiskKind, Long>>()
        var t = 0L
        while (t <= DemoScript.DURATION_MS) {
            val f = DemoScript.frameAt(t)
            d.feed(DriveSample(t, f.speedKmh, f.brake, f.turn, f.steerDeg)).forEach { seen += it.kind to it.tMs }
            t += 500
        }
        assertEquals(
            listOf(RiskKind.LANE_CHANGE to 24_500L, RiskKind.HARD_BRAKE to 32_000L, RiskKind.HARD_ACCEL to 41_000L, RiskKind.SPEED_SURGE to 43_000L, RiskKind.HIGH_SPEED to 63_500L),
            seen,
        )
    }

    @Test fun demoFullPipelineGivesScoreAndWarnings() {
        val d = EventDetector(); val tr = TripTracker()
        var st = WarnState(); val spoken = ArrayList<String>()
        var t = 0L
        var summary: com.hmgcarapp.saferadar.domain.TripSummary? = null
        while (t <= DemoScript.DURATION_MS) {
            val f = DemoScript.frameAt(t)
            val ev = d.feed(DriveSample(t, f.speedKmh, f.brake, f.turn, f.steerDeg))
            tr.onTick(t, true, f.speedKmh, ev)
            if (t == 0L) st = WarnFlow.reduce(st, WarnEvent.DriveChanged(true)).first
            if (t % 1_000L == 0L && f.others.isNotEmpty()) {
                val c = NearbyMapper.candidates(f.lat, f.lon, f.headingDeg.toDouble(), f.others)
                val (n, fx) = WarnFlow.reduce(st, WarnEvent.Nearby(t, c)); st = n
                fx.filterIsInstance<WarnEffect.Speak>().forEach { spoken += it.text }
            }
            t += 500
        }
        summary = tr.finishNow()
        // 가상 차량 A(왼쪽 뒤) -> B(앞쪽): 36초에 한 번, 58초에 한 번. 셀 양자화 때문에 도중의 방향 변화는 같은 상황이 아니므로 최소 간격(8초)으로 억제된다.
        assertEquals(
            listOf(
                "왼쪽 뒤에 급차선변경이 잦은 차량이 있습니다. 차로 변경에 주의하세요.",
                "앞쪽에 급제동이 잦은 차량이 있습니다. 차간 거리를 넉넉히 두세요.",
            ),
            spoken,
        )
        val s = summary!!
        assertEquals(listOf(1, 1, 1, 1, 1), s.counts)
        assertEquals("6+4+8+5+5=28 감점, 거리 3.09km(<10km)는 환산 없음", 72, s.score)
        assertEquals(2, s.durationMin)
        assertEquals(3.09, s.distanceKm, 0.01)
        assertEquals(5, s.deductions.size)
        assertTrue(s.tip.contains("방향지시등")) // 가장 큰 기여: 차로변경 8
    }

    // ---------------- 저장 코덱 ----------------
    @Test fun codecRoundTripAndNoLocationFields() {
        val p = Persisted(
            Settings(consent = true, consentAsked = true, background = false, voiceWarn = false),
            listOf(TripRecord(1_700_000_000_000L, 88, 12.5f, 33, listOf(1, 0, 2, 0, 0))),
            TripSnapshot(4_200.0, 300_000L, listOf(0, 1, 0, 0, 0), listOf(Deduction(RiskKind.HARD_ACCEL, 3))),
            1_700_000_100_000L,
        )
        val json = StateCodec.encode(p)
        val back = StateCodec.parse(json.toString())
        assertFalse(back.corrupt)
        assertEquals(p, back.p)
        val keys = json.toString().lowercase()
        listOf("lat", "lon", "geohash", "gh\"", "\"id\"", "heading", "position", "device", "account").forEach { assertFalse("저장 본문에 $it 가 있으면 안 된다: $keys", keys.contains(it)) }
    }

    @Test fun codecDefaultsAreOptOut() {
        val d = StateCodec.parse("{}")
        assertFalse(d.corrupt)
        assertFalse("동의는 기본 꺼짐", d.p.settings.consent)
        assertFalse(d.p.settings.consentAsked)
        assertEquals(Settings(), Persisted().settings)
        assertFalse(Settings().consent)
    }

    @Test fun codecSanitizesAndDetectsCorruption() {
        assertTrue(StateCodec.parse("not json").corrupt)
        val o = JSONObject().put("trips", org.json.JSONArray().put(JSONObject().put("at", -5).put("s", 500).put("km", 1e9).put("m", -3).put("c", org.json.JSONArray().put(-4).put(7))))
        val t = StateCodec.parse(o.toString()).p.trips.single()
        assertEquals(0L, t.endWallMs); assertEquals(100, t.score); assertEquals(100_000f, t.distanceKm, 0f); assertEquals(0, t.durationMin)
        assertEquals(listOf(0, 7, 0, 0, 0), t.counts)
    }

    @Test fun codecKeepsOnlyLatest60Trips() {
        val many = (1..75).map { TripRecord(it.toLong(), 80, 1f, 5, listOf(0, 0, 0, 0, 0)) }
        val back = StateCodec.parse(StateCodec.encode(Persisted(trips = many)).toString()).p.trips
        assertEquals(60, back.size); assertEquals(16L, back.first().endWallMs); assertEquals(75L, back.last().endWallMs)
    }

    // ---------------- StateStore (wake-mate 와 같은 규칙) ----------------
    @Test fun storeRestoreWriteClear() {
        val store = LocalJsonStore(tmp.newFolder())
        val ss = StateStore(store)
        assertEquals(Persisted(), ss.restore(0).p)
        val p = Persisted(trips = listOf(TripRecord(5L, 90, 3f, 10, listOf(0, 0, 0, 0, 0))))
        assertEquals(WriteResult.WROTE, ss.writeLatest { p })
        assertEquals(WriteResult.UNCHANGED, ss.writeLatest { p })
        assertEquals(p, StateStore(store).restore(0).p)
        assertTrue(ss.clearAll { Persisted() })
        assertEquals(Persisted(), StateStore(store).restore(0).p)
    }

    @Test fun storeQuarantinesCorruptFile() {
        val dir = tmp.newFolder()
        File(dir, "saferadar_state.json").writeText("{{{{")
        val r = StateStore(LocalJsonStore(dir)).restore(0)
        assertTrue(r.corrupt); assertTrue(r.quarantined)
        assertEquals(Persisted(), r.p)
    }

    // ---------------- 시연 입력 파서 ----------------
    @Test fun simInputParsers() {
        assertEquals(80f, SimInput.speed(80)!!, 0f); assertEquals(80.5f, SimInput.speed(80.5)!!, 0f)
        assertNull(SimInput.speed(-1)); assertNull(SimInput.speed(301)); assertNull(SimInput.speed("80")); assertNull(SimInput.speed(Double.NaN)); assertNull(SimInput.speed(null))
        assertEquals(Gear.PARK, SimInput.gear("p")); assertEquals(Gear.DRIVE, SimInput.gear("D")); assertEquals(Gear.REVERSE, SimInput.gear("reverse")); assertNull(SimInput.gear("X")); assertNull(SimInput.gear(null))
    }
}
