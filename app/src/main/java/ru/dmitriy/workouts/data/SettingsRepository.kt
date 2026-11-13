package ru.dmitriy.workouts.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private val Context.preferences by preferencesDataStore("settings")
@Serializable data class AppSettings(
    val onboarded:Boolean=false, val aiConsent:Boolean=false,
    val includeNotes:Boolean=false, val sound:Boolean=true, val vibration:Boolean=true,
    val includeChatsInBackup:Boolean=false
)
@Singleton class SettingsRepository @Inject constructor(@ApplicationContext private val context:Context) {
    private val key=stringPreferencesKey("settings_v1")
    private val json=Json { ignoreUnknownKeys=true }
    val settings=context.preferences.data.map { p -> p[key]?.let { runCatching { json.decodeFromString<AppSettings>(it) }.getOrNull() } ?: AppSettings() }
    suspend fun update(transform:(AppSettings)->AppSettings) { context.preferences.edit { p ->
        val old=p[key]?.let { runCatching { json.decodeFromString<AppSettings>(it) }.getOrNull() } ?: AppSettings()
        p[key]=json.encodeToString(transform(old))
    } }
}
