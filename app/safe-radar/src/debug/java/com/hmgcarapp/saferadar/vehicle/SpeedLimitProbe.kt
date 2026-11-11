package com.hmgcarapp.saferadar.vehicle

import com.hmgcarapp.core.log.HmgLog

/**
 * DEBUG 전용 관측용: NaviHelper 가 준 제한속도 원값(raw)과 수신 개수만 logcat(`HMG/SpeedLimit`)에 남긴다.
 * 값이 바뀔 때와 30건마다만 찍는다. 좌표·경로·주소는 다루지 않는다. release 소스셋의 같은 이름 객체는 아무것도 하지 않는다(문자열·로그 없음).
 */
object SpeedLimitProbe {
    private var count = 0L
    private var last: Int? = null

    @Synchronized fun onRaw(raw: Int) {
        count++
        if (raw != last || count % 30L == 1L) HmgLog.i("SpeedLimit", "raw=$raw count=$count")
        last = raw
    }
}
