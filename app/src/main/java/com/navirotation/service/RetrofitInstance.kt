// File: app/src/main/java/com/navirotation/service/RetrofitInstance.kt
package com.navirotation.service

import com.navirotation.BuildConfig
import com.navirotation.CctvService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.converter.scalars.ScalarsConverterFactory
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RetrofitInstance {

    // SK 직선 거리 API용 Retrofit
    private val sKRetrofit: Retrofit = Retrofit.Builder()
        .baseUrl(BuildConfig.SK_BASE_URL)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    // Kakao 장소 검색 API용 Retrofit
    private val kakaoRetrofit: Retrofit = Retrofit.Builder()
        .baseUrl(BuildConfig.KAKAO_BASE_URL)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    // CCTV 피드 API용 Retrofit (XML/String 응답 처리)
    private val cctvRetrofit: Retrofit = Retrofit.Builder()
        .baseUrl(BuildConfig.CCTV_BASE_URL)
        .addConverterFactory(ScalarsConverterFactory.create())
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    // Directions API용 Retrofit
    private val directionRetrofit: Retrofit = Retrofit.Builder()
        .baseUrl(BuildConfig.NAVER_DIRECTION_BASE_URL)       
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    @Singleton
    @Provides
    fun navigationService(): NavigationService =
        sKRetrofit.create(NavigationService::class.java)

    @Singleton
    @Provides
    fun mapService(): MapService =
        kakaoRetrofit.create(MapService::class.java)

    @Singleton
    @Provides
    fun cctvService(): CctvService =
        cctvRetrofit.create(CctvService::class.java)

    @Singleton
    @Provides
    fun directionService(): DirectionService =
        directionRetrofit.create(DirectionService::class.java)
}
