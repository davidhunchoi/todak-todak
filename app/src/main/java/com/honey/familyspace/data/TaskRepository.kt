package com.honey.familyspace.data

import com.honey.familyspace.model.DailyRoutine
import com.honey.familyspace.model.Task
import com.honey.familyspace.util.DateTimeUtils
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
 * 할 일(`Task`) 및 매일 루틴(`DailyRoutine`) 실시간 저장소 (Render API + 로컬 우선)
 */
class TaskRepository(private val dataStore: DataStoreManager? = null) {

    companion object {
        private const val BASE_URL = "https://todak-todak.onrender.com"
        private val JSON = "application/json; charset=utf-8".toMediaType()

        private val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

        private val tasksMap = mutableMapOf<String, MutableStateFlow<List<Task>>>()
        private val routinesMap = mutableMapOf<String, MutableStateFlow<List<DailyRoutine>>>()
    }

    /**
     * 기기 고유 사용자 ID 반환 (DataStore 기반 영구 ID, 주입 안 되면 임시 ID)
     * 매일 루틴의 "나" vs "상대" 완료 구분에 사용
     */
    private suspend fun myUserId(): String {
        return dataStore?.getOrCreateUserId() ?: "local-user"
    }

    private fun getTaskFlow(spaceId: String): MutableStateFlow<List<Task>> {
        return tasksMap.getOrPut(spaceId) {
            MutableStateFlow(emptyList())
        }
    }

    private fun getRoutineFlow(spaceId: String): MutableStateFlow<List<DailyRoutine>> {
        return routinesMap.getOrPut(spaceId) {
            MutableStateFlow(emptyList())
        }
    }

    // ==========================================
    // 1. 할 일 (Task) 실시간 관리
    // ==========================================

    fun observeTasks(spaceId: String): Flow<List<Task>> {
        return getTaskFlow(spaceId).asStateFlow()
    }

    suspend fun addTask(
        spaceId: String,
        title: String,
        dueDate: String,
        alarmTime: String? = null,
        hasAlarm: Boolean = false
    ): Result<Task> = withContext(Dispatchers.IO) {
        val taskId = UUID.randomUUID().toString()
        val newTask = Task(
            id = taskId,
            spaceId = spaceId,
            title = title.trim(),
            dueDate = dueDate.trim(),
            isCompleted = false,
            createdAt = System.currentTimeMillis(),
            alarmTime = alarmTime,
            hasAlarm = hasAlarm
        )

        // 로컬 즉각 반영 (0.1초 반응)
        val flow = getTaskFlow(spaceId)
        flow.value = listOf(newTask) + flow.value

        // 특정 시각 소리 알람 예약
        dataStore?.context?.let { ctx ->
            if (hasAlarm) {
                com.honey.familyspace.notification.TaskAlarmScheduler.scheduleTaskAlarm(ctx, newTask)
            }
        }

        // 백그라운드 서버 전송
        try {
            val jsonBody = JSONObject().apply {
                put("title", title)
                put("due_date", dueDate)
                put("user_id", myUserId())
                put("alarm_time", alarmTime ?: "")
                put("has_alarm", if (hasAlarm) 1 else 0)
            }
            val request = Request.Builder()
                .url("$BASE_URL/api/spaces/$spaceId/tasks")
                .post(jsonBody.toString().toRequestBody(JSON))
                .build()
            client.newCall(request).execute()
        } catch (_: Exception) {}

        Result.success(newTask)
    }

