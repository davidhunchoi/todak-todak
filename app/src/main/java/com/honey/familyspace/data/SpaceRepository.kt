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
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 1:1 다중 스페이스 및 초대 코드 관리 저장소 (Render API 연동 + 로컬 우선)
 */
class SpaceRepository(private val dataStore: DataStoreManager? = null) {

    companion object {
        private const val BASE_URL = "https://todak-todak.onrender.com"
        private val JSON = "application/json; charset=utf-8".toMediaType()

        private val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

        private val spacesStateFlow = MutableStateFlow<List<Space>>(emptyList())
    }

    /**
     * 무계정 익명 사용자 식별자 반환 (DataStore 영구 ID, 주입 안 되면 프로세스 임시 ID)
     */
    suspend fun ensureAnonymousAuth(): String {
        return dataStore?.getOrCreateUserId() ?: "local-user"
    }

    /**
     * 새 1:1 스페이스 생성 및 4자리 숫자 일회성 초대 코드 발급
     * (같은 이름의 방 중복 금지 — 로컬 즉시 검사 + 서버 재검사)
     */
    suspend fun createSpace(title: String, themeColor: ThemeColor): Result<Pair<Space, String>> = withContext(Dispatchers.IO) {
        val myUid = ensureAnonymousAuth()
        val trimmedTitle = title.trim()

        // 로컬 중복 검사 (서버 응답 전 즉시 피드백)
        val localDuplicate = spacesStateFlow.value.any { it.title.equals(trimmedTitle, ignoreCase = true) }
        if (localDuplicate) {
            return@withContext Result.failure(
                IllegalArgumentException("같은 이름의 방이 이미 있어요. 다른 이름을 사용해 주세요.")
            )
        }

        val defaultCode = InviteCodeGenerator.generateFormattedCode()
        val spaceId = UUID.randomUUID().toString()

        val localSpace = Space(
            id = spaceId,
            title = trimmedTitle.ifBlank { "우리 공간" },
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
                put("title", trimmedTitle.ifBlank { "우리 공간" })
                put("theme_color", themeColor.name)
                put("user_id", myUid)
            }

            val request = Request.Builder()
                .url("$BASE_URL/api/spaces")
                .post(jsonBody.toString().toRequestBody(JSON))
                .build()

            val response = client.newCall(request).execute()
            val resBody = response.body?.string() ?: ""

            if (response.isSuccessful) {
                val resJson = JSONObject(resBody)
                val inviteCode = resJson.optString("invite_code", defaultCode).ifBlank { defaultCode }
                // ⚠️ 서버가 발급한 space_id로 교체 (서버 초대 코드는 서버 ID에 귀속됨)
                // 로컬에서 미리 만든 UUID를 그대로 쓰면, 공유된 코드가 다른 방을 가리켜
                // 아내 쪽에서 항상 "유효하지 않거나 이미 사용 완료된 코드"가 뜬다.
                val spaceJson = resJson.optJSONObject("space")
                val serverSpace = if (spaceJson != null) {
                    Space(
                        id = spaceJson.optString("id", spaceId).ifBlank { spaceId },
                        title = spaceJson.optString("title", trimmedTitle.ifBlank { "우리 공간" }),
                        themeColor = spaceJson.optString("theme_color", themeColor.name),
                        memberUids = listOf(myUid),
                        createdBy = myUid,
                        createdAt = System.currentTimeMillis()
                    )
                } else {
                    localSpace
                }
                spacesStateFlow.value = spacesStateFlow.value
                    .filterNot { it.id == spaceId || it.id == serverSpace.id }
                    .toMutableList().apply { add(0, serverSpace) }
                dataStore?.setActiveSpaceId(serverSpace.id)
                return@withContext Result.success(Pair(serverSpace, inviteCode))
            }

            // 서버 거부 (같은 이름 중복 등) → 로컬 추가 롤백 후 실패 반환
            val err = try { JSONObject(resBody).getString("error") } catch (_: Exception) { "방 만들기에 실패했습니다." }
            spacesStateFlow.value = spacesStateFlow.value.filterNot { it.id == spaceId }
            return@withContext Result.failure(IllegalStateException(err))
        } catch (_: Exception) {
            // 서버 연결 실패 시에도 로컬 우선으로 정상 동작
        }

