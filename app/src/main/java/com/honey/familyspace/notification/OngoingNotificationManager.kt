package com.honey.familyspace.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.honey.familyspace.data.DataStoreManager
import com.honey.familyspace.data.TaskRepository
import com.honey.familyspace.receiver.NotificationActionReceiver
import com.honey.familyspace.ui.MainActivity
import com.honey.familyspace.util.DateTimeUtils
import com.honey.familyspace.widget.FamilySpaceWidget
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

/**
 * 상단바 조용한 고정 알림(Ongoing Notification) 관리자
 */
object OngoingNotificationManager {

    private const val CHANNEL_ID = "channel_ongoing_family_todo"
    private const val NOTIFICATION_ID = 7777

    /**
     * 알림 채널 생성 (무음, 진동 없음, 방해 금지)
     */
    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "상단바 오늘 할 일",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "소리 없이 상단바에 오늘 챙길 일을 조용히 띄워줍니다."
                enableVibration(false)
                enableLights(false)
                setSound(null, null)
            }

            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    /**
     * 상단바 고정 알림 내용 갱신
     */
    fun updateOngoingNotification(context: Context) {
        createNotificationChannel(context)

        CoroutineScope(Dispatchers.IO).launch {
            val dataStore = DataStoreManager(context)
            val activeSpaceId = dataStore.activeSpaceIdFlow.firstOrNull() ?: return@launch

            val taskRepo = TaskRepository()
            val todayString = DateTimeUtils.getTodayDateString()

            // 오늘 마감 또는 기한 지난 미완료 할 일 조회
            val tasks = taskRepo.observeTasks(activeSpaceId).firstOrNull() ?: emptyList()
            val uncompletedTasks = tasks.filter { !it.isCompleted && (it.dueDate.isBlank() || it.dueDate <= todayString) }

            // 루틴 조회
            val routines = taskRepo.observeRoutines(activeSpaceId).firstOrNull() ?: emptyList()
            val uncompletedRoutine = routines.firstOrNull { !it.isCompletedToday(todayString) }
            val completedRoutine = routines.firstOrNull { it.isCompletedToday(todayString) }

            // 알림 텍스트 구성
            val title = if (uncompletedTasks.isEmpty()) {
                "오늘 할 일 끝! 완벽해요 💖"
            } else {
                "📌 오늘 할 일 ${uncompletedTasks.size}개 남음"
            }

            val routineText = when {
                completedRoutine != null -> "💊 ${completedRoutine.title}: ${completedRoutine.lastCompletedTime} 복용 완료 ✅"
                uncompletedRoutine != null -> "💊 ${uncompletedRoutine.title}: 아직 안 드셨어요!"
                else -> "오늘 등록된 복용 루틴이 없습니다."
            }

            val taskNames = if (uncompletedTasks.isNotEmpty()) {
                uncompletedTasks.take(2).joinToString(", ") { it.title }
            } else {
                "모든 일정을 마쳤습니다."
            }

            val contentText = "$routineText\n• $taskNames"

            // 알림 탭 시 메인 앱 실행
            val openAppIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val contentPendingIntent = PendingIntent.getActivity(
                context,
                0,
                openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.checkbox_on_background)
                .setContentTitle(title)
                .setContentText(routineText)
                .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
                .setContentIntent(contentPendingIntent)
                .setOngoing(true) // 스와이프로 꺼지지 않는 고정 알림
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOnlyAlertOnce(true)

            // 아직 안 먹은 약 루틴이 있다면 알림창에 [약 먹었어요!] 액션 버튼 추가
            if (uncompletedRoutine != null) {
                val actionIntent = Intent(context, NotificationActionReceiver::class.java).apply {
                    action = NotificationActionReceiver.ACTION_COMPLETE_ROUTINE
                    putExtra(NotificationActionReceiver.EXTRA_SPACE_ID, activeSpaceId)
                    putExtra(NotificationActionReceiver.EXTRA_ROUTINE_ID, uncompletedRoutine.id)
                }
                val actionPendingIntent = PendingIntent.getBroadcast(
                    context,
                    101,
                    actionIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                builder.addAction(
                    android.R.drawable.checkbox_on_background,
                    "💊 약 먹었어요!",
                    actionPendingIntent
                )
            }

            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, builder.build())

            // 홈 화면 대형 위젯 실시간 자동 갱신
            try {
                FamilySpaceWidget().updateAll(context)
            } catch (_: Exception) {}
        }
    }
}
