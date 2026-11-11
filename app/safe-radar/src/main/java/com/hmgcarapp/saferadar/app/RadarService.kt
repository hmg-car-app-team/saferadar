package com.hmgcarapp.saferadar.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.hmgcarapp.core.log.HmgLog
import com.hmgcarapp.saferadar.R
import com.hmgcarapp.saferadar.domain.NotifText

/**
 * 앱이 떠 있는 동안 주행 점수 수집을 백그라운드에서도 이어가기 위한 위치 유형 포그라운드 서비스.
 * 로직은 갖지 않고 프로세스를 유지할 뿐이다(컨트롤러는 [RadarRuntime] 이 소유). 시작 전에 Activity 에서
 * ACCESS_FINE_LOCATION 을 먼저 받아야 하며([RadarRuntime.applyService] 가 확인), 시동과 함께 자동 실행되지는 않는다.
 * 알림에는 위치·점수 등 개인 값을 넣지 않는다.
 */
class RadarService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        RadarRuntime.serviceCreated(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val n = buildNotification()
            if (Build.VERSION.SDK_INT >= 29) ServiceCompat.startForeground(this, NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            else startForeground(NOTIF_ID, n)
        } catch (t: Throwable) {
            HmgLog.w("Service", "포그라운드 시작 실패: ${t.javaClass.simpleName}")
            stopSelf()
            return START_NOT_STICKY
        }
        return START_NOT_STICKY // 죽으면 앱을 다시 열 때만 복원한다(자동 재시작 없음)
    }

    /** 공유 상태가 바뀌면 알림 문구를 갱신한다. */
    fun refreshNotification() {
        runCatching { getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, buildNotification()) }
    }

    override fun onDestroy() {
        RadarRuntime.serviceDestroyed()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm?.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW))
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        return b.setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(getString(R.string.notif_title)).setContentText(NotifText.body(RadarRuntime.share))
            .setOngoing(true).build()
    }

    companion object {
        const val CHANNEL = "saferadar_collect"
        const val NOTIF_ID = 1001

        fun start(context: Context) {
            val i = Intent(context, RadarService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
            } catch (t: Throwable) { HmgLog.w("Service", "서비스 시작 거부: ${t.javaClass.simpleName}") }
        }

        fun stop(context: Context) { context.stopService(Intent(context, RadarService::class.java)) }
    }
}
