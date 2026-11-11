package com.hmgcarapp.saferadar

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.hmgcarapp.core.log.HmgLog
import com.hmgcarapp.pleos.designsystem.restriction.PleosUxRestrictionsProvider
import com.hmgcarapp.pleos.designsystem.theme.PleosTheme
import com.hmgcarapp.saferadar.app.RadarController
import com.hmgcarapp.saferadar.app.RadarRuntime
import com.hmgcarapp.saferadar.ui.SafeRadarApp

class MainActivity : ComponentActivity() {
    private var controller: RadarController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        HmgLog.i("saferadar", "onCreate")
        // 런타임 권한 중 이 앱이 매니페스트에 선언한 것만 요청한다. 위치(FINE)는 포그라운드 서비스를 시작하기 전에 먼저 받는다.
        val declared = runCatching {
            packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS).requestedPermissions?.toSet().orEmpty()
        }.getOrDefault(emptySet())
        val wanted = listOf(
            "android.car.permission.CAR_SPEED", "pleos.car.permission.FUSED_LOCATION",
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.POST_NOTIFICATIONS,
        )
        val toRequest = wanted.filter { it in declared && checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (toRequest.isNotEmpty()) requestPermissions(toRequest.toTypedArray(), 1)
        RadarRuntime.activityCreated()
        val c = RadarRuntime.acquire(this)
        controller = c
        setContent {
            PleosTheme {
                PleosUxRestrictionsProvider { SafeRadarApp(c) }
            }
        }
    }

    override fun onStart() { super.onStart(); controller?.onAppStart() }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        controller?.onAppStart()
    }

    override fun onStop() { controller?.saveNow(); super.onStop() }

    override fun onDestroy() {
        controller?.onUiDriveState(null) // 화면이 없으면 화면 쪽 주행 판정을 해제(core 판정만 사용)
        controller = null
        RadarRuntime.activityDestroyed()
        super.onDestroy()
    }
}
