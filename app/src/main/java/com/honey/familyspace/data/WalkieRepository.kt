package com.honey.familyspace.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

data class WalkieMessage(
    val id: String,
    val senderId: String,
    val audioBase64: String,
    val durationMs: Int,
    val createdAt: Long
)

/**
 * 무전기(PTT) 음성 데이터 송수신 레포지토리
 */
class WalkieRepository(private val dataStore: DataStoreManager) {

    companion object {
        private const val BASE_URL = "https://todak-todak-ruby.vercel.app"
    }

    /**
     * 녹음된 음성 메시지 서버 전송 (Base64)
     */
    suspend fun send(spaceId: String, audioBase64: String, durationMs: Int): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val myUid = dataStore.getOrCreateUserId()
            val url = URL("$BASE_URL/api/spaces/$spaceId/walkie/send")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10000
                readTimeout = 15000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
            }

            val payload = JSONObject().apply {
                put("sender_id", myUid)
                put("audio_base64", audioBase64)
                put("duration_ms", durationMs)
            }

            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(payload.toString()) }

            val responseCode = conn.responseCode
            if (responseCode in 200..299) {
                val resp = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(resp)
                json.optString("id", "")
            } else {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP $responseCode"
                throw Exception("무전 전송 실패: $err")
            }
        }
    }

    /**
     * 상대방이 보낸 최신 미재생 무전 메시지 폴링
     */
    suspend fun pollLatest(spaceId: String, afterMs: Long): WalkieMessage? = withContext(Dispatchers.IO) {
        runCatching {
            val myUid = dataStore.getOrCreateUserId()
            val url = URL("$BASE_URL/api/spaces/$spaceId/walkie/latest?my_user_id=$myUid&after_ms=$afterMs")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5000
                readTimeout = 5000
                setRequestProperty("Accept", "application/json")
            }

            if (conn.responseCode in 200..299) {
                val resp = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(resp)
                val msgObj = json.optJSONObject("message") ?: return@runCatching null
                WalkieMessage(
                    id = msgObj.optString("id"),
                    senderId = msgObj.optString("sender_id"),
                    audioBase64 = msgObj.optString("audio_base64"),
                    durationMs = msgObj.optInt("duration_ms", 0),
                    createdAt = msgObj.optLong("created_at", 0L)
                )
            } else {
                null
            }
        }.getOrNull()
    }

    /**
     * 재생 완료 상태를 서버에 통보 (is_played = 1)
     */
    suspend fun markPlayed(spaceId: String, msgId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val url = URL("$BASE_URL/api/spaces/$spaceId/walkie/$msgId/played")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 5000
                readTimeout = 5000
                setRequestProperty("Content-Type", "application/json")
            }
            if (conn.responseCode !in 200..299) {
                throw Exception("재생 완료 처리 실패: ${conn.responseCode}")
            }
            Unit
        }
    }
}
