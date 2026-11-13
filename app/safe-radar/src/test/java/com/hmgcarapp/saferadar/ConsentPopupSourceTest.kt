package com.hmgcarapp.saferadar

import com.hmgcarapp.saferadar.domain.PrivacyText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 동의 팝업이 description(maxLines=3 말줄임) 대신 content 슬롯에 전체 고지를 넣는지 소스로 고정한다(검증 D1). */
class ConsentPopupSourceTest {
    private val popup: String = File("src/main/java/com/hmgcarapp/saferadar/ui/Screens.kt").readText()
        .substringAfter("private fun ConsentPopup").substringBefore("// ---------------- 주행 중")

    @Test fun popupDoesNotUseTruncatedDescription() {
        assertFalse(Regex("""description\s*=\s*PrivacyText""").containsMatchIn(popup))
        assertTrue(popup.contains("content = {"))
    }

    @Test fun popupRendersEveryParagraphAndScrolls() {
        assertTrue(popup.contains("PrivacyText.CONSENT_PARAGRAPHS.forEach"))
        assertTrue(popup.contains("verticalScroll"))
    }

    @Test fun paragraphsAreShortAndFactual() {
        assertEquals(listOf(PrivacyText.CONSENT_INTRO, PrivacyText.CONSENT_OUTRO), PrivacyText.CONSENT_PARAGRAPHS)
        val all = PrivacyText.CONSENT_PARAGRAPHS.joinToString(" ")
        listOf("대략 위치", "진행 방향", "위험 등급", "위험 등급일 때만 주된 위험 유형", "익명 번호", "서버에 보내요", "끄면 바로 멈춰요").forEach { assertTrue(it, all.contains(it)) }
        assertEquals(PrivacyText.CONSENT_BODY, all)
    }

    @Test fun consentGateAndDefaultOffArePreserved() {
        val ui = File("src/main/java/com/hmgcarapp/saferadar/ui/Screens.kt").readText()
        assertTrue(ui.contains("set_consent") && ui.contains("confirmConsent = true"))
        assertTrue(ui.contains("confirmLabel = \"동의하고 켜기\""))
        assertFalse("스위치는 동의 팝업 확인 전에 켜지지 않는다", ui.contains("onCheckedChange = { c.setConsent(it) }"))
    }
}
