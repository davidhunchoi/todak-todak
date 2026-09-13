package com.honey.familyspace.notification

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock

/**
 * 사용자 설정 주기(1시간, 2시간 등)마다 미완료 할 일을 일깨워주는 잔소리 알림 스케줄러
 */
object ReminderScheduler {

    private const val REQUEST_CODE_REMINDER = 9001

    /**
     * 다음 리마인더 알람 예약
     * @param intervalHours 0이면 취소, 1 이상이면 해당 시간(시간 단위) 후 실행
     */
    fun scheduleReminder(context: Context, intervalHours: Int) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(context, ReminderAlarmReceiver::class.java).apply {
            action = ReminderAlarmReceiver.ACTION_TRIGGER_REMINDER
        }

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        val pendingIntent = PendingIntent.getBroadcast(context, REQUEST_CODE_REMINDER, intent, flags)

        if (intervalHours <= 0) {
            // 알림 끔
            alarmManager.cancel(pendingIntent)
            return
        }

        val triggerAtMillis = SystemClock.elapsedRealtime() + (intervalHours * 60 * 60 * 1000L)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
            } else {
                alarmManager.set(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
            }
        } catch (e: Exception) {}
    }

    /**
     * 알람 취소
     */
    fun cancelReminder(context: Context) {
        scheduleReminder(context, 0)
    }
}
