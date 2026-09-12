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
class TaskRepository {

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

    private fun getTaskFlow(spaceId: String): MutableStateFlow<List<Task>> {
        return tasksMap.getOrPut(spaceId) {
            MutableStateFlow(
                listOf(
                    Task(
                        id = "sample-task-1",
                        spaceId = spaceId,
                        title = "세탁소에서 옷 찾아오기",
                        dueDate = DateTimeUtils.getTodayDateString(),
                        isCompleted = false
                    ),
                    Task(
                        id = "sample-task-2",
                        spaceId = spaceId,
                        title = "주말 마트 장보기 (우유, 사과)",
                        dueDate = DateTimeUtils.getTomorrowDateString(),
                        isCompleted = false
                    )
                )
            )
        }
    }

    private fun getRoutineFlow(spaceId: String): MutableStateFlow<List<DailyRoutine>> {
        return routinesMap.getOrPut(spaceId) {
            MutableStateFlow(
                listOf(
                    DailyRoutine(
                        id = "routine-pill-1",
                        spaceId = spaceId,
                        title = "아침 혈압약 & 영양제 챙겨먹기",
                        iconType = "PILL",
                        targetTime = "08:30",
                        lastCompletedDate = "",
                        lastCompletedTime = ""
                    )
                )
            )
        }
    }

    // ==========================================
    // 1. 할 일 (Task) 실시간 관리
    // ==========================================

    fun observeTasks(spaceId: String): Flow<List<Task>> {
        return getTaskFlow(spaceId).asStateFlow()
    }

    suspend fun addTask(spaceId: String, title: String, dueDate: String): Result<Task> = withContext(Dispatchers.IO) {
        val taskId = UUID.randomUUID().toString()
        val newTask = Task(
            id = taskId,
            spaceId = spaceId,
            title = title.trim(),
            dueDate = dueDate.trim(),
            isCompleted = false,
            createdAt = System.currentTimeMillis()
        )

        // 로컬 즉각 반영 (0.1초 반응)
        val flow = getTaskFlow(spaceId)
        flow.value = listOf(newTask) + flow.value

        // 백그라운드 서버 전송
        try {
            val jsonBody = JSONObject().apply {
                put("title", title)
                put("due_date", dueDate)
                put("user_id", "my-uid")
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
        flow.value = flow.value.map {
            if (it.id == taskId) it.copy(isCompleted = newStatus, completedAt = if (newStatus) System.currentTimeMillis() else null)
            else it
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

    suspend fun deleteTask(spaceId: String, taskId: String): Result<Unit> = withContext(Dispatchers.IO) {
        val flow = getTaskFlow(spaceId)
        flow.value = flow.value.filter { it.id != taskId }

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
                put("title", title)
                put("icon_type", iconType)
                put("target_time", targetTime)
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
            val request = Request.Builder()
                .url("$BASE_URL/api/spaces/$spaceId/routines/$routineId/check")
                .post("{}".toRequestBody(JSON))
                .build()
            client.newCall(request).execute()
        } catch (_: Exception) {}

        Result.success(completedTimeKorean)
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
