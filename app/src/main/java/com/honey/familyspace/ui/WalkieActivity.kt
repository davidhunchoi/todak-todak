package com.honey.familyspace.ui

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Bundle
import android.util.Base64
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.honey.familyspace.data.DataStoreManager
import com.honey.familyspace.data.WalkieRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * 무전기(PTT) 액티비티
 *
 * - 큰 버튼을 길게 누르는 동안 녹음
 * - 버튼에서 손 떼면 서버 업로드
 * - 3초마다 서버 폴링하여 상대방 음성 수신 시 자동 재생
 */
class WalkieActivity : ComponentActivity() {

    companion object {
        const val EXTRA_SPACE_ID = "extra_space_id"
        const val EXTRA_SPACE_TITLE = "extra_space_title"
    }

    private val requestAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasAudioPermission.value = granted
        if (!granted) {
            Toast.makeText(this, "마이크 권한이 필요합니다.", Toast.LENGTH_SHORT).show()
        }
    }

    private val hasAudioPermission = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val spaceId = intent.getStringExtra(EXTRA_SPACE_ID) ?: ""
        val spaceTitle = intent.getStringExtra(EXTRA_SPACE_TITLE) ?: "무전기"

        hasAudioPermission.value = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasAudioPermission.value) {
            requestAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }

        val dataStore = DataStoreManager(this)
        val walkieRepo = WalkieRepository(dataStore)

        setContent {
            WalkieScreen(
                spaceId = spaceId,
                spaceTitle = spaceTitle,
                hasAudioPermission = hasAudioPermission.value,
                walkieRepo = walkieRepo,
                onBack = { finish() }
            )
        }
    }
}

