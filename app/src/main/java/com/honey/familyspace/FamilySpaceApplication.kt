package com.honey.familyspace

import android.app.Application
import com.honey.familyspace.notification.OngoingNotificationManager

/**
 * 애플리케이션 진입점 (알림 채널 초기화)
 */
class FamilySpaceApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // 상단바 고정 알림 채널 초기화
        OngoingNotificationManager.createNotificationChannel(this)
    }
}
