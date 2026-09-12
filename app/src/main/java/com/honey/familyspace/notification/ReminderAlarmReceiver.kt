package com.honey.familyspace.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.honey.familyspace.R
import com.honey.familyspace.data.DataStoreManager
import com.honey.familyspace.data.SpaceRepository
import com.honey.familyspace.data.TaskRepository
import com.honey.familyspace.ui.MainActivity
import com.honey.familyspace.util.DateTimeUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * 설정된 시간(1시간, 2시간 등)마다 깨어나 미완료 할 일이 남아있는지 확인하고
 * 헤드업 알림을 띄워주는 브로드캐스트 리시버
 */
class ReminderAlarmReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_TRIGGER_REMINDER = "com.honey.familyspace.ACTION_TRIGGER_REMINDER"
        const val CHANNEL_ID = "todak_reminder_channel"
        private const val NOTIFICATION_ID = 8888
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != ACTION_TRIGGER_REMINDER && action != Intent.ACTION_BOOT_COMPLETED) {
            return
        }

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dataStore = DataStoreManager(context)
                val intervalHours = dataStore.reminderIntervalHoursFlow.first()
                if (intervalHours <= 0) {
                    return@launch
                }

                val nightMute = dataStore.reminderNightMuteFlow.first()
                val currentHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
                val isNightTime = currentHour >= 22 || currentHour < 8

                // 야간 수면 보호 모드일 때는 알림을 울리지 않음
                if (!nightMute || !isNightTime) {
                    checkAndShowNotification(context, dataStore)
                }

                // 다음 알람 자동 예약
                ReminderScheduler.scheduleReminder(context, intervalHours)
            } catch (_: Exception) {
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun checkAndShowNotification(context: Context, dataStore: DataStoreManager) {
        val activeSpaceId = dataStore.activeSpaceIdFlow.first()
        val spaceRepo = SpaceRepository(dataStore)
        val taskRepo = TaskRepository(dataStore)
        val todayDate = DateTimeUtils.getTodayDateString()

        // 활성 스페이스가 없으면 첫 번째 스페이스 사용
        val spaceId = activeSpaceId ?: spaceRepo.observeMySpaces().first().firstOrNull()?.id ?: return

        val tasks = taskRepo.observeTasks(spaceId).first()
        val routines = taskRepo.observeRoutines(spaceId).first()

        val uncompletedTasks = tasks.count { !it.isCompleted }
        val uncompletedRoutines = routines.count { !it.isCompletedToday(todayDate) }
        val totalUncompleted = uncompletedTasks + uncompletedRoutines

        // 할 일을 모두 마쳤으면 알림을 띄우지 않고 조용히 패스
        if (totalUncompleted <= 0) return

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        // 알림 채널 생성 (Android 8.0+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "할 일 챙김 리마인더",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "주기적으로 남은 할 일과 매일 루틴을 챙겨주는 알림"
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(channel)
        }

        // 앱 열기 인텐트
        val appIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            1001,
            appIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val title = "💌 똑똑! 오늘 챙길 일 ${totalUncompleted}개가 남아있어요"
        val firstIncompleteTitle = routines.firstOrNull { !it.isCompletedToday(todayDate) }?.title
            ?: tasks.firstOrNull { !it.isCompleted }?.title
            ?: "미완료 할 일"

        val contentText = if (totalUncompleted == 1) {
            "'$firstIncompleteTitle' 확인해 보세요 🌸"
        } else {
            "'$firstIncompleteTitle' 외 ${totalUncompleted - 1}개가 기다려요 🌸"
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$contentText\n터치하면 토닥토닥 앱으로 바로 이동합니다."))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(contentPendingIntent)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }
}
