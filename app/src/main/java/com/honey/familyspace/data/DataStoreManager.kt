package com.honey.familyspace.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "family_space_prefs")

/**
 * 사용자 기기 로컬 설정 저장소 (Jetpack DataStore 기반)
 */
class DataStoreManager(private val context: Context) {

    companion object {
        private val KEY_ACTIVE_SPACE_ID = stringPreferencesKey("active_space_id")
        private val KEY_USER_NICKNAME = stringPreferencesKey("user_nickname")
    }

    /**
     * 현재 선택된 활성 스페이스 ID 스트림
     */
    val activeSpaceIdFlow: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[KEY_ACTIVE_SPACE_ID]
    }

    /**
     * 현재 활성 스페이스 ID 저장
     */
    suspend fun setActiveSpaceId(spaceId: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_ACTIVE_SPACE_ID] = spaceId
        }
    }

    /**
     * 사용자 닉네임 스트림 (기본값: "나")
     */
    val userNicknameFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_USER_NICKNAME] ?: "나"
    }

    /**
     * 사용자 닉네임 저장
     */
    suspend fun setUserNickname(nickname: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_USER_NICKNAME] = nickname
        }
    }
}
