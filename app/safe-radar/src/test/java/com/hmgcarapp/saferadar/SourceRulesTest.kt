package com.hmgcarapp.saferadar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 제작 규칙(디자인시스템·개인정보·네트워크·모듈 의존·키 문자열)이 소스에서 깨지지 않게 막는 정적 점검. */
class SourceRulesTest {
    private val root = File("src/main/java/com/hmgcarapp/saferadar")
    private fun kt(sub: String? = null): List<File> = (if (sub == null) root else File(root, sub)).walkTopDown().filter { it.extension == "kt" }.toList()
    private fun code(f: File) = f.readLines().filter { !it.trimStart().startsWith("//") && !it.trimStart().startsWith("*") && !it.trimStart().startsWith("/*") }.joinToString("\n")

    @Test fun ui_hasNoImplementationExplanationCopy() {
        val ui = File(root, "ui/Screens.kt").readText() + File(root, "domain/Share.kt").readText() + File(root, "domain/DriveResolver.kt").readText()
        listOf("미검증 초기값", "초기값이에요", "가상 차량과 가상", "시연 데이터(저장하지 않음)", "시연 중: 서버로", "개발·시연용", "주행 중으로 간주해요", "sim_badge", "driving_notice", "corrupt_kept", "save_held", "다시 시도해요(계속 실패하면", "가속페달 신호는 이 차량 플랫폼에 없어")
            .forEach { assertFalse("화면에 구현 사정 설명 문구 금지: $it", ui.contains(it)) }
        assertTrue(ui.contains("btn_demo") && ui.contains("set_consent") && ui.contains("consent_body"))
        listOf("signals_line", "consent_note", "share_badge\"", "알아두세요", "저장된 기록과 개인정보", "받고 있는 차량 신호").forEach { assertFalse("제거된 안내 패널: $it", ui.contains(it)) }
    }

    @Test fun sourcesExist() { assertTrue(kt().size >= 18) }

    @Test fun ui_usesOnlyPleosComponentsAndTokens() {
        kt("ui").forEach { f ->
            val c = code(f)
            val material = Regex("import androidx\\.compose\\.material3\\.(\\w+)").findAll(c).map { it.groupValues[1] }.toSet()
            assertEquals("${f.name}: Material 위젯 금지(Text 만 허용)", emptySet<String>(), material - "Text")
            assertFalse("${f.name}: 하드코딩 색", Regex("\\bColor\\(|0x[0-9A-Fa-f]{6,8}|Color\\.(Red|Blue|Green|White|Black|Gray)").containsMatchIn(c))
            assertFalse("${f.name}: sp/fontSize 직접 지정", Regex("\\d\\.sp\\b|fontSize\\s*=").containsMatchIn(c))
            assertFalse("${f.name}: dp 직접 지정(토큰 사용)", Regex("\\d\\.dp\\b").containsMatchIn(c))
            assertFalse("${f.name}: weight 와 고정폭 컨트롤 혼용 방지(ui 에서 weight 금지)", c.contains(".weight("))
            assertFalse("${f.name}: 주행 중 입력 위젯(키보드) 금지", Regex("PleosTextField|PleosNumberField|PleosDropdown|BasicTextField").containsMatchIn(c))
        }
    }

    @Test fun noDependencyOnOtherAppModules() {
        kt().forEach { f ->
            Regex("import (com\\.hmgcarapp\\.[a-z]+)").findAll(code(f)).map { it.groupValues[1] }.forEach {
                assertTrue("${f.name}: 허용되지 않은 의존 $it", it in setOf("com.hmgcarapp.core", "com.hmgcarapp.pleos", "com.hmgcarapp.saferadar"))
            }
        }
    }

