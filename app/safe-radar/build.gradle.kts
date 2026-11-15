import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// 서버 주소: 코드/Git 에 고정하지 않는다. local.properties(`saferadar.serverUrl`) -> 환경변수 SAFERADAR_SERVER_URL -> 기본값.
//  - release 기본값: http://127.0.0.1:8765 (호스트에서 서버를 띄우고 `adb reverse tcp:8765 tcp:8765`; 서버가 없으면 앱은 한국어 안내와 시연 모드만. 평문은 main network_security_config 의 127.0.0.1/localhost 만)
//  - debug 기본값: 에뮬레이터에서 호스트 PC 로 가는 로컬 주소(10.0.2.2; 평문 허용은 debug 소스셋의 network_security_config)
//  - 값은 `http://127.0.0.1:포트` 처럼 스킴을 포함한 전체 주소여야 한다(스킴 없으면 앱이 거부해 접속하지 않음). 어느 출처가 쓰였는지는 gradle 로그의 '[server-url]' 줄로 확인.
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
var serverUrlSource = "default"
val configuredServerUrl: String = (
    localProps.getProperty("saferadar.serverUrl")?.also { serverUrlSource = "local.properties" }
        ?: providers.gradleProperty("saferadar.serverUrl").orNull?.also { serverUrlSource = "-P" }
        ?: System.getenv("SAFERADAR_SERVER_URL")?.also { serverUrlSource = "env" }
        ?: ""
    ).trim()
logger.lifecycle("[server-url] safe-radar: source=$serverUrlSource")
val releaseDefaultUrl = "http://127.0.0.1:8765"
fun quoted(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

// release 서명: app/.signing/signing.properties(커밋 금지, .gitignore)가 있으면 그 키로 서명한다.
// 항목: storeFile(.signing/ 안의 파일 이름), storePassword, keyAlias, keyPassword. 없으면 서명되지 않은 release APK 가 나온다.
val signingProps = Properties().apply {
    rootProject.file(".signing/signing.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
val hasReleaseSigning = signingProps.getProperty("storeFile") != null
logger.lifecycle("[signing] safe-radar release: " + (if (hasReleaseSigning) "app/.signing" else "unsigned"))

android {
    namespace = "com.hmgcarapp.saferadar"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.hmgcarapp.saferadar"
        minSdk = 28
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
    }

    signingConfigs {
        if (hasReleaseSigning) create("release") {
            storeFile = rootProject.file(".signing/" + signingProps.getProperty("storeFile"))
            storePassword = signingProps.getProperty("storePassword")
            keyAlias = signingProps.getProperty("keyAlias")
            keyPassword = signingProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        debug {
            buildConfigField("String", "RADAR_SERVER_URL", quoted(configuredServerUrl.ifEmpty { "http://10.0.2.2:8765" }))
        }
        release {
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
            buildConfigField("String", "RADAR_SERVER_URL", quoted(if (configuredServerUrl.isEmpty()) releaseDefaultUrl else configuredServerUrl))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.pleos.navihelper) // 읽기 전용 제한속도 리스너만 사용(새 권한 없음)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.org.json)
}
