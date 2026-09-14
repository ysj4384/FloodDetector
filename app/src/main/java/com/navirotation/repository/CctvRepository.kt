// File: app/src/main/java/com/navirotation/repository/CctvRepository.kt

package com.navirotation.repository

import com.navirotation.BuildConfig
import com.navirotation.CctvService
import retrofit2.Call
import javax.inject.Inject
import javax.inject.Singleton

/**
 * CCTV API 호출을 캡슐화한 Repository
 */
@Singleton
class CctvRepository @Inject constructor(
    private val cctvService: CctvService
) {
    /**
     * XML(String) 형태로 CCTV 정보를 가져오는 Call 객체를 반환
     *
     * @param category "all" 등
     * @param pageNo   "1", "2" 등
     * @param cctvType "1" (동영상), "2" (이미지) 등
     * @param minX     서쪽 경도
     * @param maxX     동쪽 경도
     * @param minY     남쪽 위도
     * @param maxY     북쪽 위도
     */
    fun fetchCctvXml(
        category: String,
        pageNo: String,
        cctvType: String, // cctvType 파라미터 추가
        minX: String,
        maxX: String,
        minY: String,
        maxY: String
    ): Call<String> {
        return cctvService.getCctvInfo(
            BuildConfig.CCTV_API_KEY,
            category,
            cctvType, // 파라미터로 받은 cctvType 사용
            minX,
            maxX,
            minY,
            maxY,
            "xml"
        )
    }
}