package com.zionhuang.kugou.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

@Serializable
data class SearchSongResponse(
    val status: Int,
    val errcode: Int,
    val error: String,
    val data: Data,
) {
    @Serializable
    data class Data(
        val info: List<Info>,
    ) {
        @Serializable
        data class Info(
            val duration: Int,
            val hash: String,
            val songname: String? = null,
            val singername: String? = null,
            @SerialName("album_name") val albumName: String? = null,
        )
    }
}
