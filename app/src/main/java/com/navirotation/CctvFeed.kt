package com.navirotation

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class CctvFeed(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val streamUrl: String
) : Parcelable