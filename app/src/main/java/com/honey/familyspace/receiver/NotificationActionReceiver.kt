package com.honey.familyspace.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.honey.familyspace.data.TaskRepository
import com.honey.familyspace.notification.OngoingNotificationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 상단바 고정 알림창 내 액션 버튼(약 복용 완료 등) 클릭 처리 리시버
 * (앱 화면을 띄우지 않고 백그라운드에서 즉시 완료 처리)
 */
class NotificationActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_COMPLETE_ROUTINE = "com.honey.familyspace.ACTION_COMPLETE_ROUTINE"
        const val EXTRA_SPACE_ID = "extra_space_id"
        const val EXTRA_ROUTINE_ID = "extra_routine_id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_COMPLETE_ROUTINE) {
            val spaceId = intent.getStringExtra(EXTRA_SPACE_ID) ?: return
            val routineId = intent.getStringExtra(EXTRA_ROUTINE_ID) ?: return

            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val repository = TaskRepository()
                    repository.checkRoutineDone(spaceId, routineId)

                    // 알림 즉시 갱신
                    OngoingNotificationManager.updateOngoingNotification(context)
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}
