package com.honey.familyspace.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.honey.familyspace.data.DataStoreManager
import com.honey.familyspace.data.SpaceRepository
import com.honey.familyspace.data.WalkieRepository
import com.honey.familyspace.notification.WalkieNotificationManager
import com.honey.familyspace.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 화면이 꺼져 있어도 무전을 수신해 진동/소리로 알려주는 저전력 상시대기 포그라운드 서비스
 */
class WalkieStandbyService : Service {

    constructor() : super()

    companion object {
        private const val STANDBY_CHANNEL_ID = "channel_walkie_standby"
        private const val STANDBY_NOTIF_ID = 8887

        fun start(context: Context) {
            val intent = Intent(context, WalkieStandbyService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, WalkieStandbyService::class.java)
            context.stopService(intent)
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private var pollJob: Job? = null
    private var lastPollMs = System.currentTimeMillis() - 30_000L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createStandbyChannel()
        startForeground(STANDBY_NOTIF_ID, buildStandbyNotification())
        startPolling()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (pollJob == null || pollJob?.isActive != true) {
            startPolling()
        }
        return START_STICKY
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = serviceScope.launch {
            val dataStore = DataStoreManager(applicationContext)
            val walkieRepo = WalkieRepository(dataStore)
            val spaceRepo = SpaceRepository(dataStore)

            while (isActive) {
                // 12초 주기 저전력 확인 (배터리 소모 극소화)
                delay(12_000)

                val standbyEnabled = dataStore.walkieStandbyFlow.firstOrNull() ?: true
                if (!standbyEnabled) continue

                val activeSpaceId = dataStore.activeSpaceIdFlow.firstOrNull()
                if (activeSpaceId.isNullOrBlank() || activeSpaceId == "ALL") continue

                try {
                    val result = walkieRepo.pollLatest(activeSpaceId, afterMs = lastPollMs)
                    if (result != null) {
                        lastPollMs = result.createdAt + 1
                        val partnerName = dataStore.partnerNicknameFlow.firstOrNull() ?: "상대방"
                        val space = spaceRepo.observeMySpaces().firstOrNull()?.find { it.id == activeSpaceId }
                        val spaceTitle = space?.title ?: "우리 공간"

                        // 화면 깨우기 락 (3초)
                        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
                        val wakeLock = pm?.newWakeLock(
                            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                            "FamilySpace:WalkieWakeLock"
                        )
                        wakeLock?.acquire(3000L)

                        // 진동 및 헤즈업 알림 발동!
                        WalkieNotificationManager.showIncomingWalkieNotification(
                            applicationContext,
                            spaceId = activeSpaceId,
                            spaceTitle = spaceTitle,
                            senderNickname = partnerName
                        )
                    }
                } catch (_: Exception) {}
            }
        }
    }

    private fun createStandbyChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                STANDBY_CHANNEL_ID,
                "무전기 상시대기 상태",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "무전기 수신 대기 상태를 유지합니다."
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildStandbyNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, STANDBY_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("📻 토닥 무전기 대기 중")
            .setContentText("상대방이 무전을 보내면 화면이 꺼져 있어도 진동으로 알려드려요.")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        pollJob?.cancel()
    }
}
