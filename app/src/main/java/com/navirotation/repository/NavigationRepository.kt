package com.navirotation.repository

import com.kakaomobility.knsdk.common.util.FloatPoint
import com.navirotation.data.navigation.CoordConvertResponse
import com.navirotation.data.navigation.DistanceResponse
import com.navirotation.service.NavigationService
import javax.inject.Inject

class NavigationRepository @Inject constructor(private val navigationService: NavigationService) {
    fun getDistanceData(curDirection: FloatPoint, nextDirection: FloatPoint) : DistanceResponse? {
        return navigationService.distanceRequest(
            appKey = com.navirotation.BuildConfig.SK_APP_KEY,
            version = "1",
            startX = curDirection.x.toString(),
            startY = curDirection.y.toString(),
            endX = nextDirection.x.toString(),
            endY = nextDirection.y.toString(),
            "KATECH"
        ).execute().body()
    }

    fun getCoordConvertData(myLatitude: Double, myLongitude: Double): CoordConvertResponse? {
        return navigationService.coordConvertRequest(
            appKey = com.navirotation.BuildConfig.SK_APP_KEY,
            version = "1",
            lat = myLatitude.toString(),
            lon = myLongitude.toString(),
            fromCoord = "WGS84GEO",
            toCoord =  "KATECH"
        ).execute().body()
    }
}