@Composable
fun WalkieScreen(
    spaceId: String,
    spaceTitle: String,
    hasAudioPermission: Boolean,
    walkieRepo: WalkieRepository,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()

    // 상태
    var isRecording by remember { mutableStateOf(false) }
    var isUploading by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("버튼을 길게 눌러 말하세요") }
    var receivedAudioBase64 by remember { mutableStateOf<String?>(null) }
    var receivedMsgId by remember { mutableStateOf<String?>(null) }
    var lastPollMs by remember { mutableStateOf(System.currentTimeMillis() - 300_000L) }

    // 녹음기/재생기 참조
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var tempFile by remember { mutableStateOf<File?>(null) }
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }

    // 버튼 펄스 애니메이션
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isRecording) 1.12f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    // 버튼 색상 애니메이션
    val buttonColor by animateColorAsState(
        targetValue = when {
            isRecording -> Color(0xFFE53935)
            isUploading -> Color(0xFFFB8C00)
            isPlaying -> Color(0xFF43A047)
            else -> Color(0xFF1E88E5)
        },
        animationSpec = tween(300),
        label = "buttonColor"
    )

    // 상대방 음성 수신 후 자동 재생
    LaunchedEffect(receivedAudioBase64, receivedMsgId) {
        val b64 = receivedAudioBase64 ?: return@LaunchedEffect
        val msgId = receivedMsgId ?: return@LaunchedEffect
        if (isPlaying) return@LaunchedEffect

        isPlaying = true
        statusMessage = "📻 수신 중..."

        try {
            val audioBytes = Base64.decode(b64, Base64.DEFAULT)
            val playFile = File.createTempFile("walkie_rx_", ".m4a")
            playFile.writeBytes(audioBytes)

            val player = MediaPlayer()
            mediaPlayer = player
            player.setDataSource(playFile.absolutePath)
            player.prepare()
            player.start()
            player.setOnCompletionListener {
                isPlaying = false
                statusMessage = "버튼을 길게 눌러 말하세요"
                playFile.delete()
                player.release()
                mediaPlayer = null
                // 재생 완료 서버 통보
                scope.launch {
                    runCatching { walkieRepo.markPlayed(spaceId, msgId) }
                }
            }
        } catch (e: Exception) {
            isPlaying = false
            statusMessage = "재생 오류: ${e.message}"
        }

        receivedAudioBase64 = null
        receivedMsgId = null
    }

    // 3초마다 서버 폴링 (상대방 새 음성 확인)
    LaunchedEffect(spaceId) {
        while (true) {
            delay(3_000)
            if (isRecording || isUploading || isPlaying) continue
            try {
                val result = walkieRepo.pollLatest(spaceId, afterMs = lastPollMs)
                if (result != null) {
                    lastPollMs = result.createdAt + 1
                    receivedAudioBase64 = result.audioBase64
                    receivedMsgId = result.id
                }
            } catch (_: Exception) {}
        }
    }

    // 화면 종료 시 리소스 정리
    DisposableEffect(Unit) {
        onDispose {
            recorder?.stop()
            recorder?.release()
            mediaPlayer?.stop()
            mediaPlayer?.release()
            tempFile?.delete()
        }
    }

    // UI
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFF0D1B2A), Color(0xFF1B2E3F), Color(0xFF0D1B2A))
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 상단 바
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "뒤로", tint = Color.White)
                }
                Text(
                    text = "📻 $spaceTitle 무전기",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.size(48.dp))
            }

            Spacer(modifier = Modifier.height(32.dp))

            // 무전기 상태 카드
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0x1AFFFFFF)),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "CH 01 · FamilySpace",
                        color = Color(0xFF4FC3F7),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .background(
                                    color = when {
                                        isRecording -> Color(0xFFE53935)
                                        isPlaying -> Color(0xFF43A047)
                                        else -> Color(0xFF37474F)
                                    },
                                    shape = CircleShape
                                )
                        )
                        Text(
                            text = when {
                                isRecording -> "● TX 송신 중..."
                                isUploading -> "⇑ 전송 중..."
                                isPlaying -> "● RX 수신 중..."
                                else -> "STANDBY"
                            },
                            color = Color(0xFFB0BEC5),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // 상태 메시지
            Text(
                text = statusMessage,
                color = Color(0xFFB0BEC5),
                fontSize = 16.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp)
            )

            Spacer(modifier = Modifier.height(36.dp))

            // PTT 버튼 (길게 누르기)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(220.dp)
            ) {
                // 외부 펄스 링
                if (isRecording) {
                    Box(
                        modifier = Modifier
                            .size(220.dp)
                            .scale(pulseScale)
                            .background(
                                color = Color(0x33E53935),
                                shape = CircleShape
                            )
                    )
                }

                // 메인 PTT 버튼
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(180.dp)
                        .shadow(
                            elevation = if (isRecording) 20.dp else 8.dp,
                            shape = CircleShape
                        )
                        .background(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    buttonColor,
                                    buttonColor.copy(alpha = 0.75f)
                                )
                            ),
                            shape = CircleShape
                        )
                        .pointerInput(hasAudioPermission) {
                            detectTapGestures(
                                onPress = { _ ->
                                    // 권한 없으면 안내
                                    if (!hasAudioPermission) {
                                        statusMessage = "마이크 권한을 허용해 주세요."
                                        return@detectTapGestures
                                    }
                                    if (isUploading || isPlaying) return@detectTapGestures

                                    // 녹음 시작
                                    try {
                                        val file = File.createTempFile("walkie_tx_", ".m4a")
                                        tempFile = file
                                        @Suppress("DEPRECATION")
                                        val rec = MediaRecorder()
                                        recorder = rec
                                        rec.setAudioSource(MediaRecorder.AudioSource.MIC)
                                        rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                                        rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                                        rec.setAudioSamplingRate(16000)
                                        rec.setAudioEncodingBitRate(32000)
                                        rec.setOutputFile(file.absolutePath)
                                        rec.prepare()
                                        rec.start()
                                        isRecording = true
                                        statusMessage = "🎙️ 말씀하세요..."
                                    } catch (e: Exception) {
                                        statusMessage = "녹음 오류: ${e.message}"
                                        return@detectTapGestures
                                    }

                                    val recordStart = System.currentTimeMillis()

                                    // 손 뗄 때까지 대기
                                    tryAwaitRelease()

                                    val durationMs = System.currentTimeMillis() - recordStart

                                    // 녹음 중지
                                    try {
                                        recorder?.stop()
                                        recorder?.release()
                                        recorder = null
                                    } catch (_: Exception) {}
                                    isRecording = false

                                    if (durationMs < 500) {
                                        statusMessage = "너무 짧아요! 더 길게 눌러 주세요."
                                        tempFile?.delete()
                                        tempFile = null
                                        return@detectTapGestures
                                    }

                                    // 업로드 (백그라운드 코루틴)
                                    isUploading = true
                                    statusMessage = "⇑ 전송 중..."
                                    scope.launch {
                                        try {
                                            val file = tempFile ?: return@launch
                                            val bytes = file.readBytes()
                                            val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                                            walkieRepo.send(spaceId, b64, durationMs.toInt())
                                            statusMessage = "✅ 전송 완료!"
                                        } catch (e: Exception) {
                                            statusMessage = "전송 실패: ${e.message}"
                                        } finally {
                                            isUploading = false
                                            tempFile?.delete()
                                            tempFile = null
                                            delay(2_000)
                                            if (!isRecording && !isPlaying) {
                                                statusMessage = "버튼을 길게 눌러 말하세요"
                                            }
                                        }
                                    }
                                }
                            )
                        }
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = "무전기 버튼",
                            tint = Color.White,
                            modifier = Modifier.size(60.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (isRecording) "말하는 중" else "눌러서 말하기",
                            color = Color.White.copy(alpha = 0.9f),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(50.dp))

            // 사용법 힌트
            Text(
                text = "💡 버튼을 누르고 있는 동안 녹음됩니다\n손을 떼면 상대방에게 바로 전송됩니다",
                color = Color(0xFF546E7A),
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                lineHeight = 18.sp
            )

            Spacer(modifier = Modifier.height(40.dp))
        }
    }
}
