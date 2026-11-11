package com.hmgcarapp.saferadar.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import com.hmgcarapp.core.drive.Gear
import com.hmgcarapp.core.location.GeoPoint
import com.hmgcarapp.core.log.HmgLog
import com.hmgcarapp.saferadar.domain.SimInput

/**
 * adb 시연 주입 수신기(debuggable 전용, DUMP 권한 보유자=adb shell 만 전송 가능). 시스템 주도라 주행 가드 없음.
 * 동의(consent)는 이 경로로 바꿀 수 없다(동의는 화면에서 사용자만 바꾼다).
 *  시연      --ez demo true|false      (가상 차량 시나리오 시작/중단; 서버로 보내지 않고 저장하지 않음)
 *  신호      --ef speed 80  --es gear D|P  --ef lat 37.5 --ef lon 127.03  --ez hub true|false
 */
internal object SimReceiver {
    const val ACTION = "com.hmgcarapp.saferadar.SIM"
    const val PERMISSION = "android.permission.DUMP"
    const val MIN_INTERVAL_MS = 300L

    fun register(context: Context, c: RadarController, monoClock: () -> Long): BroadcastReceiver {
        var lastKey: String? = null; var lastAt = 0L
        val r = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, i: Intent) {
                val ex = i.extras
                val key = ex?.keySet()?.sorted()?.joinToString(",") ?: ""
                val now = monoClock()
                if (key == lastKey && now - lastAt in 0 until MIN_INTERVAL_MS) { HmgLog.i("SafeRadar", "SIM 무시: ${MIN_INTERVAL_MS}ms 이내 재수신"); return }
                lastKey = key; lastAt = now
                fun raw(k: String): Any? = ex?.get(k)
                fun bad(k: String) = HmgLog.w("SafeRadar", "SIM 무시: $k (형식 또는 범위 오류)")
                if (i.hasExtra("demo")) { if (i.getBooleanExtra("demo", false)) c.beginDemo() else c.abortDemo() }
                val lat = SimInput.number(raw("lat")); val lon = SimInput.number(raw("lon"))
                val loc = if (lat != null && lon != null && lat in -90.0..90.0 && lon in -180.0..180.0) GeoPoint(lat, lon) else null
                if ((i.hasExtra("lat") || i.hasExtra("lon")) && loc == null) bad("lat/lon")
                val enabled = if (i.hasExtra("hub")) i.getBooleanExtra("hub", false) else null
                val speed = if (i.hasExtra("speed")) SimInput.speed(raw("speed")).also { if (it == null) bad("speed") } else null
                val gear: Gear? = if (i.hasExtra("gear")) SimInput.gear(i.getStringExtra("gear")).also { if (it == null) bad("gear") } else null
                // 신호를 주입하면 허브를 켠다(꺼 둔 채 값만 넣으면 효과가 없어 혼동되므로)
                c.simHub(enabled ?: if (speed != null || gear != null || loc != null) true else null, speed, gear, loc)
                HmgLog.i("SafeRadar", "SIM 수신: ${ex?.keySet()}")
            }
        }
        val f = IntentFilter(ACTION)
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(r, f, PERMISSION, null, Context.RECEIVER_EXPORTED) else context.registerReceiver(r, f, PERMISSION, null)
        return r
    }
}
