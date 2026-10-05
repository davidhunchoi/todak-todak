package com.honey.familyspace.ui

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
    ERROR
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

        setContent {
            QuickVoiceScreen(
                onStartListening = { onPartial, onResult, onError ->
                    vibrateStart()
                    voiceInputManager.startListening(
                        onPartialResult = onPartial,
                        onResult = onResult,
                        onError = onError
                    )
                },
                onProcessVoiceResult = { rawText, onComplete, onError ->
                    processVoiceInput(rawText, onComplete, onError)
                },
                onDismiss = { finish() }
            )
        }
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

                // 활성 스페이스 확인 (없으면 첫 번째 스페이스 사용)
                var spaceId = dataStoreManager.activeSpaceIdFlow.first()
                if (spaceId.isNullOrBlank()) {
                    val spaceRepo = SpaceRepository(dataStoreManager)
                    val spaces = spaceRepo.observeMySpaces().first()
                    if (spaces.isNotEmpty()) {
                        spaceId = spaces[0].id
                        dataStoreManager.setActiveSpaceId(spaceId)
                    }
                }

                // 1) 토닥토닥 로컬/Firestore 저장
                if (!spaceId.isNullOrBlank()) {
                    taskRepository.addTask(
                        spaceId = spaceId,
                        title = parsed.content,
                        dueDate = parsed.targetDate
                    )
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
    onStartListening: (onPartial: (String) -> Unit, onResult: (String) -> Unit, onError: (String) -> Unit) -> Unit,
    onProcessVoiceResult: (rawText: String, onComplete: (VoiceParseResult, String) -> Unit, onError: (String) -> Unit) -> Unit,
    onDismiss: () -> Unit
) {
    var state by remember { mutableStateOf(QuickVoiceState.LISTENING) }
    var recognizedText by remember { mutableStateOf("") }
    var savedInfo by remember { mutableStateOf<Pair<VoiceParseResult, String>?>(null) }
    var errorMessage by remember { mutableStateOf("") }

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

    LaunchedEffect(Unit) {
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
    }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onDismiss() },
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
                                text = "듣고 있어요...",
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
                        }
                    }
                }
            }
        }
    }
}
