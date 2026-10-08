package com.honey.familyspace.ui

import android.Manifest
import android.app.Activity
import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.RecognizerIntent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.honey.familyspace.data.DataStoreManager
import com.honey.familyspace.data.NavigatorSyncClient
import com.honey.familyspace.data.SpaceRepository
import com.honey.familyspace.data.TaskRepository
import com.honey.familyspace.model.ThemeColor
import com.honey.familyspace.util.DateTimeUtils
import com.honey.familyspace.util.VoiceDateParser
import com.honey.familyspace.util.VoiceParseResult
import com.honey.familyspace.util.VoiceTargetType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 전원/측면 버튼 2번 누름, 메인 마이크 버튼 및 위젯에서 실행되는
 * 100% 구글 공식 음성 인식(Google Speech Dialog) 직통 액티비티
 */
class QuickVoiceActivity : ComponentActivity() {

    companion object {
        const val EXTRA_SPACE_ID = "extra_space_id"
    }

    private lateinit var dataStoreManager: DataStoreManager
    private lateinit var taskRepository: TaskRepository
    private lateinit var navigatorSyncClient: NavigatorSyncClient
    private var vibrator: Vibrator? = null

    private var targetSpaceId: String? = null

    private val requestAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startGoogleSpeech()
        } else {
            Toast.makeText(this, "마이크 권한이 필요합니다.", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    // 구글 공식 음성 인식 다이얼로그 런처
    private val googleSpeechLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val matches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val best = VoiceInputManager.pickBestResult(matches)
            if (best.isNotBlank()) {
                handleRecognizedSpeech(best)
            } else {
                Toast.makeText(this, "인식된 내용이 없습니다.", Toast.LENGTH_SHORT).show()
                finish()
            }
        } else {
            // 사용자 취소 또는 뒤로가기
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. 잠금화면 위에서 화면 켜기
        turnScreenOnAndUnlock()

        // 2. 진동기 초기화
        initVibrator()

        // 3. 의존성 초기화
        dataStoreManager = DataStoreManager(this)
        taskRepository = TaskRepository(dataStoreManager)
        navigatorSyncClient = NavigatorSyncClient(dataStoreManager)

        targetSpaceId = intent.getStringExtra(EXTRA_SPACE_ID)?.takeIf { it.isNotBlank() }

        // 4. 권한 체크 후 구글 공식 음성 즉시 실행
        if (checkAudioPermission()) {
            startGoogleSpeech()
        } else {
            requestAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun checkAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun startGoogleSpeech() {
        vibrateStart()
        try {
            val intent = VoiceInputManager.createGoogleSpeechIntent("할 일을 말씀해 주세요")
            googleSpeechLauncher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "기기에 구글 음성 인식기가 없습니다.", Toast.LENGTH_SHORT).show()
            finish()
        } catch (e: Exception) {
            Toast.makeText(this, "구글 음성 실행 실패: ${e.message}", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun handleRecognizedSpeech(rawText: String) {
        lifecycleScope.launch {
            try {
                val parsed = VoiceDateParser.parse(rawText)
                if (parsed.content.isBlank()) {
                    Toast.makeText(this@QuickVoiceActivity, "내용이 인식되지 않았습니다.", Toast.LENGTH_SHORT).show()
                    vibrateError()
                    finish()
                    return@launch
                }

                // 저장 대상 방 결정: 전달받은 방 > 활성 방 > 첫 번째 방 > 기본 방 생성
                var spaceId = targetSpaceId ?: dataStoreManager.activeSpaceIdFlow.first()
                if (spaceId.isNullOrBlank()) {
                    val spaceRepo = SpaceRepository(dataStoreManager)
                    val spaces = spaceRepo.observeMySpaces().first()
                    if (spaces.isNotEmpty()) {
                        spaceId = spaces[0].id
                    }
                }
                if (!spaceId.isNullOrBlank()) {
                    dataStoreManager.setActiveSpaceId(spaceId)
                } else {
                    val spaceRepo = SpaceRepository(dataStoreManager)
                    val created = spaceRepo.createSpace("우리 공간", ThemeColor.CORAL)
                    val newSpaceId = created.getOrNull()?.first?.id
                    if (!newSpaceId.isNullOrBlank()) {
                        spaceId = newSpaceId
                        dataStoreManager.setActiveSpaceId(newSpaceId)
                    }
                }

                if (spaceId.isNullOrBlank()) {
                    Toast.makeText(this@QuickVoiceActivity, "저장할 방이 없습니다.", Toast.LENGTH_SHORT).show()
                    vibrateError()
                    finish()
                    return@launch
                }

                val targetDateStr = parsed.targetDate

                // 로컬 및 서버 저장
                val taskId = taskRepository.addTask(
                    spaceId = spaceId,
                    title = parsed.content,
                    dueDate = targetDateStr,
                    alarmTime = null,
                    hasAlarm = false
                )

                // 백그라운드 서버 동기화
                taskRepository.syncTasksFromServer(spaceId)

                // My Navigator 웹 서버 동기화 (파워유저 설정에서 내비 연동이 켜져 있을 때만 전송)
                val isNavEnabled = dataStoreManager.navigatorEnabledFlow.first()
                var navMsg = ""
                if (isNavEnabled) {
                    val syncSuccess = navigatorSyncClient.syncParsedVoiceData(parsed)
                    navMsg = if (syncSuccess) " · 🧭내비 연동됨" else ""
                }

                vibrateSuccess()
                val dateMsg = if (targetDateStr != null) " [$targetDateStr]" else ""
                Toast.makeText(
                    this@QuickVoiceActivity,
                    "✅ 등록 완료: ${parsed.content}$dateMsg$navMsg",
                    Toast.LENGTH_LONG
                ).show()

                finish()
            } catch (e: Exception) {
                Toast.makeText(this@QuickVoiceActivity, "등록 오류: ${e.message}", Toast.LENGTH_SHORT).show()
                vibrateError()
                finish()
            }
        }
    }

    private fun turnScreenOnAndUnlock() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            keyguardManager?.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
    }

    private fun initVibrator() {
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vibratorManager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    private fun vibrateStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(40)
        }
    }

    private fun vibrateSuccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 50, 70, 60), -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(50)
        }
    }

    private fun vibrateError() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 80, 50, 80), -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(80)
        }
    }
}
