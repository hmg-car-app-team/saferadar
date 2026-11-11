package com.hmgcarapp.saferadar.domain

import com.hmgcarapp.core.drive.DriveState

/** core 판정과 화면(UX 제한 provider) 판정 결합: 어느 쪽이든 DRIVING 이면 DRIVING. 화면 판정이 없으면(null: 화면 없음) core 만 본다. */
object DriveResolver {
    fun combine(core: DriveState, ui: DriveState?): DriveState =
        if (core == DriveState.DRIVING || ui == DriveState.DRIVING) DriveState.DRIVING else DriveState.PARKED
}

/** 리포트 음성 한 줄(순수). 시연이면 '시연' 접두어. */
object TripVoice {
    fun line(score: Int?, events: Int, demo: Boolean): String {
        val body = if (score != null) "오늘 안전점수 ${score}점, 위험 이벤트 ${events}회입니다." else "이번 주행은 짧아 점수를 계산하지 않았어요."
        return if (demo) "시연입니다. $body" else body
    }
}

/** 포그라운드 서비스 알림 문구(순수). 공유 중일 때만 위치 공유 사실을 알린다. 알림에는 위치·점수 같은 개인 값을 넣지 않는다. */
object NotifText {
    const val BASE = "안전운전 점수를 이 기기에서 계산하고 있어요"
    const val SHARING = "익명 위치(약 150m 격자)를 근접 경고용으로 공유 중이에요"
    fun body(s: ShareStatus): String = if (ShareGate.mayConnect(s)) SHARING else BASE
}

/** 주기 저장 정책: 진행 중 주행(복원 대상)이 있을 때만 30초 주기로 쓴다(정차 중 불필요한 flash 쓰기 방지). */
object SavePolicy {
    fun periodic(tripActive: Boolean, demo: Boolean): Boolean = tripActive && !demo
}

/**
 * 개인정보 고지 문구(동의 팝업·설정 화면·README 가 같은 핵심 문장을 쓴다; 테스트로 고정).
 */
object PrivacyText {
    const val NOT_SENT = "이름·차량번호·계정·기기 번호·정확한 좌표·속도·주행 점수와 기록은 보내지 않아요."
    const val ROUTE = "이동 경로를 저장하지는 않지만 같은 번호로 최대 10분간 연속된 대략 위치(약 150m 격자)를 보내고, 서버는 접속 IP 를 볼 수 있어요."
    const val SERVER = "이 앱이 설계상 연결하는 서버(로컬 테스트 서버 기준)는 위치를 메모리에 15초만 두고 디스크·로그에 남기지 않아요."
    const val CONSENT_INTRO = "켜면 주행 중 3초마다 대략적인 위치(약 150m 격자)·진행 방향·위험 등급과 주된 위험 유형을 임의로 바뀌는 익명 번호와 함께 서버에 보내고, 주변 위험 차량을 음성으로 알려드려요."
    const val CONSENT_OUTRO = "근접 경고 탭에서 언제든 끌 수 있어요. 안 켜도 점수 기능은 이 기기 안에서 그대로 동작해요."
    const val REVOKE_NOT_SAVED = "동의 철회가 저장되지 않았어요. 앱을 다시 켜면 동의가 복원될 수 있어요"
    /** 동의 팝업이 문단별로 그대로 렌더하는 전체 고지(단일 출처). */
    val CONSENT_PARAGRAPHS = listOf(CONSENT_INTRO, NOT_SENT, ROUTE, SERVER, CONSENT_OUTRO)
    const val CONSENT_BODY = "$CONSENT_INTRO $NOT_SENT $ROUTE $SERVER $CONSENT_OUTRO"
    const val SHARE_PAGE_BODY = "$NOT_SENT $ROUTE $SERVER"
}
