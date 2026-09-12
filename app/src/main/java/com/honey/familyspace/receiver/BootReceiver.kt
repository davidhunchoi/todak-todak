package com.honey.familyspace.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.honey.familyspace.data.DataStoreManager
import com.honey.familyspace.notification.OngoingNotificationManager
import com.honey.familyspace.notification.ReminderScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 기기 재부팅 완료(`BOOT_COMPLETED`) 시 상단바 고정 알림 및 리마인더 스케줄 복구 리시버
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            OngoingNotificationManager.updateOngoingNotification(context)

            val dataStore = DataStoreManager(context)
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val interval = dataStore.reminderIntervalHoursFlow.first()
                    if (interval > 0) {
                        ReminderScheduler.scheduleReminder(context, interval)
                    }
                } catch (_: Exception) {}
            }
        }
    }
}
