package com.navirotation.data.map

import com.google.gson.annotations.SerializedName

data class SearchPlaceResponse(
    @SerializedName("meta") val meta: Meta,
    @SerializedName("documents") val documents: List<Document>
) {
    data class Meta(
        @SerializedName("total_count") val totalCount: Int,
        @SerializedName("pageable_count") val pageableCount: Int,
        @SerializedName("is_end") val isEnd: Boolean,
        @SerializedName("same_name") val sameName: SameName,
    ) {
        data class SameName(
            @SerializedName("region") val region: List<String>,
            @SerializedName("keyword") val keyword: String,
            @SerializedName("selected_region") val selectedRegion: String,
        )
    }

    data class Document(
        @SerializedName("id") val id: String,
        @SerializedName("place_name") val placeName: String,
        @SerializedName("category_name") val categoryName: String,
        @SerializedName("category_group_code") val categoryGroupCode: String,
        @SerializedName("category_group_name") val categoryGroupName: String,
        @SerializedName("phone") val phone: String,
        @SerializedName("address_name") val addressName: String,
        @SerializedName("road_address_name") val roadAddressName: String,
        @SerializedName("x") val x: String,
        @SerializedName("y") val y: String,
        @SerializedName("place_url") val placeUrl: String,
        @SerializedName("distance") var distance: String,
    )
}