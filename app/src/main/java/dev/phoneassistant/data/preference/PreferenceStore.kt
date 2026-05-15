package dev.phoneassistant.data.preference

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.phoneassistant.data.model.AssistantMode
import dev.phoneassistant.data.model.AssistantSettings
import dev.phoneassistant.data.model.DEFAULT_QWEN_MODEL
import dev.phoneassistant.data.model.TaskMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "assistant_settings")

class PreferenceStore(private val context: Context) {

    private val apiKeyKey = stringPreferencesKey("api_key")
    private val modelKey = stringPreferencesKey("model")
    private val modeKey = stringPreferencesKey("mode")
    private val taskModeKey = stringPreferencesKey("task_mode")

    val settingsFlow: Flow<AssistantSettings> = context.dataStore.data.map { preferences ->
        AssistantSettings(
            apiKey = preferences[apiKeyKey].orEmpty(),
            model = preferences[modelKey].orEmpty().ifBlank { DEFAULT_QWEN_MODEL },
            mode = preferences[modeKey]?.let { runCatching { AssistantMode.valueOf(it) }.getOrNull() }
                ?: AssistantMode.OFFLINE,
            taskMode = preferences[taskModeKey]?.let { runCatching { TaskMode.valueOf(it) }.getOrNull() }
                ?: TaskMode.CHAT
        )
    }

    suspend fun saveSettings(apiKey: String, model: String) {
        context.dataStore.edit { preferences ->
            preferences[apiKeyKey] = apiKey.trim()
            preferences[modelKey] = model.trim().ifBlank { DEFAULT_QWEN_MODEL }
        }
    }

    suspend fun saveMode(mode: AssistantMode) {
        context.dataStore.edit { preferences ->
            preferences[modeKey] = mode.name
        }
    }

    suspend fun saveTaskMode(taskMode: TaskMode) {
        context.dataStore.edit { preferences ->
            preferences[taskModeKey] = taskMode.name
        }
    }
}