    @Test fun noPlaintextHttpNoKeysNoHardcodedServer() {
        kt().forEach { f ->
            val c = code(f)
            assertFalse("${f.name}: 평문 http 리터럴", Regex("\"http://").containsMatchIn(c))
            assertFalse("${f.name}: API 키 사용", Regex("API_KEY|apiKey|api_key|secret|Bearer |Authorization", RegexOption.IGNORE_CASE).containsMatchIn(c))
            assertFalse("${f.name}: 고정 서버 주소 하드코딩", Regex("https?://[A-Za-z0-9]").containsMatchIn(c))
            assertFalse("${f.name}: 사설/로컬 주소 하드코딩", Regex("10\\.0\\.2\\.2|192\\.168\\.|localhost|127\\.0\\.0\\.1").containsMatchIn(c.replace(Regex("\"10\\.0\\.2\\.2\".*"), "").replace(Regex("\"localhost\".*"), "").replace(Regex("\"127\\.0\\.0\\.1\".*"), "")))
        }
        val gradle = File("build.gradle.kts").readText()
        assertFalse("빌드 스크립트에 키 변수", gradle.contains("API_KEY") || gradle.contains("apiKey"))
        // release 의 기본 서버 주소는 루프백(adb reverse), debug 는 에뮬레이터 주소(10.0.2.2)
        val rel = gradle.substringAfter("release {").substringBefore("}")
        assertTrue(gradle.contains("val releaseDefaultUrl = \"http://127.0.0.1:8765\""))
        assertTrue(rel.contains("releaseDefaultUrl") && rel.contains("configuredServerUrl"))
        assertFalse("release 에 10.0.2.2 기본값 금지(LAN 사설 주소 위험)", rel.contains("10.0.2.2") || gradle.substringBefore("android {").contains("10.0.2.2:"))
        assertTrue(gradle.substringAfter("debug {").substringBefore("}").contains("http://10.0.2.2:8765"))
    }

    @Test fun cleartextOnlyInDebugSourceSet() {
        val main = File("src/main/res/xml/network_security_config.xml").readText()
        assertTrue(main.contains("cleartextTrafficPermitted=\"false\""))
        val mainDomains = Regex("<domain[^>]*>([^<]+)</domain>").findAll(main).map { it.groupValues[1] }.toSet()
        assertEquals("main(release 포함)에서 평문을 허용하는 주소는 루프백뿐(10.0.2.2 아님)", setOf("127.0.0.1", "localhost"), mainDomains)
        assertEquals("평문 허용 domain-config 는 하나", 1, Regex("cleartextTrafficPermitted=\"true\"").findAll(main).count())
        assertFalse(main.contains("includeSubdomains=\"true\""))
        val debug = File("src/debug/res/xml/network_security_config.xml").readText()
        val domains = Regex("<domain[^>]*>([^<]+)</domain>").findAll(debug).map { it.groupValues[1] }.toSet()
        assertEquals("debug 에서 평문을 허용하는 주소는 로컬 개발 주소뿐", setOf("10.0.2.2", "localhost", "127.0.0.1"), domains)
        // release 소스셋은 SpeedLimitProbe 무동작 구현 하나뿐이며 로그·문자열이 없다
        val rel = File("src/release").walkTopDown().filter { it.isFile }.map { it.relativeTo(File("src/release")).path }.toList()
        assertEquals(listOf("java/com/hmgcarapp/saferadar/vehicle/SpeedLimitProbe.kt"), rel)
        val relCode = code(File("src/release/" + rel[0]))
        assertFalse("release 프로브에 문자열 리터럴·로그 금지", relCode.contains("\"") || relCode.contains("HmgLog") || relCode.contains("Log."))
        assertTrue(File("src/main/AndroidManifest.xml").readText().contains("android:networkSecurityConfig=\"@xml/network_security_config\""))
        assertFalse(File("src/main/AndroidManifest.xml").readText().contains("usesCleartextTraffic"))
    }

    @Test fun manifestPermissionsAreMinimal() {
        val m = File("src/main/AndroidManifest.xml").readText()
        listOf("RECORD_AUDIO", "STT_SERVICE", "RECEIVE_BOOT_COMPLETED", "READ_CONTACTS", "READ_PHONE_STATE", "GET_ACCOUNTS", "CAR_CLIMATE", "CAR_ENERGY", "ACCESS_BACKGROUND_LOCATION", "ACCESS_NETWORK_STATE", "NAVI_ROUTE")
            .forEach { assertFalse("불필요 권한 $it", m.contains(it)) }
        listOf("CAR_SPEED", "pleos.car.permission.CAR_DRIVE", "pleos.car.permission.CAR_LIGHTS", "FUSED_LOCATION", "TTS_SERVICE", "ACCESS_FINE_LOCATION", "FOREGROUND_SERVICE_LOCATION", "android.permission.INTERNET")
            .forEach { assertTrue("필요 권한 $it", m.contains(it)) }
        assertTrue(m.contains("android:name=\"distractionOptimized\" android:value=\"true\""))
        assertTrue(m.contains("android:foregroundServiceType=\"location\""))
        assertTrue("서비스는 외부 시작 불가", m.substringAfter("<service").substringBefore("/>").contains("android:exported=\"false\""))
        assertTrue(m.contains("android:allowBackup=\"false\""))
    }

