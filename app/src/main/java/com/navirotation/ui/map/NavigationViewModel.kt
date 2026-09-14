package com.navirotation.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kakaomobility.knsdk.common.util.FloatPoint
import com.navirotation.data.navigation.CoordZipData
import com.navirotation.data.navigation.CoordZipResult
import com.navirotation.data.navigation.DistanceResult
import com.navirotation.repository.NavigationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.zip
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class NavigationViewModel @Inject constructor(
    private val navigationRepository: NavigationRepository
) : ViewModel() {

    private val _distanceData: MutableStateFlow<DistanceResult> = MutableStateFlow(DistanceResult())
    val distanceData: StateFlow<DistanceResult> = _distanceData

    private val _coordZipResult: MutableStateFlow<CoordZipResult> = MutableStateFlow(CoordZipResult())
    val coordZipResult: StateFlow<CoordZipResult> = _coordZipResult

    // suspend 버전만 공개적으로 사용!
    suspend fun convertWgsToKatechSync(lat: Double, lon: Double): FloatPoint? {
        return withContext(Dispatchers.IO) {
            val response = navigationRepository.getCoordConvertData(lat, lon)
            val coordinate = response?.coordinate
            if (coordinate != null) {
                val x = coordinate.lon?.toFloatOrNull() ?: 0f
                val y = coordinate.lat?.toFloatOrNull() ?: 0f
                FloatPoint(x, y)
            } else null
        }
    }

    fun getDistanceData(curDirection: FloatPoint, nextDirection: FloatPoint) {
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val result = navigationRepository.getDistanceData(curDirection, nextDirection)
                _distanceData.value = DistanceResult(success = result?.distanceInfo)
            } catch (e: Exception) {
                _distanceData.value = DistanceResult(failure = e)
            }
        }
    }

    fun getCoordConvertData(
        startLatitude: Double, startLongitude: Double,
        endLatitude: Double, endLongitude: Double
    ) {
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val startLocationFlow = flow {
                    emit(navigationRepository.getCoordConvertData(startLatitude, startLongitude)?.coordinate)
                }

                val endLocationFlow = flow {
                    emit(navigationRepository.getCoordConvertData(endLatitude, endLongitude)?.coordinate)
                }

                startLocationFlow.zip(endLocationFlow) { startLocation, endLocation ->
                    CoordZipResult(
                        success = CoordZipData(
                            startLatitude = startLocation?.lat,
                            startLongitude = startLocation?.lon,
                            endLatitude = endLocation?.lat,
                            endLongitude = endLocation?.lon
                        )
                    )
                }.collect { coordZipResult ->
                    _coordZipResult.value = coordZipResult
                }
            } catch (e: Exception) {
                _coordZipResult.value = CoordZipResult(failure = e)
            }
        }
    }
}
