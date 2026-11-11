package com.hmgcarapp.saferadar.domain

enum class TurnSignal { OFF, LEFT, RIGHT }

/**
 * 한 시점의 차량 신호 묶음(단조 시계 [tMs]). null = 알 수 없음.
 * 가속페달·시동·운전자 ID 신호는 이 플랫폼에 없어 포함하지 않는다.
 */
data class DriveSample(
    val tMs: Long,
    val speedKmh: Float,
    val brake: Boolean? = null,
    val turn: TurnSignal? = null,
    val steerDeg: Float? = null,
)

/**
 * 위험 이벤트 종류. [weight] 는 점수 감점·위험 등급 산정에 쓰는 **미검증 초기값**이다(실주행 데이터로 보정 필요).
 * [code] 는 서버 전송에도 쓰는 안정 번호(0..4)이므로 순서를 바꾸지 않는다.
 */
enum class RiskKind(val code: Int, val label: String, val weight: Int, val phrase: String, val tip: String) {
    HARD_BRAKE(0, "급감속", 6, "급제동이 잦은", "앞차와의 거리를 넉넉히 두고 미리 속도를 줄여 보세요."),
    HARD_ACCEL(1, "급가속", 4, "급가속이 잦은", "페달을 천천히 밟아 부드럽게 가속해 보세요."),
    LANE_CHANGE(2, "방향지시등 없는 차로변경", 8, "급차선변경이 잦은", "차로를 바꾸기 3초 전에 방향지시등을 켜 보세요."),
    HIGH_SPEED(3, "고속 지속", 5, "고속으로 달리는", "고속 구간에서도 흐름에 맞춰 속도를 낮춰 보세요."),
    SPEED_SURGE(4, "급격한 고속 진입", 5, "급하게 속도를 높이는", "고속 구간에 들어갈 때는 속도를 서서히 올려 보세요.");

    companion object {
        fun fromCode(code: Int): RiskKind? = entries.firstOrNull { it.code == code }
    }
}

data class RiskEvent(val kind: RiskKind, val tMs: Long)

/** 위험 등급(공유 대상). 숫자는 서버 전송 값이므로 바꾸지 않는다. */
enum class RiskGrade(val code: Int, val label: String) {
    LOW(0, "양호"), MID(1, "주의"), HIGH(2, "위험");

    companion object {
        fun fromCode(code: Int): RiskGrade? = entries.firstOrNull { it.code == code }
    }
}
