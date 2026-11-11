package com.hmgcarapp.saferadar.app

import android.content.Context
import com.hmgcarapp.core.CarSignals
import com.hmgcarapp.core.location.LocationPermissions
import com.hmgcarapp.core.log.HmgLog
import com.hmgcarapp.saferadar.domain.ShareStatus

/**
 * 프로세스 수명의 컨트롤러 소유자. Activity 가 없어도 서비스가 떠 있으면 컨트롤러(점수 수집)는 유지된다.
 * Activity 도 서비스도 없으면 컨트롤러를 해제한다. 모든 호출은 메인 스레드.
 */
object RadarRuntime {
    private var controller: RadarController? = null
    private var appContext: Context? = null
    private var activities = 0
    private var service: RadarService? = null
    /** 서비스 알림 문구용 최신 공유 상태 */
    var share: ShareStatus = ShareStatus.NO_CONSENT; private set

    fun acquire(context: Context): RadarController {
        val app = context.applicationContext
        appContext = app
        return controller ?: RadarController(app, CarSignals.create(app), serviceControl = ::applyService, shareListener = ::onShare).also { controller = it }
    }

    private fun onShare(s: ShareStatus) { share = s; service?.refreshNotification() }

    fun activityCreated() { activities++ }

    fun activityDestroyed() { activities = (activities - 1).coerceAtLeast(0); releaseIfIdle() }

    fun serviceCreated(s: RadarService) { service = s }

    fun serviceDestroyed() { service = null; releaseIfIdle() }

    /** 백그라운드 수집 설정 + 위치 권한(FINE) 보유 시에만 서비스를 켠다. */
    fun applyService(wanted: Boolean) {
        val ctx = appContext ?: return
        if (wanted && LocationPermissions.hasFine(ctx)) { if (service == null) RadarService.start(ctx) }
        else if (service != null || !wanted) RadarService.stop(ctx)
    }

    private fun releaseIfIdle() {
        if (activities == 0 && service == null) {
            controller?.let { HmgLog.i("Runtime", "화면·서비스 없음 -> 컨트롤러 해제"); it.release() }
            controller = null
        }
    }
}
