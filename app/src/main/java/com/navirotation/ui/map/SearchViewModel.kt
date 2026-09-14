package com.navirotation.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.navirotation.data.map.SearchPlaceResult
import com.navirotation.repository.MapRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SearchViewModel @Inject constructor(private val mapRepository: MapRepository): ViewModel(){
    private val _searchPlaceData: MutableStateFlow<SearchPlaceResult> = MutableStateFlow(SearchPlaceResult())
    val searchPlaceData: StateFlow<SearchPlaceResult> = _searchPlaceData

    fun getSearchPlaceData(query: String, x: String, y: String) {
        viewModelScope.launch(Dispatchers.Default) {
            try{
                _searchPlaceData.emit(
                    SearchPlaceResult(success = mapRepository.getSearchPlaceData(query, x, y))
                )
            } catch (e: Exception) {
                _searchPlaceData.emit(
                    SearchPlaceResult(failure = e)
                )
            }
        }
    }
}