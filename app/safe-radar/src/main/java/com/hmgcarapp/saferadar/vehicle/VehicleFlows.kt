package com.hmgcarapp.saferadar.vehicle

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import android.content.Context
import com.hmgcarapp.core.connection.ManagedConnection
import com.hmgcarapp.core.log.HmgLog
import com.hmgcarapp.saferadar.domain.TurnSignal
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 조향각(도)·브레이크 페달·방향지시등 소스. 값이 오지 않으면 아무것도 내보내지 않는다(소비자는 '알 수 없음'으로 취급). */
interface SteeringSource { fun angles(): Flow<Float> }
interface BrakeSource { fun pressed(): Flow<Boolean> }
interface TurnSignalSource { fun states(): Flow<TurnSignal> }

/** Vehicle SDK 한 속성에 대한 포트(테스트에서 가짜). 콜백 람다는 항상 Unit. */
internal interface VehiclePort<T> {
    fun initialize()
    fun queryCurrent(onValue: (T) -> Unit, onError: (Throwable) -> Unit)
    fun register(onValue: (T) -> Unit, onError: (Throwable) -> Unit): () -> Unit
    fun release()
}

internal class PortLink<T>(val port: VehiclePort<T>) {
    @Volatile var unregister: (() -> Unit)? = null
    fun close() { runCatching { unregister?.invoke() }; runCatching { port.release() } }
}

/**
 * 속성 값 흐름: 연결·재연결은 core [ManagedConnection] 이 맡는다. 푸시가 오지 않는 환경(속도 푸시 0건 실측)에 대비해 [pollMs] 마다 현재값도 질의한다.
 * 첫 값은 그대로 값으로 내보낸다(소비자가 변화로 해석하지 않도록 상태로만 쓴다).
 */
internal fun <T> portFlowOf(
    tag: String,
    factory: () -> VehiclePort<T>,
    pollMs: Long = 500L,
    sleep: suspend (Long) -> Unit = { delay(it) },
    nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
): Flow<T> = callbackFlow<T> {
    lateinit var conn: ManagedConnection<PortLink<T>>
    conn = ManagedConnection(
        tag = tag,
        create = { PortLink(factory()) },
        open = { link, gen ->
            val v = link.port
            v.initialize()
            val onValue: (T) -> Unit = conn.callback(gen) { a: T -> trySend(a); Unit }
            val onError: (Throwable) -> Unit = conn.callback(gen) { e: Throwable ->
                HmgLog.w(tag, "신호 오류: ${e.javaClass.simpleName}")
                conn.onFailure(gen, e)
                Unit
            }
            v.queryCurrent(onValue, onError)
            link.unregister = v.register(onValue, onError)
        },
        dispose = { it.close() },
        clock = nowMs,
        onCreateFailed = { t -> HmgLog.w(tag, "Vehicle SDK 사용 불가: ${t.javaClass.simpleName}") },
    )
    val poller = launch {
        while (isActive) {
            sleep(pollMs)
            val c = conn.readyConn() ?: continue
            val gen = c.gen
            try {
                c.value.port.queryCurrent(
                    conn.callback(gen) { a: T -> trySend(a); Unit },
                    conn.callback(gen) { e: Throwable -> conn.onFailure(gen, e); Unit },
                )
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                conn.onFailure(gen, t)
            }
        }
    }
    try { conn.supervise(sleep) } finally { poller.cancel() }
    awaitClose { }
}

/** Pleos Vehicle SDK 2.3.2 SteeringWheel.SteeringWheelAngle (permission: pleos.car.permission.CAR_DRIVE). */
class PleosSteeringSource(private val context: Context) : SteeringSource {
    override fun angles(): Flow<Float> = flow { emitAll(portFlowOf("Steering", { SdkSteer(context.applicationContext) })) }

