package com.example.studycapturehelper.data

import com.squareup.moshi.Json
import retrofit2.http.GET
import retrofit2.http.Path

interface GitHubReleaseApi {
    @GET("repos/{owner}/{repository}/releases/latest")
    suspend fun latestRelease(
        @Path("owner") owner: String,
        @Path("repository") repository: String,
    ): GitHubReleaseDto
}

data class GitHubReleaseDto(
    @Json(name = "tag_name") val tagName: String,
    val name: String? = null,
    val body: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<GitHubAssetDto> = emptyList(),
)

data class GitHubAssetDto(
    val name: String,
    @Json(name = "browser_download_url") val downloadUrl: String,
)
