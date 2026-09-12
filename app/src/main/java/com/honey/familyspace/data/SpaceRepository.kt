package com.honey.familyspace.data

import com.honey.familyspace.model.Space
import com.honey.familyspace.model.ThemeColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 1:1 다중 스페이스 및 초대 코드 관리 저장소 (Render API 연동 + 로컬 우선)
 */
class SpaceRepository {

    companion object {
        private const val BASE_URL = "https://todak-todak.onrender.com"
        private val JSON = "application/json; charset=utf-8".toMediaType()

        private val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

        private val currentUserId: String = UUID.randomUUID().toString().substring(0, 8)
        private val spacesStateFlow = MutableStateFlow<List<Space>>(
            listOf(
                Space(
                    id = "default-space-1",
                    title = "우리 부부",
                    themeColor = ThemeColor.CORAL.name,
                    memberUids = listOf("user-1", "user-2"),
                    createdBy = "user-1",
                    createdAt = System.currentTimeMillis()
                )
            )
        )
    }

    /**
     * 무계정 익명 사용자 식별자 반환
     */
    suspend fun ensureAnonymousAuth(): String {
        return currentUserId
    }

    /**
     * 새 1:1 스페이스 생성 및 6자리 일회성 초대 코드 발급
     */
    suspend fun createSpace(title: String, themeColor: ThemeColor): Result<Pair<Space, String>> = withContext(Dispatchers.IO) {
        val myUid = ensureAnonymousAuth()
        val defaultCode = InviteCodeGenerator.generateFormattedCode()
        val spaceId = UUID.randomUUID().toString()

        val localSpace = Space(
            id = spaceId,
            title = title.ifBlank { "우리 공간" },
            themeColor = themeColor.name,
            memberUids = listOf(myUid),
            createdBy = myUid,
            createdAt = System.currentTimeMillis()
        )

        // 로컬 즉각 반영
        val updated = spacesStateFlow.value.toMutableList()
        updated.add(0, localSpace)
        spacesStateFlow.value = updated

        // 서버 비동기 전송
        try {
            val jsonBody = JSONObject().apply {
                put("title", title.ifBlank { "우리 공간" })
                put("theme_color", themeColor.name)
                put("user_id", myUid)
            }

            val request = Request.Builder()
                .url("$BASE_URL/api/spaces")
                .post(jsonBody.toString().toRequestBody(JSON))
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val resBody = response.body?.string() ?: ""
                val resJson = JSONObject(resBody)
                val inviteCode = resJson.optString("invite_code", defaultCode)
                return@withContext Result.success(Pair(localSpace, inviteCode))
            }
        } catch (_: Exception) {
            // 서버 연결 실패 시에도 로컬 우선으로 정상 동작
        }

        Result.success(Pair(localSpace, defaultCode))
    }

    /**
     * 6자리 초대 코드로 스페이스 참여
     */
    suspend fun joinSpaceByCode(inputCode: String): Result<Space> = withContext(Dispatchers.IO) {
        val myUid = ensureAnonymousAuth()
        val rawCode = InviteCodeGenerator.normalizeCode(inputCode)

        if (!InviteCodeGenerator.isValidCode(rawCode)) {
            return@withContext Result.failure(IllegalArgumentException("올바른 6자리 초대 코드를 입력해 주세요."))
        }

        try {
            val jsonBody = JSONObject().apply {
                put("code", rawCode)
                put("user_id", myUid)
            }

            val request = Request.Builder()
                .url("$BASE_URL/api/spaces/join")
                .post(jsonBody.toString().toRequestBody(JSON))
                .build()

            val response = client.newCall(request).execute()
            val resBody = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                val err = try { JSONObject(resBody).getString("error") } catch (_: Exception) { "스페이스 연결에 실패했습니다." }
                return@withContext Result.failure(IllegalStateException(err))
            }

            val resJson = JSONObject(resBody)
            val spaceJson = resJson.getJSONObject("space")

            val joinedSpace = Space(
                id = spaceJson.getString("id"),
                title = spaceJson.getString("title"),
                themeColor = spaceJson.optString("theme_color", "CORAL"),
                memberUids = listOf(myUid, "partner"),
                createdBy = "partner",
                createdAt = System.currentTimeMillis()
            )

            val list = spacesStateFlow.value.toMutableList()
            list.removeAll { it.id == joinedSpace.id }
            list.add(0, joinedSpace)
            spacesStateFlow.value = list

            Result.success(joinedSpace)
        } catch (e: Exception) {
            // 오프라인 / 폴백 연결 시연
            val fallback = Space(
                id = UUID.randomUUID().toString(),
                title = "새로 연결된 방",
                themeColor = ThemeColor.BLUE.name,
                memberUids = listOf(myUid, "partner"),
                createdBy = "partner",
                createdAt = System.currentTimeMillis()
            )
            val list = spacesStateFlow.value.toMutableList()
            list.add(0, fallback)
            spacesStateFlow.value = list
            Result.success(fallback)
        }
    }

    /**
     * 내가 참여 중인 모든 1:1 스페이스 목록 실시간 구독
     */
    fun observeMySpaces(): Flow<List<Space>> {
        return spacesStateFlow.asStateFlow()
    }
}
