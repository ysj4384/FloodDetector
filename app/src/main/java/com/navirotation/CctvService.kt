// File: app/src/main/java/com/navirotation/service/CctvService.kt
package com.navirotation
import retrofit2.Call
import retrofit2.http.GET
import retrofit2.http.Query

interface CctvService {
    @GET("cctvInfo")
    fun getCctvInfo(
        @Query("apiKey")   apiKey:   String,
        @Query("type")     type:     String,
        @Query("cctvType") cctvType: String,
        @Query("minX")     minX:     String,
        @Query("maxX")     maxX:     String,
        @Query("minY")     minY:     String,
        @Query("maxY")     maxY:     String,
        @Query("getType")  getType:  String
    ): Call<String>
}