    private class SdkSteer(ctx: Context) : VehiclePort<Float> {
        private val v = Vehicle(ctx)
        override fun initialize() { v.initialize() }
        override fun queryCurrent(onValue: (Float) -> Unit, onError: (Throwable) -> Unit) {
            v.getSteeringWheel().getCurrentSteeringWheelAngle({ a: Float? -> if (a != null) onValue(a); Unit }, { e: Exception -> onError(e); Unit })
        }
        override fun register(onValue: (Float) -> Unit, onError: (Throwable) -> Unit): () -> Unit {
            val l = object : SteeringWheelAngleListener {
                override fun onSteeringWheelAngleUpdated(angle: Float?) { if (angle != null) onValue(angle); Unit }
                override fun onFailed(e: Exception) { onError(e); Unit }
            }
            val w = v.getSteeringWheel()
            w.registerSteeringWheelAngle(l)
            return { w.unregisterSteeringWheelAngle(l); Unit }
        }
        override fun release() { v.release() }
    }
}

/** Brake.BrakePedalStatus (permission: pleos.car.permission.CAR_DRIVE). */
class PleosBrakeSource(private val context: Context) : BrakeSource {
    override fun pressed(): Flow<Boolean> = flow { emitAll(portFlowOf("Brake", { SdkBrake(context.applicationContext) })) }

    private class SdkBrake(ctx: Context) : VehiclePort<Boolean> {
        private val v = Vehicle(ctx)
        override fun initialize() { v.initialize() }
        override fun queryCurrent(onValue: (Boolean) -> Unit, onError: (Throwable) -> Unit) {
            v.getBrake().getCurrentBrakePedalStatus({ b: Boolean? -> if (b != null) onValue(b); Unit }, { e: Exception -> onError(e); Unit })
        }
        override fun register(onValue: (Boolean) -> Unit, onError: (Throwable) -> Unit): () -> Unit {
            val l = object : BrakePedalStatusListener {
                override fun onBrakePedalStatusUpdated(pressed: Boolean?) { if (pressed != null) onValue(pressed); Unit }
                override fun onFailed(e: Exception) { onError(e); Unit }
            }
            val b = v.getBrake()
            b.registerBrakePedalStatus(l)
            return { b.unregisterBrakePedalStatus(l); Unit }
        }
        override fun release() { v.release() }
    }
}

/** TurnSignal.TurnSignal (permission: pleos.car.permission.CAR_LIGHTS). */
class PleosTurnSignalSource(private val context: Context) : TurnSignalSource {
    override fun states(): Flow<TurnSignal> = flow { emitAll(portFlowOf("TurnSignal", { SdkTurn(context.applicationContext) })) }

    private class SdkTurn(ctx: Context) : VehiclePort<TurnSignal> {
        private val v = Vehicle(ctx)
        override fun initialize() { v.initialize() }
        override fun queryCurrent(onValue: (TurnSignal) -> Unit, onError: (Throwable) -> Unit) {
            v.getTurnSignal().getCurrentTurnSignalState({ s: TurnSignalValue? -> if (s != null) onValue(s.toDomain()); Unit }, { e: Exception -> onError(e); Unit })
        }
        override fun register(onValue: (TurnSignal) -> Unit, onError: (Throwable) -> Unit): () -> Unit {
            val l = object : TurnSignalStateListener {
                override fun onTurnSignalStateUpdated(state: TurnSignalValue?) { if (state != null) onValue(state.toDomain()); Unit }
                override fun onFailed(e: Exception) { onError(e); Unit }
            }
            val t = v.getTurnSignal()
            t.registerTurnSignalState(l)
            return { t.unregisterTurnSignalState(l); Unit }
        }
        override fun release() { v.release() }
    }
}

internal fun TurnSignalValue.toDomain(): TurnSignal = when (this) {
    TurnSignalValue.LEFT -> TurnSignal.LEFT
    TurnSignalValue.RIGHT -> TurnSignal.RIGHT
    else -> TurnSignal.OFF
}
