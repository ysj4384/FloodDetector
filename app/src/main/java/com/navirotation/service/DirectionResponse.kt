// File: app/src/main/java/com/navirotation/service/DirectionResponse.kt
package com.navirotation.service

import com.google.gson.annotations.SerializedName

data class DirectionResponse(
    @SerializedName("route")
    val route: RouteContainer
) {
    data class RouteContainer(
        @SerializedName("trafast")
        val trafast: List<Route> = emptyList()   // 기본값 emptyList()로
    )

    data class Route(
        @SerializedName("summary")
        val summary: Summary,
        @SerializedName("path")
        val path: List<List<Double>>             // [ [lon, lat], … ]
    )

    data class Summary(
        @SerializedName("distance")
        val distance: Int,
        @SerializedName("duration")
        val duration: Int
    )
}