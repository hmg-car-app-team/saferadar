package com.hmgcarapp.saferadar.vehicle

import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import com.hmgcarapp.core.connection.ConnectionPolicy
import com.hmgcarapp.core.connection.ManagedConnection
import com.hmgcarapp.core.log.HmgLog
import com.hmgcarapp.saferadar.domain.SpeedLimit
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 현재 도로 제한속도(km/h) 소스. 값이 오지 않으면 아무것도 내보내지 않는다(소비자는 '알 수 없음'으로 취급해 기존 동작 유지). */
interface SpeedLimitSource { fun limits(): Flow<Int> }

/**
 * NaviHelper 3.0.5 `onCurrentRoadSpeedLimit(Int)` 읽기 전용 수신(SDK 문서상 요청 없이 초당 전달; 사용 API: initialize/addListener/removeListener/release 만). 요청·경로 조작은 하지 않는다.
 * SDK 문서 기준으로만 연동했고 에뮬레이터에서 값 수신은 확인하지 못했다. 원값을 내보내고 정상 범위([SpeedLimit.sane]) 판정은 소비자가 한다(비정상 값이 오면 '모름'으로 되돌리려고).
 * 연결은 NaviHelper.initialize 성공 콜백에서만 '준비됨'으로 본다(readyTimeoutMs, routine-navi NaviConnectRules.POLICY 와 같은 10초). 정체되면 주기적으로 reap 해 재생성한다.
 */
class PleosSpeedLimitSource(private val context: Context) : SpeedLimitSource {
    override fun limits(): Flow<Int> = callbackFlow {
        lateinit var conn: ManagedConnection<NaviHelper>
        val listener = object : NaviHelperEventListener {
            override fun onCurrentRoadSpeedLimit(speedLimit: Int) { SpeedLimitProbe.onRaw(speedLimit); trySend(speedLimit); Unit } // 원값 그대로(0 등 비정상도): 소비자가 sane 으로 거르고 비정상이면 이전 값을 지운다
        }
        conn = ManagedConnection(
            tag = "SpeedLimit",
            create = { NaviHelper(context.applicationContext) },
            open = { h, gen ->
                h.initialize(
                    {
                        conn.ifCurrent(gen) {
                            try { h.addListener(listener); conn.markReady(gen) } catch (t: Throwable) { conn.fail(gen); HmgLog.w("SpeedLimit", "리스너 등록 실패: ${t.javaClass.simpleName}") }
                        }
                        Unit
                    },
                    { t -> conn.ifCurrent(gen) { conn.fail(gen); HmgLog.w("SpeedLimit", "NaviHelper 연결 실패: ${t.javaClass.simpleName}") }; Unit },
                )
            },
            dispose = { h -> runCatching { h.removeListener(listener) }; runCatching { h.release() } },
            isConnectionFailure = { true },
            clock = { System.nanoTime() / 1_000_000L },
            policy = ConnectionPolicy(readyTimeoutMs = READY_TIMEOUT_MS),
            onCreateFailed = { t -> HmgLog.w("SpeedLimit", "NaviHelper 사용 불가: ${t.javaClass.simpleName}") },
        )
        val job = launch { conn.supervise() }
        // supervise 는 푸시 사망 표시에만 깨어나므로, '준비 정체'(initialize 콜백 무응답)는 주기적으로 확인해 교체한다
        val stall = launch { while (isActive) { delay(STALL_CHECK_MS); conn.reap(); if (conn.current() == null) conn.connect() } }
        awaitClose { job.cancel(); stall.cancel(); conn.shutdown() }
    }

    private companion object { const val READY_TIMEOUT_MS = 10_000L; const val STALL_CHECK_MS = 2_000L }
}
