package com.example.studycapturehelper.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.studycapturehelper.domain.CaptureSettings
import com.example.studycapturehelper.domain.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("capture_settings")

@Singleton
class SettingsRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : SettingsRepository {
    override val settings: Flow<CaptureSettings> = context.dataStore.data.map { values ->
        CaptureSettings(
            intervalSeconds = values[INTERVAL_SECONDS] ?: 50,
            speechEnabled = values[SPEECH_ENABLED] ?: true,
            dndEnabled = values[DND_ENABLED] ?: false,
        )
    }

    override suspend fun setIntervalSeconds(seconds: Int) {
        context.dataStore.edit { it[INTERVAL_SECONDS] = seconds.coerceIn(15, 3_600) }
    }

    override suspend fun setSpeechEnabled(enabled: Boolean) {
        context.dataStore.edit { it[SPEECH_ENABLED] = enabled }
    }

    override suspend fun setDndEnabled(enabled: Boolean) {
        context.dataStore.edit { it[DND_ENABLED] = enabled }
    }

    private companion object {
        val INTERVAL_SECONDS = intPreferencesKey("interval_seconds")
        val SPEECH_ENABLED = booleanPreferencesKey("speech_enabled")
        val DND_ENABLED = booleanPreferencesKey("dnd_enabled")
    }
}
