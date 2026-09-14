// File: app/src/main/java/com/navirotation/navi/DetourKakaoNavi.kt
package com.navirotation.navi

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.kakao.sdk.navi.NaviClient
import com.kakao.sdk.navi.model.CoordType
import com.kakao.sdk.navi.model.Location
import com.kakao.sdk.navi.model.NaviOption
import com.naver.maps.geometry.LatLng
import com.navirotation.BuildConfig
import kotlin.math.cos

object DetourKakaoNavi {

    private fun meterToLat(m: Double) = m / 111_320.0
    private fun meterToLng(lat: Double, m: Double) = m / (111_320.0 * cos(Math.toRadians(lat)))

    /** 침수 지점 기준 N/E/S/W 후보 경유지 Location 생성 */
    private fun candidateVias(center: LatLng, radiusM: Int): List<Location> {
        val dLat = meterToLat(radiusM * 1.6)
        val dLng = meterToLng(center.latitude, radiusM * 1.6)
        val north = LatLng(center.latitude + dLat, center.longitude)
        val east  = LatLng(center.latitude, center.longitude + dLng)
        val south = LatLng(center.latitude - dLat, center.longitude)
        val west  = LatLng(center.latitude, center.longitude - dLng)

        fun toLoc(name: String, p: LatLng) =
            Location(name, p.longitude.toString(), p.latitude.toString()) // x=lon, y=lat

        return listOf(
            toLoc("우회1", north),
            toLoc("우회2", east),
            toLoc("우회3", south),
            toLoc("우회4", west)
        )
    }

    fun launchWithDetour(
        context: Context,
        start: LatLng,
        goal: LatLng,
        flood: LatLng,
        radiusM: Int = 150,
        viaCount: Int = 1
    ) {
        // 디버그에선 카카오내비 아예 미사용 (다운로드/로그인 방지)
        if (!BuildConfig.USE_KAKAO_NAV) {
            Toast.makeText(context, "디버그: 카카오내비 미사용(앱 내 우회 테스트)", Toast.LENGTH_SHORT).show()
            return
        }

        val dest = Location("목적지", goal.longitude.toString(), goal.latitude.toString())
        val vias = candidateVias(flood, radiusM).take(viaCount)
        val option = NaviOption(coordType = CoordType.WGS84)

        // 설치된 경우만 열기. 미설치면 조용히 안내만.
        if (NaviClient.instance.isKakaoNaviInstalled(context)) {
            val intent = NaviClient.instance.navigateIntent(
                destination = dest,
                option = option,
                viaList = vias
            )
            context.startActivity(intent)
        } else {
            Toast.makeText(context, "카카오내비 미설치: 설치 없이 테스트하려면 디버그 모드로 실행하세요.", Toast.LENGTH_SHORT).show()
        }
    }
}