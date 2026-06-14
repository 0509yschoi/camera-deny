package com.example.studycapturehelper.data

import com.example.studycapturehelper.BuildConfig
import com.example.studycapturehelper.domain.AppUpdate
import com.example.studycapturehelper.domain.AppUpdateRepository
import com.example.studycapturehelper.domain.UpdateState
import com.example.studycapturehelper.domain.VersionComparator
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GitHubAppUpdateRepository @Inject constructor(
    private val api: GitHubReleaseApi,
    private val versionComparator: VersionComparator,
) : AppUpdateRepository {
    override suspend fun checkForUpdate(): UpdateState {
        val repository = BuildConfig.GITHUB_REPOSITORY.trim()
        val parts = repository.split('/')
        if (parts.size != 2 || parts.any(String::isBlank)) {
            return UpdateState.NotConfigured
        }

        return runCatching {
            val release = api.latestRelease(parts[0], parts[1])
            val apk = release.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
            if (
                release.draft ||
                release.prerelease ||
                apk == null ||
                !versionComparator.isNewer(release.tagName, BuildConfig.VERSION_NAME)
            ) {
                UpdateState.UpToDate
            } else {
                UpdateState.Available(
                    AppUpdate(
                        versionName = release.tagName.removePrefix("v"),
                        releaseNotes = release.body.orEmpty().take(1_000),
                        downloadUrl = apk.downloadUrl,
                        fileName = apk.name,
                    ),
                )
            }
        }.getOrElse {
            UpdateState.Error("업데이트 확인에 실패했습니다.")
        }
    }
}
