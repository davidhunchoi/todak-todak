package com.honey.familyspace.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import com.honey.familyspace.R
import com.honey.familyspace.ui.TaskAlarmActivity

/**
 * 지정된 할 일 알람 시각에 깨어나 화면을 켜고 소리와 진동으로 알려주는 리시버
 */
class TaskAlarmReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_TASK_ALARM = "com.honey.familyspace.ACTION_TASK_ALARM"
        const val EXTRA_TASK_ID = "extra_task_id"
        const val EXTRA_TASK_TITLE = "extra_task_title"
        const val EXTRA_SPACE_ID = "extra_space_id"
        const val CHANNEL_ID = "todak_task_sound_alarm_channel"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_TASK_ALARM) return

        val taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: return
        val taskTitle = intent.getStringExtra(EXTRA_TASK_TITLE) ?: "할 일"
        val spaceId = intent.getStringExtra(EXTRA_SPACE_ID) ?: ""

        // 1. 알람 끄기 화면(TaskAlarmActivity) 즉시 실행
        val alarmIntent = Intent(context, TaskAlarmActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
            putExtra(EXTRA_TASK_ID, taskId)
            putExtra(EXTRA_TASK_TITLE, taskTitle)
            putExtra(EXTRA_SPACE_ID, spaceId)
        }
        try {
            context.startActivity(alarmIntent)
        } catch (_: Exception) {}

        // 2. 잠금화면 및 상단바 헤드업 긴급 알람 알림 발송
        showAlarmNotification(context, taskId, taskTitle, alarmIntent)
    }

    private fun showAlarmNotification(context: Context, taskId: String, taskTitle: String, fullScreenIntent: Intent) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        val alarmSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val audioAttributes = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_ALARM)
                .build()

            val channel = NotificationChannel(
                CHANNEL_ID,
                "할 일 소리 알람",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "설정된 시각에 소리와 진동으로 깨워주는 알람"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 200, 500, 200, 1000)
                setSound(alarmSoundUri, audioAttributes)
            }
            notificationManager.createNotificationChannel(channel)
        }

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(context, taskId.hashCode(), fullScreenIntent, flags)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("⏰ '$taskTitle' 챙길 시간이에요!")
            .setContentText("터치하여 알람을 끄거나 확인하세요 🌸")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setSound(alarmSoundUri)
            .setVibrate(longArrayOf(0, 500, 200, 500, 200, 1000))
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setContentIntent(fullScreenPendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(taskId.hashCode(), notification)
    }
}
