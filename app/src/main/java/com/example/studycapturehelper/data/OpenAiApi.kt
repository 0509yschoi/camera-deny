package com.example.studycapturehelper.data

import com.squareup.moshi.Json
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

interface OpenAiApi {
    @POST("v1/responses")
    suspend fun createResponse(
        @Header("Authorization") authorization: String,
        @Body request: ResponseRequest,
    ): ResponseDto
}

data class ResponseRequest(
    val model: String,
    val input: List<InputMessage>,
    @Json(name = "max_output_tokens") val maxOutputTokens: Int = 300,
)

data class InputMessage(
    val role: String,
    val content: List<InputContent>,
)

data class InputContent(
    val type: String,
    val text: String? = null,
    @Json(name = "image_url") val imageUrl: String? = null,
    val detail: String? = null,
)

data class ResponseDto(
    val output: List<ResponseOutput> = emptyList(),
)

data class ResponseOutput(
    val content: List<ResponseContent> = emptyList(),
)

data class ResponseContent(
    val type: String,
    val text: String? = null,
)
