// File: app/src/main/java/com/navirotation/ServiceApplication.kt
package com.navirotation

import android.app.Application
import com.kakaomobility.knsdk.KNSDK
import com.kakao.sdk.common.KakaoSdk
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class ServiceApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // ★ Kakao SDK(v2) 전역 초기화: NaviClient 사용 전 반드시 1회 필요
        KakaoSdk.init(this, BuildConfig.KAKAO_NATIVE_APP_KEY)

        // KNSDK 설치(기존 그대로 유지)
        KNSDK.install(this, filesDir.absolutePath + "/files")
    }
}
