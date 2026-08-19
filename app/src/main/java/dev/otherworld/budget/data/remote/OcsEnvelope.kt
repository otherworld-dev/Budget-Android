package dev.otherworld.budget.data.remote

import kotlinx.serialization.Serializable

@Serializable data class OcsResponse<T>(val ocs: OcsBody<T>)
@Serializable data class OcsBody<T>(val meta: OcsMeta, val data: T)
@Serializable data class OcsMeta(
    val status: String,
    val statuscode: Int,
    val message: String? = null,
)
