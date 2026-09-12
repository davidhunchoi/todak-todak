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
        private val KEY_USER_ID = stringPreferencesKey("user_id")
    }

    /**
     * 기기별 영구 고유 사용자 ID 반환 (없으면 생성 후 저장)
     * 매일 루틴의 "나" vs "상대" 완료 구분 및 서버 동기화의 기반
     */
    suspend fun getOrCreateUserId(): String {
        var userId = ""
        context.dataStore.edit { prefs ->
            userId = prefs[KEY_USER_ID] ?: java.util.UUID.randomUUID().toString().substring(0, 8)
            prefs[KEY_USER_ID] = userId
        }
        return userId
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
