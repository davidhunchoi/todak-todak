package com.honey.familyspace.data

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.honey.familyspace.model.DailyRoutine
import com.honey.familyspace.model.Task
import com.honey.familyspace.util.DateTimeUtils
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import java.util.UUID

/**
 * 할 일(`Task`) 및 매일 루틴(`DailyRoutine`) 실시간 저장소
 */
class TaskRepository(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance()
) {

    // ==========================================
    // 1. 할 일 (Task) 실시간 관리
    // ==========================================

    /**
     * 특정 스페이스의 할 일 목록 실시간 구독
     * (마감일 및 생성일 순 정렬)
     */
    fun observeTasks(spaceId: String): Flow<List<Task>> = callbackFlow {
        if (spaceId.isBlank()) {
            trySend(emptyList())
            close()
            return@callbackFlow
        }

        val query = firestore.collection("spaces")
            .document(spaceId)
            .collection("tasks")
            .orderBy("isCompleted")
            .orderBy("dueDate")
            .orderBy("createdAt")

        val listener = query.addSnapshotListener { snapshot, error ->
            if (error != null) {
                close(error)
                return@addSnapshotListener
            }
            val tasks = snapshot?.documents?.mapNotNull { it.toObject(Task::class.java) } ?: emptyList()
            trySend(tasks)
        }

        awaitClose {
            listener.remove()
        }
    }

    /**
     * 할 일 추가
     */
    suspend fun addTask(spaceId: String, title: String, dueDate: String): Result<Task> {
        return try {
            val myUid = auth.currentUser?.uid ?: ""
            val taskId = UUID.randomUUID().toString()

            val task = Task(
                id = taskId,
                spaceId = spaceId,
                title = title.trim(),
                dueDate = dueDate.trim(),
                isCompleted = false,
                createdBy = myUid,
                createdAt = System.currentTimeMillis()
            )

            firestore.collection("spaces")
                .document(spaceId)
                .collection("tasks")
                .document(taskId)
                .set(task)
                .await()

            Result.success(task)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 할 일 완료 상태 토글 (완료 체크 / 체크 해제)
     */
    suspend fun toggleTask(spaceId: String, taskId: String, currentStatus: Boolean): Result<Unit> {
        return try {
            val newStatus = !currentStatus
            val completedAt = if (newStatus) System.currentTimeMillis() else null

            firestore.collection("spaces")
                .document(spaceId)
                .collection("tasks")
                .document(taskId)
                .update(
                    mapOf(
                        "isCompleted" to newStatus,
                        "completedAt" to completedAt
                    )
                )
                .await()

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 할 일 삭제
     */
    suspend fun deleteTask(spaceId: String, taskId: String): Result<Unit> {
        return try {
            firestore.collection("spaces")
                .document(spaceId)
                .collection("tasks")
                .document(taskId)
                .delete()
                .await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ==========================================
    // 2. 매일 루틴 (DailyRoutine) - 약 먹기 특화
    // ==========================================

    /**
     * 특정 스페이스의 매일 루틴 목록 실시간 구독
     */
    fun observeRoutines(spaceId: String): Flow<List<DailyRoutine>> = callbackFlow {
        if (spaceId.isBlank()) {
            trySend(emptyList())
            close()
            return@callbackFlow
        }

        val query = firestore.collection("spaces")
            .document(spaceId)
            .collection("routines")
            .orderBy("createdAt")

        val listener = query.addSnapshotListener { snapshot, error ->
            if (error != null) {
                close(error)
                return@addSnapshotListener
            }
            val routines = snapshot?.documents?.mapNotNull { it.toObject(DailyRoutine::class.java) } ?: emptyList()
            trySend(routines)
        }

        awaitClose {
            listener.remove()
        }
    }

    /**
     * 새 루틴 추가 (예: "아침 혈압약 먹기", "유산균 복용")
     */
    suspend fun addRoutine(
        spaceId: String,
        title: String,
        iconType: String = "PILL",
        targetTime: String = "08:30"
    ): Result<DailyRoutine> {
        return try {
            val routineId = UUID.randomUUID().toString()
            val routine = DailyRoutine(
                id = routineId,
                spaceId = spaceId,
                title = title.trim(),
                iconType = iconType,
                targetTime = targetTime,
                createdAt = System.currentTimeMillis()
            )

            firestore.collection("spaces")
                .document(spaceId)
                .collection("routines")
                .document(routineId)
                .set(routine)
                .await()

            Result.success(routine)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 원터치 루틴 완료 체크 (약 먹었어요 버튼 탭!)
     *
     * 핵심 기능: 현재 한국어 시각("오전 08:25")과 오늘 날짜를 실시간으로 기록하여
     * 아내 본인과 남편 폰 모두에 "오늘 오전 08:25 복용 완료"로 영구 노출됨
     */
    suspend fun checkRoutineDone(spaceId: String, routineId: String): Result<String> {
        return try {
            val todayDate = DateTimeUtils.getTodayDateString()
            val completedTimeKorean = DateTimeUtils.getCurrentKoreanTimeString()

            firestore.collection("spaces")
                .document(spaceId)
                .collection("routines")
                .document(routineId)
                .update(
                    mapOf(
                        "lastCompletedDate" to todayDate,
                        "lastCompletedTime" to completedTimeKorean
                    )
                )
                .await()

            Result.success(completedTimeKorean)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 루틴 삭제
     */
    suspend fun deleteRoutine(spaceId: String, routineId: String): Result<Unit> {
        return try {
            firestore.collection("spaces")
                .document(spaceId)
                .collection("routines")
                .document(routineId)
                .delete()
                .await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
