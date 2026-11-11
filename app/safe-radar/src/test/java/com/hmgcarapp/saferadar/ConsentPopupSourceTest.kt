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

    @Test fun paragraphsCoverAllKeyNotices() {
        assertEquals(listOf(PrivacyText.CONSENT_INTRO, PrivacyText.NOT_SENT, PrivacyText.ROUTE, PrivacyText.SERVER, PrivacyText.CONSENT_OUTRO), PrivacyText.CONSENT_PARAGRAPHS)
        val all = PrivacyText.CONSENT_PARAGRAPHS.joinToString(" ")
        listOf("접속 IP", "최대 10분", "15초", "언제든 끌 수 있어요").forEach { assertTrue(it, all.contains(it)) }
        assertEquals(PrivacyText.CONSENT_BODY, all)
    }
}
