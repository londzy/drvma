package com.raphta.drvma.model.data

import com.google.gson.annotations.SerializedName


data class KnownDriver(

        @SerializedName("id") val id: Int,
        @SerializedName("full_name") val name: String,
        @SerializedName("images") val images: List<String>

)
