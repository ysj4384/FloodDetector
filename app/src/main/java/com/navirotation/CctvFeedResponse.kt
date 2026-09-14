package com.navirotation

import com.google.gson.annotations.SerializedName

/**
 * CCTV 피드 API 응답 전체를 감싸는 데이터 클래스
 *
 * @param feeds CCTV 피드 목록
 */
data class CctvFeedResponse(
    @SerializedName("feeds") val feeds: List<CctvFeed>
)