        Result.success(Pair(localSpace, defaultCode))
    }

    /**
     * 4자리 숫자 초대 코드로 스페이스 참여
     */
    suspend fun joinSpaceByCode(inputCode: String): Result<Space> = withContext(Dispatchers.IO) {
        val myUid = ensureAnonymousAuth()
        val rawCode = InviteCodeGenerator.normalizeCode(inputCode)

        if (!InviteCodeGenerator.isValidCode(rawCode)) {
            return@withContext Result.failure(IllegalArgumentException("올바른 4자리 숫자 초대 코드를 입력해 주세요."))
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
     * 스페이스의 유효한 초대 코드 조회 (서버에 없거나 만료 시 자동 재발급)
     * 서버 연결 실패 시 새 로컬 코드로 폴백 (createSpace와 동일한 로컬 우선 정책)
     */
    suspend fun getOrRefreshInviteCode(spaceId: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$BASE_URL/api/spaces/$spaceId/invite")
                .get()
                .build()

            val response = client.newCall(request).execute()
            val resBody = response.body?.string() ?: ""

            if (response.isSuccessful) {
                val code = JSONObject(resBody).optString("invite_code", "")
                if (code.isNotBlank()) {
                    return@withContext Result.success(code)
                }
            }
        } catch (_: Exception) {
            // 서버 연결 실패 시 아래 로컬 폴백 사용
        }

        // 로컬 폴백: 새 코드 생성 (서버에 등록되지 않아 상대 연결은 서버 복구 후 필요)
        Result.success(InviteCodeGenerator.generateFormattedCode())
    }

    /**
     * 서버에서 내가 참여 중인 모든 스페이스 목록 동기화
     */
    suspend fun syncSpacesFromServer(): Result<List<Space>> = withContext(Dispatchers.IO) {
        val myUid = ensureAnonymousAuth()
        try {
            val request = Request.Builder()
                .url("$BASE_URL/api/users/$myUid/spaces")
                .get()
                .build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(IllegalStateException("스페이스 동기화 실패 (${response.code})"))
            }
            val resBody = response.body?.string() ?: ""
            val json = JSONObject(resBody)
            val arr = json.optJSONArray("spaces") ?: JSONArray()
            val list = mutableListOf<Space>()
            for (i in 0 until arr.length()) {
                val s = arr.getJSONObject(i)
                list.add(
                    Space(
                        id = s.getString("id"),
                        title = s.getString("title"),
                        themeColor = s.optString("theme_color", "CORAL"),
                        memberUids = listOf(myUid),
                        createdBy = s.optString("created_by", myUid),
                        createdAt = s.optLong("created_at", System.currentTimeMillis())
                    )
                )
            }
            // 서버에 방이 있으면 서버 데이터로 갱신 (로컬 전용 방 보존)
            if (list.isNotEmpty()) {
                val serverIds = list.map { it.id }.toSet()
                val locals = spacesStateFlow.value.filter { it.id !in serverIds }
                spacesStateFlow.value = list + locals
            }
            Result.success(spacesStateFlow.value)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 방 삭제 동의 요청 상태
     * @param myRequested 내가 삭제를 요청한 상태인지
     * @param otherRequested 상대방이 삭제를 요청했는지 (동의 대기)
     * @param memberCount 방 멤버 수 (1명이면 삭제 시 상대 동의 불필요)
     */
    data class SpaceDeleteStatus(
        val myRequested: Boolean,
        val otherRequested: Boolean,
        val memberCount: Int
    )

    /**
     * 방 삭제 요청 전송.
     * @return Result(Boolean) — Boolean이 true면 이미 삭제 완료(전원 동의 또는 1인 방), false면 상대 동의 대기
     */
    suspend fun requestDeleteSpace(spaceId: String): Result<Boolean> = withContext(Dispatchers.IO) {
        val myUid = ensureAnonymousAuth()
        try {
            val jsonBody = JSONObject().apply { put("user_id", myUid) }
            val request = Request.Builder()
                .url("$BASE_URL/api/spaces/$spaceId/delete-request")
                .post(jsonBody.toString().toRequestBody(JSON))
                .build()

            val response = client.newCall(request).execute()
            val resBody = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                val err = try { JSONObject(resBody).getString("error") } catch (_: Exception) { "삭제 요청에 실패했습니다." }
                return@withContext Result.failure(IllegalStateException(err))
            }

            val deleted = JSONObject(resBody).optBoolean("deleted", false)
            if (deleted) {
                removeSpaceLocally(spaceId)
            }
            Result.success(deleted)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 방 삭제 요청 상태 조회 (폴링용)
     */
    suspend fun getDeleteRequestStatus(spaceId: String): Result<SpaceDeleteStatus> = withContext(Dispatchers.IO) {
        val myUid = ensureAnonymousAuth()
        try {
            val request = Request.Builder()
                .url("$BASE_URL/api/spaces/$spaceId/delete-request?user_id=$myUid")
                .get()
                .build()

            val response = client.newCall(request).execute()
            val resBody = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                return@withContext Result.failure(IllegalStateException("삭제 요청 상태 조회 실패"))
            }

            val json = JSONObject(resBody)
            Result.success(
                SpaceDeleteStatus(
                    myRequested = json.optBoolean("my_requested", false),
                    otherRequested = json.optBoolean("other_requested", false),
                    memberCount = json.optInt("member_count", 0)
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 방 삭제 요청 취소/거절 (상대방이 거절해도 요청 전체가 해제됨)
     */
    suspend fun cancelDeleteSpace(spaceId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$BASE_URL/api/spaces/$spaceId/delete-request")
                .delete()
                .build()
            client.newCall(request).execute()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 삭제된 방을 로컬 목록에서 즉시 제거
     */
    fun removeSpaceLocally(spaceId: String) {
        spacesStateFlow.value = spacesStateFlow.value.filterNot { it.id == spaceId }
    }

    /**
     * 내가 참여 중인 모든 1:1 스페이스 목록 실시간 구독
     */
    fun observeMySpaces(): Flow<List<Space>> {
        return spacesStateFlow.asStateFlow()
    }
}
