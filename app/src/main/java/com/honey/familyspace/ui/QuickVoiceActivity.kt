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
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

enum class QuickVoiceState {
    LISTENING,
    PROCESSING,
    SUCCESS,
    ERROR,
    NEED_PERMISSION
}

/**
 * 전원/측면 버튼 2번 누름 및 위젯에서 실행되는 잠금화면 위 초경량 음성 입력 액티비티
 */
class QuickVoiceActivity : ComponentActivity() {

    private lateinit var voiceInputManager: VoiceInputManager
    private lateinit var dataStoreManager: DataStoreManager
    private lateinit var taskRepository: TaskRepository
    private lateinit var navigatorSyncClient: NavigatorSyncClient

    private var vibrator: Vibrator? = null
    private val handler = Handler(Looper.getMainLooper())
    private var autoCloseRunnable: Runnable? = null

    private val hasAudioPermission = mutableStateOf(false)

    private val requestAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasAudioPermission.value = granted
    }

    // 구글 공식 음성 다이얼로그 결과 -> 내장 연속청취와 동일한 저장 파이프로 연결
    private var googleResultHandler: ((String) -> Unit)? = null
    private var googleErrorHandler: ((String) -> Unit)? = null

    private val googleSpeechLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val matches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val best = VoiceInputManager.pickBestResult(matches)
            if (best.isNotBlank()) {
                googleResultHandler?.invoke(best)
            } else {
                googleErrorHandler?.invoke("구글 음성에서 인식된 내용이 없습니다.")
            }
        } else {
            googleErrorHandler?.invoke("구글 음성이 취소됐습니다. 내장 인식을 이용해 주세요.")
        }
        googleResultHandler = null
        googleErrorHandler = null
    }

    private fun launchGoogleSpeech(onResult: (String) -> Unit, onError: (String) -> Unit) {
        if (!checkAudioPermission()) {
            hasAudioPermission.value = false
            onError("마이크 권한이 필요합니다. 허용 버튼을 눌러 주세요.")
            return
        }
        googleResultHandler = onResult
        googleErrorHandler = onError
        try {
            voiceInputManager.cancel()
            vibrateStart()
            val intent = VoiceInputManager.createGoogleSpeechIntent("할 일을 말씀해 주세요")
            googleSpeechLauncher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            googleResultHandler = null
            googleErrorHandler = null
            onError("이 기기에 구글 음성이 없습니다. 내장 인식을 이용해 주세요.")
        } catch (e: Exception) {
            googleResultHandler = null
            googleErrorHandler = null
            onError(e.localizedMessage ?: "구글 음성을 시작할 수 없습니다.")
        }
    }

    private fun checkAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
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
        voiceInputManager = VoiceInputManager(this)

        // 4. 마이크 권한 상태 초기화 (단독 실행 대비)
        hasAudioPermission.value = checkAudioPermission()
        if (!hasAudioPermission.value) {
            requestAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }

        setContent {
            QuickVoiceScreen(
                hasAudioPermission = hasAudioPermission.value,
                onRequestPermission = {
                    requestAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                },
                onStartListening = { onPartial, onResult, onError ->
                    // 권한 없이 SpeechRecognizer를 호출하면 ERROR_INSUFFICIENT_PERMISSIONS만 발생하므로 선차단
                    if (!checkAudioPermission()) {
                        hasAudioPermission.value = false
                        onError("마이크 권한이 필요합니다. 허용 버튼을 눌러 주세요.")
                        return@QuickVoiceScreen
                    }
                    vibrateStart()
                    // 최대 30초 연속 청취: 중간 침묵으로 끊겨도 자동 재시작하며 누적
                    voiceInputManager.startContinuousListening(
                        maxSessionMs = 30000L,
                        onPartialCombined = onPartial,
                        onFinalCombined = onResult,
                        onFatalError = onError
                    )
                },
                onStopListening = {
                    voiceInputManager.stopContinuous()
                },
                onGoogleSpeech = { onResult, onError ->
                    launchGoogleSpeech(onResult, onError)
                },
                onProcessVoiceResult = { rawText, onComplete, onError ->
                    processVoiceInput(rawText, onComplete, onError)
                },
                onDismiss = { finish() }
            )
        }
    }

    override fun onResume() {
        super.onResume()
        // 설정 화면에서 권한 허용 후 복귀 대비
        hasAudioPermission.value = checkAudioPermission()
    }

    private fun processVoiceInput(
        rawText: String,
        onComplete: (VoiceParseResult, String) -> Unit,
        onError: (String) -> Unit
    ) {
        lifecycleScope.launch {
            try {
                val parsed = VoiceDateParser.parse(rawText)
                if (parsed.content.isBlank()) {
                    onError("내용이 인식되지 않았습니다.")
                    vibrateError()
                    scheduleAutoClose(2000)
                    return@launch
                }

                // 활성 스페이스 확인 (없으면 첫 번째 스페이스 사용, 그래도 없으면 자동 생성)
                var spaceId = dataStoreManager.activeSpaceIdFlow.first()
                if (spaceId.isNullOrBlank()) {
                    val spaceRepo = SpaceRepository(dataStoreManager)
                    val spaces = spaceRepo.observeMySpaces().first()
                    if (spaces.isNotEmpty()) {
                        spaceId = spaces[0].id
                        dataStoreManager.setActiveSpaceId(spaceId)
                    }
                }
                if (spaceId.isNullOrBlank()) {
                    // 첫 설치 등 방이 전혀 없는 경우: 가짜 성공 대신 기본 방을 만들어 저장 보장
                    val spaceRepo = SpaceRepository(dataStoreManager)
                    val created = spaceRepo.createSpace("우리 공간", ThemeColor.CORAL)
                    val newSpaceId = created.getOrNull()?.first?.id
                    if (!newSpaceId.isNullOrBlank()) {
                        spaceId = newSpaceId
                        dataStoreManager.setActiveSpaceId(newSpaceId)
                    }
                }
                if (spaceId.isNullOrBlank()) {
                    onError("저장할 공간이 없습니다. 메인 화면에서 방을 먼저 만들어 주세요.")
                    vibrateError()
                    scheduleAutoClose(2500)
                    return@launch
                }

                // 1) 토닥토닥 로컬/서버 저장 (실패해도 로컬 캐시에는 반영됨)
                val addResult = taskRepository.addTask(
                    spaceId = spaceId,
                    title = parsed.content,
                    dueDate = parsed.targetDate
                )
                if (addResult.isFailure) {
                    onError("저장에 실패했습니다. 다시 시도해 주세요.")
                    vibrateError()
                    scheduleAutoClose(2000)
                    return@launch
                }

                // 2) 파워유저 마이 내비게이터 연동 체크
                val isNavEnabled = dataStoreManager.navigatorEnabledFlow.first()
                var syncMessage = ""
                if (isNavEnabled) {
                    val syncSuccess = navigatorSyncClient.syncParsedVoiceData(parsed)
                    syncMessage = when (parsed.targetType) {
                        VoiceTargetType.LIFE_GRAPH -> if (syncSuccess) "📈 인생 그래프 전송 완료" else "📈 인생 그래프 전송 대기"
                        VoiceTargetType.NAVIGATOR_GANTT -> if (syncSuccess) "🧭 내비 Gantt 전송 완료" else "🧭 내비 Gantt 전송 대기"
                        else -> if (syncSuccess) "🧭 내비 동기화 완료" else ""
                    }
                }

                vibrateSuccess()
                onComplete(parsed, syncMessage)
                scheduleAutoClose(1200) // 성공 시 1.2초 후 쾌속 자동 종료
            } catch (e: Exception) {
                onError(e.localizedMessage ?: "저장 중 오류가 발생했습니다.")
                vibrateError()
                scheduleAutoClose(2000)
            }
        }
    }

    private fun scheduleAutoClose(delayMillis: Long) {
        autoCloseRunnable?.let { handler.removeCallbacks(it) }
        autoCloseRunnable = Runnable {
            if (!isFinishing && !isDestroyed) {
                finish()
            }
        }
        handler.postDelayed(autoCloseRunnable!!, delayMillis)
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
            vibrator?.vibrate(longArrayOf(0, 50, 70, 60), -1)
        }
    }

    private fun vibrateError() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(200)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        autoCloseRunnable?.let { handler.removeCallbacks(it) }
        voiceInputManager.destroy()
    }
}

