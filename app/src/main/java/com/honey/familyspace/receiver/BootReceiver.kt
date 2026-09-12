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
        }
    }
}