    @Test fun logsNeverPrintCoordinatesIdsOrUrls() {
        kt().forEach { f ->
            code(f).lines().filter { it.contains("HmgLog.") }.forEach { line ->
                assertFalse("${f.name}: 좌표/ID/주소를 로그에 남김: $line", Regex("latitude|longitude|\\.lat\\b|\\.lon\\b|geohash|\\bgh\\b|idRotator|\\.id\\b|report\\b|serverUrl|baseUrl|\\bloc\\b|lastLoc|\\$\\{?g\\b").containsMatchIn(line))
            }
        }
    }

    @Test fun sdkCallbacksAreManagedConnectionBased() {
        assertTrue(code(File(root, "vehicle/VehicleFlows.kt")).contains("ManagedConnection"))
        assertFalse("직접 재연결 루프 금지", code(File(root, "vehicle/VehicleFlows.kt")).contains("while (true)"))
    }

    @Test fun userActionsAreParkedOnlyGuarded() {
        val c = code(File(root, "app/RadarController.kt"))
        // 화면에서 호출되는 상태 변경 공개 메서드는 모두 parkedOnly 로 감싼다
        listOf("setPage", "answerConsent", "postponeConsent", "setConsent", "setBackground", "setVoiceWarn", "clearAllData", "startDemo").forEach {
            assertTrue("$it 에 parkedOnly 가드", Regex("fun $it\\([^)]*\\)\\s*=\\s*parkedOnly").containsMatchIn(c))
        }
    }

    @Test fun noNetworkUnlessConsentGate() {
        val pump = code(File(root, "app/SharePump.kt"))
        assertTrue("Report 생성은 ShareGate 판정 뒤에만", pump.indexOf("ShareGate.mayConnect") in 0 until pump.indexOf("return Report(id, gh, sector"))
        val c = code(File(root, "app/RadarController.kt"))
        assertFalse("컨트롤러가 서버 API 를 직접 호출하지 않는다(SharePump 만)", Regex("\\.report\\(|HttpShareApi\\(|URL\\(").containsMatchIn(c.replace("{ HttpShareApi(it) }", "")))
        assertFalse("STT(상시 청취) 사용 금지", kt().any { Regex("core\\.voice\\.Listener|PleosListener|listenOnce").containsMatchIn(code(it)) })
        // 서버 전송 객체(Report)는 정해진 필드만
        val share = code(File(root, "domain/Share.kt"))
        assertTrue(share.contains("data class Report(val id: String, val geohash: String, val heading8: Int, val grade: RiskGrade, val kind: RiskKind?)"))
    }

    @Test fun controllerUsesServerUrlPolicy() {
        val c = code(File(root, "app/RadarController.kt"))
        assertTrue(c.contains("ServerUrlPolicy.validate(serverUrlRaw, debuggable)"))
    }

    @Test fun speedLimitProbeIsDebugOnly() {
        val dbg = File("src/debug/java/com/hmgcarapp/saferadar/vehicle/SpeedLimitProbe.kt").readText()
        assertTrue(dbg.contains("raw=\$raw count=\$count"))
        // main 은 프로브를 호출만 한다: 관측 문자열·로그 호출이 main 에 없어야 release 에 남지 않는다
        val nav = code(File(root, "vehicle/NaviSpeedLimit.kt"))
        assertTrue(nav.contains("SpeedLimitProbe.onRaw(speedLimit)"))
        assertFalse(kt().any { code(it).contains("raw=") })
        // 프로브는 좌표·주소 등을 다루지 않는다
        assertFalse(Regex("latitude|longitude|geohash|serverUrl", RegexOption.IGNORE_CASE).containsMatchIn(dbg))
    }
}
