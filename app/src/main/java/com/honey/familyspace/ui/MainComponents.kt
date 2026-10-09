package com.honey.familyspace.ui
import android.content.Intent
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import com.honey.familyspace.widget.FamilySpaceWidget
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.honey.familyspace.notification.ReminderScheduler
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import com.honey.familyspace.util.AppUpdateManager
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.honey.familyspace.data.DataStoreManager
import com.honey.familyspace.data.InviteCodeGenerator
import com.honey.familyspace.data.SpaceRepository
import com.honey.familyspace.data.TaskRepository
import com.honey.familyspace.model.DailyRoutine
import com.honey.familyspace.model.Space
import com.honey.familyspace.model.Task
import com.honey.familyspace.model.ThemeColor
import com.honey.familyspace.model.DueBadge
import com.honey.familyspace.notification.OngoingNotificationManager
import com.honey.familyspace.util.DateTimeUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 개별 할 일 카드 컴포넌트 (짧게 누르면 완료 토글, 길게 누르면 수정/삭제)
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TaskCardItem(
    task: Task,
    todayDate: String,
    theme: ThemeColor,
    spaceBadge: String? = null,
    onToggle: () -> Unit,
    onLongClick: () -> Unit
) {
    val badge = task.getDueBadge(todayDate)
    val isOverdue = !task.isCompleted && badge == DueBadge.OVERDUE

    // 기한 초과 시 소프트 로즈 틴트 배경 및 은은한 로즈 핑크 테두리 적용 (방식 A)
    val cardBgColor = when {
        task.isCompleted -> Color(0xFFF9F9F9)
        isOverdue -> Color(0xFFFFF0F2)
        else -> Color.White
    }
    val cardBorder = if (isOverdue) {
        BorderStroke(1.dp, Color(0xFFFFA4B2).copy(alpha = 0.6f))
    } else {
        null
    }

    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = cardBgColor
        ),
        border = cardBorder,
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onToggle,
                onLongClick = onLongClick
            )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 동그라미 체크박스
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .background(
                        color = if (task.isCompleted) Color(theme.accentHex) else Color(0xFFEEEEEE),
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (task.isCompleted) {
                    Icon(Icons.Default.Check, contentDescription = "완료", tint = Color.White, modifier = Modifier.size(20.dp))
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                if (!spaceBadge.isNullOrBlank()) {
                    Surface(
                        color = Color(theme.accentHex).copy(alpha = 0.15f),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.padding(bottom = 4.dp)
                    ) {
                        Text(
                            text = "🏷️ $spaceBadge",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(theme.accentHex),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                Text(
                    text = task.title,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (task.isCompleted) Color(0xFF9E9E9E) else Color(theme.textColor),
                    textDecoration = if (task.isCompleted) TextDecoration.LineThrough else TextDecoration.None
                )

                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (badge.label.isNotBlank()) {
                        Text(
                            text = if (task.dueDate.isNotBlank() && !task.isCompleted) {
                                "${badge.label} · ${DateTimeUtils.formatKoreanDate(task.dueDate)}"
                            } else {
                                badge.label
                            },
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(badge.badgeColorHex)
                        )
                    }

                    // 소리 알람이 켜져 있는 경우 알람 시각 뱃지 표시
                    if (task.hasAlarm && !task.alarmTime.isNullOrBlank() && !task.isCompleted) {
                        if (badge.label.isNotBlank()) {
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                        val timeParts = task.alarmTime.split(":")
                        val h = timeParts.getOrNull(0)?.toIntOrNull() ?: 10
                        val m = timeParts.getOrNull(1)?.toIntOrNull() ?: 0
                        val amPm = if (h < 12) "오전" else "오후"
                        val displayH = if (h % 12 == 0) 12 else h % 12
                        val alarmStr = "$amPm $displayH:${String.format(java.util.Locale.KOREA, "%02d", m)}"
                        Surface(
                            color = Color(theme.accentHex).copy(alpha = 0.12f),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = "⏰ $alarmStr",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(theme.accentHex),
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 매일 반복 루틴 카드 컴포넌트
 * 상대 검토 등 복잡한 요소를 배제하고 직관적인 완료 토글 버튼 및 롱클릭 삭제 지원
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RoutineCardItem(
    routine: DailyRoutine,
    todayDate: String,
    theme: ThemeColor,
    spaceBadge: String? = null,
    onCheck: () -> Unit,
    onLongClick: () -> Unit
) {
    val isDone = routine.isCompletedToday(todayDate)

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isDone) Color(0xFFF1F8E9) else Color.White
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onCheck,
                onLongClick = onLongClick
            )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "🔁 매일 반복",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(theme.accentHex)
                    )
                    if (!spaceBadge.isNullOrBlank()) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(
                            color = Color(theme.accentHex).copy(alpha = 0.15f),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = "🏷️ $spaceBadge",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(theme.accentHex),
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                if (isDone) {
                    Text(
                        text = "오늘 완료! ✅ (${routine.lastCompletedTime})",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF2E7D32)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = routine.title,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = if (isDone) Color(0xFF757575) else Color(theme.textColor),
                textDecoration = if (isDone) TextDecoration.LineThrough else TextDecoration.None
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 일반 할 일처럼 심플하고 직관적인 완료 토글 버튼
            Button(
                onClick = onCheck,
                modifier = Modifier.fillMaxWidth().height(44.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isDone) Color(0xFFC8E6C9) else Color(theme.accentHex)
                )
            ) {
                Text(
                    text = if (isDone) "✅ 완료했어요" else "먹었어요 / 완료하기",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isDone) Color(0xFF1B5E20) else Color.White
                )
            }
        }
    }
}

/**
 * 스페이스가 전혀 없을 때 보여주는 시작 가이드
 */
@Composable
fun EmptySpaceGuide(
    onCreateSpace: (title: String, theme: ThemeColor) -> Unit,
    onJoinClick: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("환영합니다! 🏡", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(10.dp))
        Text("배우자나 가족과 함께 사용할\n방을 만들거나 초대 코드를 입력해 주세요.", fontSize = 15.sp, color = Color.Gray)

        Spacer(modifier = Modifier.height(30.dp))

        Button(
            onClick = { onCreateSpace("우리 부부", ThemeColor.CORAL) },
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(ThemeColor.CORAL.accentHex))
        ) {
            Text("새 방 만들기 (예: 우리 부부)", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(14.dp))

        Button(
            onClick = onJoinClick,
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF555555))
        ) {
            Text("초대 코드 입력하고 연결하기", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * 5종 감성 파스텔 테마 컬러 팔레트 선택 컴포넌트
 */
@Composable
fun ThemeColorPaletteSelector(
    selectedTheme: ThemeColor,
    onSelectTheme: (ThemeColor) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ThemeColor.entries.forEach { theme ->
            val isSelected = theme == selectedTheme
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = if (isSelected) Color(theme.accentHex).copy(alpha = 0.15f) else Color(0xFFF8F8F8),
                        shape = RoundedCornerShape(12.dp)
                    )
                    .clickable { onSelectTheme(theme) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .background(Color(theme.accentHex), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    if (isSelected) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = "선택됨",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = theme.displayName,
                    fontSize = 14.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    color = if (isSelected) Color(theme.accentHex) else Color(0xFF333333)
                )
            }
        }
    }
}

/**
 * 초대 코드 입력 및 새 방 개설 다이얼로그 (테마 컬러 선택 지원)
 */
@Composable
fun JoinSpaceDialog(
    theme: ThemeColor,
    initialCreating: Boolean = false,
    errorMessage: String? = null,
    createError: String? = null,
    onDismiss: () -> Unit,
    onJoinCode: (code: String) -> Unit,
    onCreateNew: (title: String, theme: ThemeColor) -> Unit
) {
    var inputCode by remember { mutableStateOf("") }
    var newTitle by remember { mutableStateOf("") }
    var selectedTheme by remember { mutableStateOf(ThemeColor.CORAL) }
    var isCreating by remember { mutableStateOf(initialCreating) }

    // 연결하기 버튼은 유효한 4자리 코드가 모두 입력되었을 때만 활성화
    val isCodeValid = InviteCodeGenerator.isValidCode(inputCode)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isCreating) "새 방 만들기" else "초대 코드로 연결", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                if (isCreating) {
                    OutlinedTextField(
                        value = newTitle,
                        onValueChange = { newTitle = it },
                        label = { Text("방 이름 (예: 엄마와 나, 모임)") },
                        isError = createError != null,
                        supportingText = {
                            if (createError != null) {
                                Text(createError, fontSize = 12.sp, color = Color(0xFFB3261E))
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("방 테마 색상 선택 🎨", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(6.dp))
                    ThemeColorPaletteSelector(
                        selectedTheme = selectedTheme,
                        onSelectTheme = { selectedTheme = it }
                    )
                } else {
                    OutlinedTextField(
                        value = inputCode,
                        onValueChange = { raw ->
                            // 숫자만 허용, 최대 4자리
                            inputCode = raw.filter { it.isDigit() }.take(4)
                        },
                        label = { Text("4자리 초대 코드") },
                        placeholder = { Text("예: 1234") },
                        isError = (inputCode.isNotBlank() && !isCodeValid) || errorMessage != null,
                        supportingText = {
                            when {
                                errorMessage != null -> Text(errorMessage, fontSize = 12.sp, color = Color(0xFFB3261E))
                                inputCode.isNotBlank() && !isCodeValid -> Text(
                                    "4자리 숫자를 모두 입력해 주세요",
                                    fontSize = 12.sp
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "📱 폰 교체/재설치 시 전달받은 배우자 재연결 코드도 여기에 입력하시면 기존 방으로 즉시 연결됩니다 🌸",
                        fontSize = 12.sp,
                        color = Color.Gray
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (isCreating && newTitle.isNotBlank()) {
                        onCreateNew(newTitle, selectedTheme)
                    } else if (!isCreating && isCodeValid) {
                        onJoinCode(inputCode)
                    }
                },
                enabled = if (isCreating) newTitle.isNotBlank() else isCodeValid,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isCreating) Color(selectedTheme.accentHex) else Color(theme.accentHex),
                    disabledContainerColor = Color(0xFFCCCCCC)
                )
            ) {
                Text(if (isCreating) "방 만들기" else "연결하기")
            }
        },
        dismissButton = {
            TextButton(onClick = { isCreating = !isCreating }) {
                Text(if (isCreating) "초대 코드로 참여하기" else "직접 새 방 만들기")
            }
        }
    )
}

/**
 * 🎙️ 한국어 음성 명령 사용 가이드 다이얼로그 (Information)
 */
@Composable
fun VoiceGuideDialog(
    theme: ThemeColor,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        containerColor = Color.White,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🎙️ 음성 명령 사용법 안내", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(theme.textColor))
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "전원 버튼을 2번 연속 누르면 화면이 켜지며 바로 음성 녹음이 시작됩니다 ⚡",
                    fontSize = 13.sp,
                    color = Color.DarkGray,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(14.dp))

                // 카드 1: 가족/부부 기본 할일
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("🌸 기본 가족/부부 할 일", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFFE65100))
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("• \"오늘날짜로 우유 사기 저장해줘\"", fontSize = 13.sp, color = Color(0xFF333333))
                        Text("• \"내일 세탁소 정장 찾기 등록해줘\"", fontSize = 13.sp, color = Color(0xFF333333))
                        Text("• \"이번주 금요일 부모님 병원 예약\"", fontSize = 13.sp, color = Color(0xFF333333))
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // 카드 2: 마이 내비게이터 Gantt Task (파워유저)
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFEDE7F6)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("🧭 마이 내비게이터 Gantt (파워유저)", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF512DA8))
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("• \"내비에 오늘날짜로 배관 자재 발주 체크\"", fontSize = 13.sp, color = Color(0xFF333333))
                        Text("• \"간트에 내일 P&ID 라인 넘버링 검토\"", fontSize = 13.sp, color = Color(0xFF333333))
                        Text("👉 지정하신 [Gantt 기본 수신함]으로 자동 적재!", fontSize = 11.sp, color = Color(0xFF673AB7), fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // 카드 3: 인생 라이프 그래프 (파워유저)
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("📈 인생 라이프 그래프 (파워유저)", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2E7D32))
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("• \"인생기록: 오늘 대형 프로젝트 계약 체결!\"", fontSize = 13.sp, color = Color(0xFF333333))
                        Text("• \"인생그래프: 배관 기술사 1차 합격!\"", fontSize = 13.sp, color = Color(0xFF333333))
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "💡 팁: 문장 끝의 '~저장해줘', '~적어줘'는 앱이 알아서 깔끔하게 제거하고 등록해 드려요.",
                    fontSize = 11.sp,
                    color = Color.Gray
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = Color(theme.accentHex)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("확인", color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
    )
}
