package com.honey.familyspace.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import com.honey.familyspace.ui.WalkieActivity

/**
 * 무전기(PTT) 수신 알림 관리자 (진동 & 소리 & 헤즈업 팝업)
 */
object WalkieNotificationManager {

    const val CHANNEL_ID = "channel_walkie_incoming"
    const val NOTIFICATION_ID = 8888

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val audioAttributes = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT)
                .build()

            val channel = NotificationChannel(
                CHANNEL_ID,
                "무전기 수신 알림",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "상대방이 무전을 보냈을 때 진동 및 소리로 즉시 알려줍니다."
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 300, 150, 300)
                setSound(soundUri, audioAttributes)
            }

            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    /**
     * 새 무전 도착 알림 표시 (진동 + 헤즈업 팝업)
     */
    fun showIncomingWalkieNotification(
        context: Context,
        spaceId: String,
        spaceTitle: String,
        senderNickname: String
    ) {
        createNotificationChannel(context)

        // 즉각 햅틱 진동
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 300, 150, 300), -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(longArrayOf(0, 300, 150, 300), -1)
            }
        } catch (_: Exception) {}

        val intent = Intent(context, WalkieActivity::class.java).apply {
            putExtra(WalkieActivity.EXTRA_SPACE_ID, spaceId)
            putExtra(WalkieActivity.EXTRA_SPACE_TITLE, spaceTitle)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("📻 [${senderNickname.ifBlank { "상대방" }}]님의 새 무전!")
            .setContentText("터치하면 바로 무전을 들을 수 있어요.")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setSound(soundUri)
            .setVibrate(longArrayOf(0, 300, 150, 300))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    fun clearNotification(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(NOTIFICATION_ID)
    }
}
