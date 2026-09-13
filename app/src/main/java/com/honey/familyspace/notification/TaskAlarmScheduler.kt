package com.honey.familyspace.notification

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.honey.familyspace.model.Task
import com.honey.familyspace.util.DateTimeUtils
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * 특정 할 일의 날짜/시각에 소리와 진동으로 모닝콜처럼 깨워주는 정확한 소리 알람 스케줄러
 */
object TaskAlarmScheduler {

    private const val TAG = "TaskAlarmScheduler"

    /**
     * 할 일의 지정 시각 소리 알람 예약
     */
    fun scheduleTaskAlarm(context: Context, task: Task) {
        if (!task.hasAlarm || task.alarmTime.isNullOrBlank() || task.isCompleted) {
            cancelTaskAlarm(context, task.id)
            return
        }

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return

        // 날짜 결정: dueDate가 비어있으면 오늘 날짜 사용
        val targetDate = if (task.dueDate.isNotBlank()) task.dueDate else DateTimeUtils.getTodayDateString()
        val targetTime = task.alarmTime // "HH:mm"

        val triggerMillis = calculateTriggerMillis(targetDate, targetTime)
        if (triggerMillis <= System.currentTimeMillis()) {
            Log.d(TAG, "지나간 시각의 알람은 예약하지 않습니다: $targetDate $targetTime")
            return
        }

        val intent = Intent(context, TaskAlarmReceiver::class.java).apply {
            action = TaskAlarmReceiver.ACTION_TASK_ALARM
            putExtra(TaskAlarmReceiver.EXTRA_TASK_ID, task.id)
            putExtra(TaskAlarmReceiver.EXTRA_TASK_TITLE, task.title)
            putExtra(TaskAlarmReceiver.EXTRA_SPACE_ID, task.spaceId)
        }

        val requestCode = task.id.hashCode()
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        val pendingIntent = PendingIntent.getBroadcast(context, requestCode, intent, flags)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMillis, pendingIntent)
                } else {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMillis, pendingIntent)
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMillis, pendingIntent)
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerMillis, pendingIntent)
            }
            Log.d(TAG, "할 일 알람 예약 완료: '${task.title}' at $targetDate $targetTime ($triggerMillis)")
        } catch (e: Exception) {
            Log.e(TAG, "알람 예약 실패: ${e.message}", e)
        }
    }

    /**
     * 특정 할 일 알람 취소
     */
    fun cancelTaskAlarm(context: Context, taskId: String) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(context, TaskAlarmReceiver::class.java).apply {
            action = TaskAlarmReceiver.ACTION_TASK_ALARM
        }
        val requestCode = taskId.hashCode()
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getBroadcast(context, requestCode, intent, flags)
        alarmManager.cancel(pendingIntent)
        Log.d(TAG, "할 일 알람 취소: $taskId")
    }

    private fun calculateTriggerMillis(dateStr: String, timeStr: String): Long {
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA)
            val date = sdf.parse("$dateStr $timeStr")
            date?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }
}
