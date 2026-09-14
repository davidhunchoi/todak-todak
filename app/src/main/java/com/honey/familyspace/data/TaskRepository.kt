package com.honey.familyspace.data

import android.content.Context
import com.honey.familyspace.model.DailyRoutine
import com.honey.familyspace.model.Task
import com.honey.familyspace.util.DateTimeUtils
import androidx.glance.appwidget.updateAll
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
            val cached = dataStore?.context?.let { loadTasksFromCache(it, spaceId) } ?: emptyList()
            MutableStateFlow(cached)
        }
    }

    private fun getRoutineFlow(spaceId: String): MutableStateFlow<List<DailyRoutine>> {
        return routinesMap.getOrPut(spaceId) {
            val cached = dataStore?.context?.let { loadRoutinesFromCache(it, spaceId) } ?: emptyList()
            MutableStateFlow(cached)
        }
    }

    /**
     * 할 일/루틴 데이터 변경 시 홈 화면 위젯 및 상단바 고정 알림 실시간 동기화
     */
    private suspend fun notifyWidgetAndNotification() {
        dataStore?.context?.let { ctx ->
            try {
                com.honey.familyspace.widget.FamilySpaceWidget().updateAll(ctx)
                com.honey.familyspace.notification.OngoingNotificationManager.updateOngoingNotification(ctx)
            } catch (e: Exception) {}
        }
    }

    // ==========================================
    // 1. 할 일 (Task) 실시간 관리
    // ==========================================

    fun observeTasks(spaceId: String): Flow<List<Task>> {
        return getTaskFlow(spaceId).asStateFlow()
    }

    /**
     * 참여 중인 모든 방의 할 일을 한 번에 모아보기 위한 Flow
     */
    fun observeAllTasks(spaceIds: List<String>): Flow<List<Task>> {
        if (spaceIds.isEmpty()) return kotlinx.coroutines.flow.flowOf(emptyList())
        val flows = spaceIds.map { getTaskFlow(it) }
        return kotlinx.coroutines.flow.combine(flows) { arrays ->
            arrays.flatMap { it }.sortedByDescending { it.createdAt }
        }
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
        val updatedList = listOf(newTask) + flow.value
        flow.value = updatedList
        dataStore?.context?.let { saveTasksToCache(it, spaceId, updatedList) }
        notifyWidgetAndNotification()

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
        } catch (e: Exception) {}

        Result.success(newTask)
    }

    suspend fun toggleTask(spaceId: String, taskId: String, currentStatus: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        val newStatus = !currentStatus
        val flow = getTaskFlow(spaceId)
        var toggledTask: Task? = null
        val updatedList = flow.value.map {
            if (it.id == taskId) {
                val updated = it.copy(isCompleted = newStatus, completedAt = if (newStatus) System.currentTimeMillis() else null)
                toggledTask = updated
                updated
            } else it
        }
        flow.value = updatedList
        dataStore?.context?.let { saveTasksToCache(it, spaceId, updatedList) }
        notifyWidgetAndNotification()

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
        } catch (e: Exception) {}

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
        val updatedList = flow.value.map {
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
        flow.value = updatedList
        dataStore?.context?.let { saveTasksToCache(it, spaceId, updatedList) }
        notifyWidgetAndNotification()

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
        } catch (e: Exception) {}

        Result.success(Unit)
    }

    suspend fun deleteTask(spaceId: String, taskId: String): Result<Unit> = withContext(Dispatchers.IO) {
        val flow = getTaskFlow(spaceId)
        val updatedList = flow.value.filter { it.id != taskId }
        flow.value = updatedList
        dataStore?.context?.let { saveTasksToCache(it, spaceId, updatedList) }
        notifyWidgetAndNotification()

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
        } catch (e: Exception) {}

        Result.success(Unit)
    }

    /**
     * 서버에서 최신 할 일 목록을 가져와 로컬 StateFlow 및 알람, 위젯 즉시 갱신
     * (아내나 자녀가 등록/체크한 내역이 3초 내에 내 폰에 실시간 반영되는 핵심 함수)
     */
    suspend fun syncTasksFromServer(spaceId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$BASE_URL/api/spaces/$spaceId/tasks")
                .get()
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(IllegalStateException("할 일 동기화 실패 (${response.code})"))
            }

            val resJson = JSONObject(response.body?.string() ?: "")
            val tasksArray = resJson.optJSONArray("tasks") ?: return@withContext Result.failure(
                IllegalStateException("할 일 응답 형식 오류")
            )

            val flow = getTaskFlow(spaceId)
            val syncedList = mutableListOf<Task>()

            for (i in 0 until tasksArray.length()) {
                val t = tasksArray.getJSONObject(i)
                val taskId = t.optString("id")
                val title = t.optString("title")
                val dueDate = t.optString("due_date", "")
                val isCompleted = t.optBoolean("is_completed", false)
                val alarmTime = t.optString("alarm_time", "").takeIf { it.isNotBlank() }
                val hasAlarm = t.optBoolean("has_alarm", false)

                val task = Task(
                    id = taskId,
                    spaceId = spaceId,
                    title = title,
                    dueDate = dueDate,
                    isCompleted = isCompleted,
                    createdAt = System.currentTimeMillis(),
                    alarmTime = alarmTime,
                    hasAlarm = hasAlarm
                )
                syncedList.add(task)

                // 알람 스케줄러 동기화 (미완료 알람 항목 자동 예약)
                dataStore?.context?.let { ctx ->
                    if (hasAlarm && !isCompleted && !alarmTime.isNullOrBlank()) {
                        com.honey.familyspace.notification.TaskAlarmScheduler.scheduleTaskAlarm(ctx, task)
                    } else {
                        com.honey.familyspace.notification.TaskAlarmScheduler.cancelTaskAlarm(ctx, taskId)
                    }
                }
            }

            flow.value = syncedList
            dataStore?.context?.let { saveTasksToCache(it, spaceId, syncedList) }

            // 위젯 및 상단바 알림 실시간 갱신
            dataStore?.context?.let { ctx ->
                try {
                    com.honey.familyspace.widget.FamilySpaceWidget().updateAll(ctx)
                    com.honey.familyspace.notification.OngoingNotificationManager.updateOngoingNotification(ctx)
                } catch (e: Exception) {}
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ==========================================
    // 2. 매일 루틴 (DailyRoutine) - 약 먹기 특화
    // ==========================================

    fun observeRoutines(spaceId: String): Flow<List<DailyRoutine>> {
        return getRoutineFlow(spaceId).asStateFlow()
    }

    /**
     * 참여 중인 모든 방의 매일 루틴을 한 번에 모아보기 위한 Flow
     */
    fun observeAllRoutines(spaceIds: List<String>): Flow<List<DailyRoutine>> {
        if (spaceIds.isEmpty()) return kotlinx.coroutines.flow.flowOf(emptyList())
        val flows = spaceIds.map { getRoutineFlow(it) }
        return kotlinx.coroutines.flow.combine(flows) { arrays ->
            arrays.flatMap { it }.sortedByDescending { it.createdAt }
        }
    }

    /**
     * 매일 루틴 제목 수정
     */
    suspend fun updateRoutineTitle(
        spaceId: String,
        routineId: String,
        newTitle: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val flow = getRoutineFlow(spaceId)
        val currentList = flow.value
        val updatedList = currentList.map {
            if (it.id == routineId) it.copy(title = newTitle.trim()) else it
        }
        flow.value = updatedList
        dataStore?.context?.let { saveRoutinesToCache(it, spaceId, updatedList) }
        notifyWidgetAndNotification()
        Result.success(Unit)
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
        val updatedList = flow.value + routine
        flow.value = updatedList
        dataStore?.context?.let { saveRoutinesToCache(it, spaceId, updatedList) }
        notifyWidgetAndNotification()

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
        } catch (e: Exception) {}

        Result.success(routine)
    }

    suspend fun checkRoutineDone(spaceId: String, routineId: String): Result<String> = withContext(Dispatchers.IO) {
        val todayDate = DateTimeUtils.getTodayDateString()
        val completedTimeKorean = DateTimeUtils.getCurrentKoreanTimeString()
        val myId = myUserId()

        val flow = getRoutineFlow(spaceId)
        val updatedList = flow.value.map {
            if (it.id == routineId) {
                it.copy(
                    lastCompletedDate = todayDate,
                    lastCompletedTime = completedTimeKorean
                )
            } else it
        }
        flow.value = updatedList
        dataStore?.context?.let { saveRoutinesToCache(it, spaceId, updatedList) }
        notifyWidgetAndNotification()

        try {
            val jsonBody = JSONObject().apply {
                put("user_id", myId)
            }
            val request = Request.Builder()
                .url("$BASE_URL/api/spaces/$spaceId/routines/$routineId/check")
                .post(jsonBody.toString().toRequestBody(JSON))
                .build()
            client.newCall(request).execute()
        } catch (e: Exception) {}

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
            val mergedRoutines = localsOnly + synced
            flow.value = mergedRoutines
            dataStore?.context?.let { saveRoutinesToCache(it, spaceId, mergedRoutines) }
            notifyWidgetAndNotification()

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteRoutine(spaceId: String, routineId: String): Result<Unit> = withContext(Dispatchers.IO) {
        val flow = getRoutineFlow(spaceId)
        val updatedList = flow.value.filter { it.id != routineId }
        flow.value = updatedList
        dataStore?.context?.let { saveRoutinesToCache(it, spaceId, updatedList) }
        notifyWidgetAndNotification()

        try {
            val request = Request.Builder()
                .url("$BASE_URL/api/spaces/$spaceId/routines/$routineId")
                .delete()
                .build()
            client.newCall(request).execute()
        } catch (e: Exception) {}

        Result.success(Unit)
    }

    private fun saveTasksToCache(context: Context, spaceId: String, list: List<Task>) {
        try {
            val sp = context.getSharedPreferences("todak_cache", Context.MODE_PRIVATE)
            val arr = JSONArray()
            for (t in list) {
                val obj = JSONObject().apply {
                    put("id", t.id)
                    put("spaceId", t.spaceId)
                    put("title", t.title)
                    put("dueDate", t.dueDate)
                    put("isCompleted", t.isCompleted)
                    put("createdAt", t.createdAt)
                    put("alarmTime", t.alarmTime ?: "")
                    put("hasAlarm", t.hasAlarm)
                }
                arr.put(obj)
            }
            sp.edit().putString("tasks_${spaceId}", arr.toString()).apply()
        } catch (e: Exception) {}
    }

    private fun loadTasksFromCache(context: Context, spaceId: String): List<Task> {
        return try {
            val sp = context.getSharedPreferences("todak_cache", Context.MODE_PRIVATE)
            val jsonStr = sp.getString("tasks_${spaceId}", null) ?: return emptyList()
            val arr = JSONArray(jsonStr)
            val list = mutableListOf<Task>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    Task(
                        id = obj.getString("id"),
                        spaceId = obj.optString("spaceId", spaceId),
                        title = obj.getString("title"),
                        dueDate = obj.optString("dueDate", ""),
                        isCompleted = obj.optBoolean("isCompleted", false),
                        createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
                        alarmTime = obj.optString("alarmTime", "").takeIf { it.isNotBlank() },
                        hasAlarm = obj.optBoolean("hasAlarm", false)
                    )
                )
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun saveRoutinesToCache(context: Context, spaceId: String, list: List<DailyRoutine>) {
        try {
            val sp = context.getSharedPreferences("todak_cache", Context.MODE_PRIVATE)
            val arr = JSONArray()
            for (r in list) {
                val obj = JSONObject().apply {
                    put("id", r.id)
                    put("spaceId", r.spaceId)
                    put("title", r.title)
                    put("iconType", r.iconType)
                    put("lastCompletedDate", r.lastCompletedDate)
                    put("lastCompletedTime", r.lastCompletedTime)
                    put("partnerCompletedDate", r.partnerCompletedDate)
                    put("partnerCompletedTime", r.partnerCompletedTime)
                    put("targetTime", r.targetTime)
                    put("createdAt", r.createdAt)
                }
                arr.put(obj)
            }
            sp.edit().putString("routines_${spaceId}", arr.toString()).apply()
        } catch (e: Exception) {}
    }

    private fun loadRoutinesFromCache(context: Context, spaceId: String): List<DailyRoutine> {
        return try {
            val sp = context.getSharedPreferences("todak_cache", Context.MODE_PRIVATE)
            val jsonStr = sp.getString("routines_${spaceId}", null) ?: return emptyList()
            val arr = JSONArray(jsonStr)
            val list = mutableListOf<DailyRoutine>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    DailyRoutine(
                        id = obj.getString("id"),
                        spaceId = obj.optString("spaceId", spaceId),
                        title = obj.getString("title"),
                        iconType = obj.optString("iconType", "PILL"),
                        lastCompletedDate = obj.optString("lastCompletedDate", ""),
                        lastCompletedTime = obj.optString("lastCompletedTime", ""),
                        partnerCompletedDate = obj.optString("partnerCompletedDate", ""),
                        partnerCompletedTime = obj.optString("partnerCompletedTime", ""),
                        targetTime = obj.optString("targetTime", "08:30"),
                        createdAt = obj.optLong("createdAt", System.currentTimeMillis())
                    )
                )
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }
}
