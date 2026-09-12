package com.honey.familyspace.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.honey.familyspace.notification.OngoingNotificationManager

/**
 * 기기 재부팅 완료(`BOOT_COMPLETED`) 시 상단바 고정 알림 복구 리시버
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            OngoingNotificationManager.updateOngoingNotification(context)

            val dataStore = com.honey.familyspace.data.DataStoreManager(context)
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                try {
                    val interval = kotlinx.coroutines.flow.first(dataStore.reminderIntervalHoursFlow)
                    if (interval > 0) {
                        com.honey.familyspace.notification.ReminderScheduler.scheduleReminder(context, interval)
                    }
                } catch (_: Exception) {}
            }
        }
    }
}