    suspend fun toggleTask(spaceId: String, taskId: String, currentStatus: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        val newStatus = !currentStatus
        val flow = getTaskFlow(spaceId)
        var toggledTask: Task? = null
        flow.value = flow.value.map {
            if (it.id == taskId) {
                val updated = it.copy(isCompleted = newStatus, completedAt = if (newStatus) System.currentTimeMillis() else null)
                toggledTask = updated
                updated
            } else it
        }

        // 완료 상태가 되면 알람 자동 취소
        dataStore?.context?.let { ctx ->
            if (newStatus) {
                com.honey.familyspace.notification.TaskAlarmScheduler.cancelTaskAlarm(ctx, taskId)
            } else {
                toggledTask?.let { task ->
                    if (task.hasAlarm) {
                        com.honey.familyspace.notification.TaskAlarmScheduler.scheduleTaskAlarm(ctx, task)
                    }
                }
            }
        }

        try {
            val jsonBody = JSONObject().apply {
                put("is_completed", newStatus)
            }
            val request = Request.Builder()
                .url("$BASE_URL/api/spaces/$spaceId/tasks/$taskId")
                .patch(jsonBody.toString().toRequestBody(JSON))
                .build()
            client.newCall(request).execute()
        } catch (_: Exception) {}

        Result.success(Unit)
    }

    suspend fun updateTask(
        spaceId: String,
        taskId: String,
        newTitle: String,
        newDueDate: String,
        newAlarmTime: String? = null,
        newHasAlarm: Boolean = false
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val flow = getTaskFlow(spaceId)
        var updatedTask: Task? = null
        flow.value = flow.value.map {
            if (it.id == taskId) {
                val updated = it.copy(
                    title = newTitle,
                    dueDate = newDueDate,
                    alarmTime = newAlarmTime,
                    hasAlarm = newHasAlarm
                )
                updatedTask = updated
                updated
            } else it
        }

        // 알람 스케줄 갱신
        dataStore?.context?.let { ctx ->
            if (newHasAlarm && updatedTask != null) {
                com.honey.familyspace.notification.TaskAlarmScheduler.scheduleTaskAlarm(ctx, updatedTask!!)
            } else {
                com.honey.familyspace.notification.TaskAlarmScheduler.cancelTaskAlarm(ctx, taskId)
            }
        }

        try {
            val jsonBody = JSONObject().apply {
                put("title", newTitle)
                put("due_date", newDueDate)
                put("alarm_time", newAlarmTime ?: "")
                put("has_alarm", if (newHasAlarm) 1 else 0)
            }
            val request = Request.Builder()
                .url("$BASE_URL/api/spaces/$spaceId/tasks/$taskId")
                .patch(jsonBody.toString().toRequestBody(JSON))
                .build()
            client.newCall(request).execute()
        } catch (_: Exception) {}

        Result.success(Unit)
    }

    suspend fun deleteTask(spaceId: String, taskId: String): Result<Unit> = withContext(Dispatchers.IO) {
        val flow = getTaskFlow(spaceId)
        flow.value = flow.value.filter { it.id != taskId }

        // 삭제된 할 일의 알람 취소
        dataStore?.context?.let { ctx ->
            com.honey.familyspace.notification.TaskAlarmScheduler.cancelTaskAlarm(ctx, taskId)
        }

        try {
            val request = Request.Builder()
                .url("$BASE_URL/api/spaces/$spaceId/tasks/$taskId")
                .delete()
                .build()
            client.newCall(request).execute()
        } catch (_: Exception) {}

        Result.success(Unit)
    }

    // ==========================================
    // 2. 매일 루틴 (DailyRoutine) - 약 먹기 특화
    // ==========================================

    fun observeRoutines(spaceId: String): Flow<List<DailyRoutine>> {
        return getRoutineFlow(spaceId).asStateFlow()
    }

    suspend fun addRoutine(
        spaceId: String,
        title: String,
        iconType: String = "PILL",
        targetTime: String = "08:30"
    ): Result<DailyRoutine> = withContext(Dispatchers.IO) {
        val routineId = UUID.randomUUID().toString()
        val routine = DailyRoutine(
            id = routineId,
            spaceId = spaceId,
            title = title.trim(),
            iconType = iconType,
            targetTime = targetTime,
            createdAt = System.currentTimeMillis()
        )

        val flow = getRoutineFlow(spaceId)
        flow.value = flow.value + routine

        try {
            val jsonBody = JSONObject().apply {
                put("id", routineId)
                put("title", title)
                put("icon_type", iconType)
                put("target_time", targetTime)
                put("user_id", myUserId())
            }
            val request = Request.Builder()
                .url("$BASE_URL/api/spaces/$spaceId/routines")
                .post(jsonBody.toString().toRequestBody(JSON))
                .build()
            client.newCall(request).execute()
        } catch (_: Exception) {}

        Result.success(routine)
    }

