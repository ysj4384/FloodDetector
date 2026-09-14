package com.navirotation.data.navigation

import com.google.gson.annotations.SerializedName

data class CoordConvertResponse(
    @SerializedName("coordinate") val coordinate: LocationInfo
) {
    data class LocationInfo(
        @SerializedName("lat") val lat: String,
        @SerializedName("lon") val lon: String
    )
}