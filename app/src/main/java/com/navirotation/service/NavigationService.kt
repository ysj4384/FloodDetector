package com.navirotation.service

import com.navirotation.data.navigation.CoordConvertResponse
import com.navirotation.data.navigation.DistanceResponse
import retrofit2.Call
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Query

interface NavigationService {

    /**
     * SK 직선 거리 계산
     */
    @GET("routes/distance")
    fun distanceRequest(
        @Header("appKey") appKey: String,
        @Query("version") version: String,
        @Query("startX") startX: String,
        @Query("startY") startY: String,
        @Query("endX") endX: String,
        @Query("endY") endY: String,
        @Query("reqCoordType") reqCoordType: String
    ) : Call<DistanceResponse>

    /**
     * SK 좌표 변환
     */
    @GET("geo/coordconvert")
    fun coordConvertRequest(
        @Header("appKey") appKey: String,
        @Query("version") version: String,
        @Query("lat") lat: String,
        @Query("lon") lon: String,
        @Query("fromCoord") fromCoord: String,
        @Query("toCoord") toCoord: String,
    ) : Call<CoordConvertResponse>
}
