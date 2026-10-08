package com.honey.familyspace.data

import android.util.Log
import com.honey.familyspace.util.VoiceParseResult
import com.honey.familyspace.util.VoiceTargetType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * 마이 내비게이터(My Navigator) 웹 서버로 Gantt Task 및 인생 그래프 데이터를 전송하는 클라이언트
 */
class NavigatorSyncClient(private val dataStoreManager: DataStoreManager) {

    companion object {
        private const val TAG = "NavigatorSyncClient"
        private const val TIMEOUT_MS = 5000
    }

    /**
     * 음성 파싱 결과를 마이 내비게이터 웹 서버로 비동기 전송
     */
    suspend fun syncParsedVoiceData(parseResult: VoiceParseResult): Boolean = withContext(Dispatchers.IO) {
        try {
            val isEnabled = dataStoreManager.navigatorEnabledFlow.first()
            val serverUrl = dataStoreManager.navigatorServerUrlFlow.first()
            val apiKey = dataStoreManager.navigatorApiKeyFlow.first()
            val defaultGroup = dataStoreManager.navigatorDefaultTaskGroupFlow.first()

            if (!isEnabled || serverUrl.isBlank()) {
                Log.d(TAG, "마이 내비게이터 연동이 꺼져 있거나 서버 주소가 없습니다.")
                return@withContext false
            }

            val endpoint = when (parseResult.targetType) {
                VoiceTargetType.LIFE_GRAPH -> "$serverUrl/api/external/life-graph"
                else -> "$serverUrl/api/external/gantt-task"
            }

            val targetProject = defaultGroup.ifBlank { "토닥토닥" }

            val jsonPayload = JSONObject().apply {
                put("title", parseResult.content)
                put("content", parseResult.content)
                put("target_date", parseResult.targetDate)
                put("target_type", parseResult.targetType.name)
                // 🌟 아무 프로젝트에 섞이지 않고 '토닥토닥' 간트 차트로 직행 (없으면 자동 생성)
                put("project_name", targetProject)
                put("project", targetProject)
                put("default_group", targetProject)
                put("parent_task", targetProject)
                put("task_group", targetProject)
                put("auto_create_project", true)
                put("raw_text", parseResult.rawText)
                put("source", "todak_quick_voice")
                put("timestamp", System.currentTimeMillis())
            }

            val url = URL(endpoint)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                setRequestProperty("Accept", "application/json")
                if (apiKey.isNotBlank()) {
                    setRequestProperty("Authorization", "Bearer $apiKey")
                    setRequestProperty("X-API-Key", apiKey)
                }
            }

            OutputStreamWriter(connection.outputStream, "UTF-8").use { writer ->
                writer.write(jsonPayload.toString())
                writer.flush()
            }

            val responseCode = connection.responseCode
            val isSuccess = responseCode in 200..299
            Log.i(TAG, "마이 내비게이터 전송 결과 [$responseCode]: $isSuccess (Endpoint: $endpoint)")

            connection.disconnect()
            isSuccess
        } catch (e: Exception) {
            Log.e(TAG, "마이 내비게이터 전송 중 오류 발생: ${e.localizedMessage}")
            false
        }
    }
}
