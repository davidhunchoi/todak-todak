package com.honey.familyspace.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.honey.familyspace.data.DataStoreManager
import com.honey.familyspace.data.SpaceRepository
import com.honey.familyspace.data.TaskRepository
import com.honey.familyspace.notification.OngoingNotificationManager

/**
 * FamilySpace Todo 메인 액티비티
 */
class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        // 권한 승인 후 상단바 알림 즉시 업데이트
        OngoingNotificationManager.updateOngoingNotification(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 필수 권한 요청 (음성 인식 및 알림)
        checkAndRequestPermissions()

        val dataStore = DataStoreManager(applicationContext)
        val spaceRepo = SpaceRepository(dataStore)
        val taskRepo = TaskRepository(dataStore)

        // 상단바 고정 알림 초기화
        OngoingNotificationManager.updateOngoingNotification(this)

        setContent {
            MainScreen(
                spaceRepo = spaceRepo,
                taskRepo = taskRepo,
                dataStore = dataStore
            )
        }
    }

    private fun checkAndRequestPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        // 1. 음성 인식(STT) 마이크 권한
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsToRequest.add(Manifest.permission.RECORD_AUDIO)
        }

        // 2. 안드로이드 13+ 알림 권한
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    override fun onResume() {
        super.onResume()
        // 앱으로 돌아올 때마다 상단바 알림 갱신
        OngoingNotificationManager.updateOngoingNotification(this)
    }
}
