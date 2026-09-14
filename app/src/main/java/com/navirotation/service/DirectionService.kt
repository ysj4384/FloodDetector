// File: app/src/main/java/com/navirotation/service/DirectionService.kt
package com.navirotation.service

import com.navirotation.BuildConfig
import retrofit2.Call
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Query


interface DirectionService {
    @GET("driving")
    fun getRoute(
        @Header("X-NCP-APIGW-API-KEY-ID")  clientId: String = BuildConfig.NAVER_DIRECTION_CLIENT_ID,
        @Header("X-NCP-APIGW-API-KEY")     clientSecret: String = BuildConfig.NAVER_DIRECTION_CLIENT_SECRET,
        @Query("start")  start: String,     // "lon,lat"
        @Query("goal")   goal: String,      // "lon,lat"
        @Query("option") option: String = "trafast",
        @Query("waypoints") waypoints: String? = null   // ← 추가
    ): Call<DirectionResponse>
}