    suspend fun checkRoutineDone(spaceId: String, routineId: String): Result<String> = withContext(Dispatchers.IO) {
        val todayDate = DateTimeUtils.getTodayDateString()
        val completedTimeKorean = DateTimeUtils.getCurrentKoreanTimeString()
        val myId = myUserId()

        val flow = getRoutineFlow(spaceId)
        flow.value = flow.value.map {
            if (it.id == routineId) {
                it.copy(
                    lastCompletedDate = todayDate,
                    lastCompletedTime = completedTimeKorean
                )
            } else it
        }

        try {
            val jsonBody = JSONObject().apply {
                put("user_id", myId)
            }
            val request = Request.Builder()
                .url("$BASE_URL/api/spaces/$spaceId/routines/$routineId/check")
                .post(jsonBody.toString().toRequestBody(JSON))
                .build()
            client.newCall(request).execute()
        } catch (_: Exception) {}

        Result.success(completedTimeKorean)
    }

    /**
     * 서버에서 루틴 목록 + 사람별 오늘 체크 상태 동기화
     * (상대방이 체크한 기록을 partnerCompleted* 필드로 반영)
     */
    suspend fun syncRoutinesFromServer(spaceId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val myId = myUserId()
            val request = Request.Builder()
                .url("$BASE_URL/api/spaces/$spaceId/routines?user_id=$myId")
                .get()
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext Result.failure(
                IllegalStateException("루틴 동기화 실패 (${response.code})")
            )

            val resJson = JSONObject(response.body?.string() ?: "")
            val routinesArray = resJson.optJSONArray("routines") ?: return@withContext Result.failure(
                IllegalStateException("루틴 응답 형식 오류")
            )

            val flow = getRoutineFlow(spaceId)
            val localList = flow.value

            // 서버 데이터를 기준으로 새 목록 구성 (순서: 서버 순서)
            val synced = mutableListOf<DailyRoutine>()
            for (i in 0 until routinesArray.length()) {
                val r = routinesArray.getJSONObject(i)
                val id = r.optString("id")
                // 로컬에만 존재하던 것(오프라인 등록)은 유지하지 않고 서버 기준으로 교체
                synced.add(
                    DailyRoutine(
                        id = id,
                        spaceId = spaceId,
                        title = r.optString("title"),
                        iconType = r.optString("icon_type", "PILL"),
                        targetTime = r.optString("target_time", "08:30"),
                        lastCompletedDate = r.optString("my_completed_date", ""),
                        lastCompletedTime = r.optString("my_completed_time", ""),
                        partnerCompletedDate = r.optString("partner_completed_date", ""),
                        partnerCompletedTime = r.optString("partner_completed_time", ""),
                        createdAt = r.optLong("created_at", System.currentTimeMillis())
                    )
                )
            }

            // 로컬에만 있고 서버에 없는 루틴(전송 실패분)은 앞에 보존
            val serverIds = synced.map { it.id }.toSet()
            val localsOnly = localList.filter { it.id !in serverIds }
            flow.value = localsOnly + synced

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteRoutine(spaceId: String, routineId: String): Result<Unit> = withContext(Dispatchers.IO) {
        val flow = getRoutineFlow(spaceId)
        flow.value = flow.value.filter { it.id != routineId }

        try {
            val request = Request.Builder()
                .url("$BASE_URL/api/spaces/$spaceId/routines/$routineId")
                .delete()
                .build()
            client.newCall(request).execute()
        } catch (_: Exception) {}

        Result.success(Unit)
    }
}