@Composable
fun QuickVoiceScreen(
    hasAudioPermission: Boolean,
    onRequestPermission: () -> Unit,
    onStartListening: (onPartial: (String) -> Unit, onResult: (String) -> Unit, onError: (String) -> Unit) -> Unit,
    onStopListening: () -> Unit,
    onGoogleSpeech: (onResult: (String) -> Unit, onError: (String) -> Unit) -> Unit,
    onProcessVoiceResult: (rawText: String, onComplete: (VoiceParseResult, String) -> Unit, onError: (String) -> Unit) -> Unit,
    onDismiss: () -> Unit
) {
    var state by remember { mutableStateOf(if (hasAudioPermission) QuickVoiceState.LISTENING else QuickVoiceState.NEED_PERMISSION) }
    var recognizedText by remember { mutableStateOf("") }
    var savedInfo by remember { mutableStateOf<Pair<VoiceParseResult, String>?>(null) }
    var errorMessage by remember { mutableStateOf("") }
    var listenStartMs by remember { mutableStateOf(0L) }
    var elapsedSec by remember { mutableStateOf(0) }

    // 펄스 애니메이션
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(700),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )

    LaunchedEffect(hasAudioPermission) {
        if (!hasAudioPermission) {
            state = QuickVoiceState.NEED_PERMISSION
            return@LaunchedEffect
        }
        state = QuickVoiceState.LISTENING
        recognizedText = ""
        errorMessage = ""
        listenStartMs = System.currentTimeMillis()
        elapsedSec = 0
        onStartListening(
            { partial -> recognizedText = partial },
            { finalResult ->
                recognizedText = finalResult
                state = QuickVoiceState.PROCESSING
                onProcessVoiceResult(
                    finalResult,
                    { result, syncMsg ->
                        savedInfo = Pair(result, syncMsg)
                        state = QuickVoiceState.SUCCESS
                    },
                    { err ->
                        errorMessage = err
                        state = QuickVoiceState.ERROR
                    }
                )
            },
            { err ->
                errorMessage = err
                // 권한 문제면 권한 안내 화면으로 유지
                state = if (!hasAudioPermission || err.contains("마이크 권한")) {
                    QuickVoiceState.NEED_PERMISSION
                } else {
                    QuickVoiceState.ERROR
                }
            }
        )
    }

    // 청취 중 경과 타이머 (최대 30초)
    LaunchedEffect(state) {
        if (state == QuickVoiceState.LISTENING) {
            while (state == QuickVoiceState.LISTENING) {
                kotlinx.coroutines.delay(500)
                elapsedSec = ((System.currentTimeMillis() - listenStartMs) / 1000).toInt()
            }
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color.Black.copy(alpha = 0.65f)
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth(0.88f)
                    .padding(16.dp),
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E24)),
                elevation = CardDefaults.cardElevation(defaultElevation = 12.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    when (state) {
                        QuickVoiceState.LISTENING -> {
                            Box(
                                modifier = Modifier
                                    .size(80.dp)
                                    .scale(scale)
                                    .background(Color(0xFFFF5252).copy(alpha = 0.25f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(60.dp)
                                        .background(Color(0xFFFF5252), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(text = "🎙️", fontSize = 28.sp)
                                }
                            }

                            Spacer(modifier = Modifier.height(18.dp))
                            Text(
                                text = "듣고 있어요... ${elapsedSec}초 (최대 30초)",
                                color = Color.White,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = if (recognizedText.isNotBlank()) recognizedText else "\"오늘날짜로 우유 사기 저장해줘\"",
                                color = if (recognizedText.isNotBlank()) Color(0xFFFFD54F) else Color.White.copy(alpha = 0.6f),
                                fontSize = 15.sp,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "숨 고르셔도 돼요. 말이 끝나면 [완료 저장]을 눌러주세요.",
                                color = Color.White.copy(alpha = 0.55f),
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(14.dp))
                            androidx.compose.foundation.layout.Row(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Button(
                                    onClick = {
                                        if (recognizedText.isNotBlank()) {
                                            state = QuickVoiceState.PROCESSING
                                            onStopListening()
                                        }
                                    },
                                    enabled = recognizedText.isNotBlank(),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFF4CAF50),
                                        disabledContainerColor = Color.Gray.copy(alpha = 0.4f)
                                    )
                                ) {
                                    Text("완료 저장", color = Color.White, fontWeight = FontWeight.Bold)
                                }
                                androidx.compose.material3.TextButton(onClick = { onDismiss() }) {
                                    Text("닫기", color = Color.White.copy(alpha = 0.8f))
                                }
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                            Button(
                                onClick = {
                                    onGoogleSpeech(
                                        { googleText ->
                                            recognizedText = googleText
                                            state = QuickVoiceState.PROCESSING
                                            onProcessVoiceResult(
                                                googleText,
                                                { result, syncMsg ->
                                                    savedInfo = Pair(result, syncMsg)
                                                    state = QuickVoiceState.SUCCESS
                                                },
                                                { err ->
                                                    errorMessage = err
                                                    state = QuickVoiceState.ERROR
                                                }
                                            )
                                        },
                                        { err ->
                                            errorMessage = err
                                            state = QuickVoiceState.ERROR
                                        }
                                    )
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4285F4))
                            ) {
                                Text("구글 음성으로 말하기", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                            if (recognizedText.isBlank()) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "말이 인식되면 버튼이 활성화돼요.",
                                    color = Color.White.copy(alpha = 0.5f),
                                    fontSize = 12.sp
                                )
                            }
                        }

                        QuickVoiceState.PROCESSING -> {
                            CircularProgressIndicator(
                                color = Color(0xFFFFD54F),
                                strokeWidth = 3.dp,
                                modifier = Modifier.size(56.dp)
                            )
                            Spacer(modifier = Modifier.height(18.dp))
                            Text(
                                text = "할 일 분석 및 저장 중...",
                                color = Color.White,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        QuickVoiceState.SUCCESS -> {
                            Box(
                                modifier = Modifier
                                    .size(64.dp)
                                    .background(Color(0xFF4CAF50), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(text = "✓", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "저장 완료!",
                                color = Color.White,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold
                            )
                            savedInfo?.let { (parsed, syncMsg) ->
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "${DateTimeUtils.formatKoreanDate(parsed.targetDate)} : ${parsed.content}",
                                    color = Color(0xFFFFE082),
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium,
                                    textAlign = TextAlign.Center
                                )
                                if (syncMsg.isNotBlank()) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = syncMsg,
                                        color = Color(0xFF81C784),
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(14.dp))
                            androidx.compose.material3.TextButton(onClick = { onDismiss() }) {
                                Text("닫기", color = Color.White.copy(alpha = 0.8f))
                            }
                        }

                        QuickVoiceState.ERROR -> {
                            Box(
                                modifier = Modifier
                                    .size(64.dp)
                                    .background(Color(0xFFE53935), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(text = "!", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "인식되지 않았습니다",
                                color = Color.White,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = errorMessage,
                                color = Color.White.copy(alpha = 0.7f),
                                fontSize = 14.sp,
                                textAlign = TextAlign.Center
                            )
                            if (recognizedText.isNotBlank()) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "부분 인식: $recognizedText",
                                    color = Color(0xFFFFD54F).copy(alpha = 0.9f),
                                    fontSize = 13.sp,
                                    textAlign = TextAlign.Center
                                )
                            }
                            Spacer(modifier = Modifier.height(14.dp))
                            Button(
                                onClick = {
                                    // 재시도: 화면을 닫았다 다시 여는 대신 상태만 리셋하고 재시작 유도
                                    errorMessage = ""
                                    recognizedText = ""
                                    listenStartMs = System.currentTimeMillis()
                                    elapsedSec = 0
                                    state = QuickVoiceState.LISTENING
                                    onStartListening(
                                        { partial -> recognizedText = partial },
                                        { finalResult ->
                                            recognizedText = finalResult
                                            state = QuickVoiceState.PROCESSING
                                            onProcessVoiceResult(
                                                finalResult,
                                                { result, syncMsg ->
                                                    savedInfo = Pair(result, syncMsg)
                                                    state = QuickVoiceState.SUCCESS
                                                },
                                                { err ->
                                                    errorMessage = err
                                                    state = QuickVoiceState.ERROR
                                                }
                                            )
                                        },
                                        { err ->
                                            errorMessage = err
                                            state = QuickVoiceState.ERROR
                                        }
                                    )
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5252))
                            ) {
                                Text("다시 말하기", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                            androidx.compose.material3.TextButton(onClick = { onDismiss() }) {
                                Text("닫기", color = Color.White.copy(alpha = 0.8f))
                            }
                        }

                        QuickVoiceState.NEED_PERMISSION -> {
                            Box(
                                modifier = Modifier
                                    .size(64.dp)
                                    .background(Color(0xFFFF9800), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(text = "🎙️", fontSize = 28.sp)
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "마이크 권한 필요",
                                color = Color.White,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = if (errorMessage.isNotBlank()) errorMessage
                                    else "음성 저장을 위해 마이크 권한을 허용해 주세요.",
                                color = Color.White.copy(alpha = 0.7f),
                                fontSize = 14.sp,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(14.dp))
                            Button(
                                onClick = onRequestPermission,
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5252))
                            ) {
                                Text("권한 허용하기", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}
