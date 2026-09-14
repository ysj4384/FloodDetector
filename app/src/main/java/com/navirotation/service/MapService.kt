package com.navirotation.service

import com.navirotation.data.map.SearchPlaceResponse
import retrofit2.Call
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Query

interface MapService {

    /**
     * 카카오 키워드로 장소 검색하기
     */
    @GET("search/keyword.json")
    fun searchPlaceRequest(
        @Header("Authorization") Authorization: String,
        @Query("query") query: String,
        @Query("category_group_code") category_group_code: String?,
        @Query("x") x: String?,
        @Query("y") y: String?,
        @Query("radius") radius: Int?,
        @Query("rect") rect: String?,
        @Query("page") page: Int?,
        @Query("size") size: Int?,
        @Query("sort") sort: String?
    ) : Call<SearchPlaceResponse>
}