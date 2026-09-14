package com.navirotation.repository

import com.navirotation.data.map.SearchPlaceResponse
import com.navirotation.service.MapService
import javax.inject.Inject

class MapRepository @Inject constructor(private val mapService: MapService) {

    fun getSearchPlaceData(query: String, x: String, y: String) : SearchPlaceResponse? {
        return mapService.searchPlaceRequest(
            Authorization = "KakaoAK ${com.navirotation.BuildConfig.KAKAO_REST_API_KEY}",
            query = query,
            category_group_code = null,
            x = x,
            y = y,
            radius = null,
            rect = null,
            page = null,
            size = null,
            sort = null
        ).execute().body()
    }
}