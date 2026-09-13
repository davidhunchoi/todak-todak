package com.honey.familyspace.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import android.app.Activity
import android.app.TimePickerDialog
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.honey.familyspace.model.Task
import com.honey.familyspace.model.ThemeColor
import com.honey.familyspace.util.DateTimeUtils

/**
 * 할 일 카드 길게 누르기 시 열리는 내용/마감일 수정 및 삭제 바텀시트
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditTaskBottomSheet(
    task: Task,
    theme: ThemeColor,
    onDismiss: () -> Unit,
    onUpdateTask: (newTitle: String, newDueDate: String, newAlarmTime: String?, newHasAlarm: Boolean) -> Unit,
    onDeleteTask: () -> Unit
) {
    val context = LocalContext.current
    var taskTitle by remember { mutableStateOf(task.title) }
    var selectedDueDate by remember { mutableStateOf(task.dueDate) }
    var isListening by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }

    // 특정 시각 소리 알람 상태 (기존 할 일 정보 기반)
    val initialHour = remember(task.alarmTime) {
        task.alarmTime?.split(":")?.getOrNull(0)?.toIntOrNull() ?: 10
    }
    val initialMinute = remember(task.alarmTime) {
        task.alarmTime?.split(":")?.getOrNull(1)?.toIntOrNull() ?: 0
    }
    var hasAlarm by remember { mutableStateOf(task.hasAlarm) }
    var alarmHour by remember { mutableStateOf(initialHour) }
    var alarmMinute by remember { mutableStateOf(initialMinute) }
    var showTimePicker by remember { mutableStateOf(false) }

    // 구글 공식 신경망 음성 인식 다이얼로그 런처
    val googleSpeechLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val matches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val spokenText = matches?.firstOrNull()
            if (!spokenText.isNullOrBlank()) {
                taskTitle = spokenText.take(50)
                errorMessage = null
            }
        }
    }

    val dateOptions = listOf(
        "오늘" to DateTimeUtils.getTodayDateString(),
        "내일" to DateTimeUtils.getTomorrowDateString(),
        "기한 없음" to ""
    )

    val isCustomDateSelected = selectedDueDate.isNotBlank() &&
        selectedDueDate != DateTimeUtils.getTodayDateString() &&
        selectedDueDate != DateTimeUtils.getTomorrowDateString()

    val initialLang = remember {
        if (java.util.Locale.getDefault().language == "en") "en-US" else "ko-KR"
    }
    var selectedLang by remember { mutableStateOf(initialLang) }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val voiceManager = remember { VoiceInputManager(context) }

    ModalBottomSheet(
        onDismissRequest = {
            voiceManager.destroy()
            onDismiss()
        },
        sheetState = sheetState,
        containerColor = Color(theme.cardBackgroundHex),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp)
        ) {
            // 헤더
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "✏️ 할 일 수정",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(theme.textColor)
                )

                // 언어 전환 토글
                Row(
                    modifier = Modifier
                        .background(Color(0xFFEEEEEE), RoundedCornerShape(14.dp))
                        .padding(3.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .background(
                                color = if (selectedLang == "ko-KR") Color(theme.accentHex) else Color.Transparent,
                                shape = RoundedCornerShape(12.dp)
                            )
                            .clickable { selectedLang = "ko-KR" }
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "한국어",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (selectedLang == "ko-KR") Color.White else Color.Gray
                        )
                    }
                    Box(
                        modifier = Modifier
                            .background(
                                color = if (selectedLang == "en-US") Color(theme.accentHex) else Color.Transparent,
                                shape = RoundedCornerShape(12.dp)
                            )
                            .clickable { selectedLang = "en-US" }
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "English",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (selectedLang == "en-US") Color.White else Color.Gray
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // 1. 입력창 & 음성 인식(마이크/정지) 토글 버튼
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = taskTitle,
                    onValueChange = { if (it.length <= 50) taskTitle = it },
                    placeholder = { Text("할 일을 입력하거나 마이크로 말씀하세요", fontSize = 15.sp) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(theme.accentHex),
                        unfocusedBorderColor = Color(0xFFE0E0E0)
                    ),
                    singleLine = true
                )

                Spacer(modifier = Modifier.width(10.dp))

                IconButton(
                    onClick = {
                        errorMessage = null
                        try {
                            val prompt = if (selectedLang == "en-US") "Please speak your task 🎙️" else "할 일을 말씀해 주세요 🎙️"
                            val intent = VoiceInputManager.createGoogleSpeechIntent(prompt)
                            googleSpeechLauncher.launch(intent)
                        } catch (e: Exception) {
                            if (isListening) {
                                voiceManager.stopListening()
                                isListening = false
                            } else {
                                isListening = true
                                voiceManager.startListening(
                                    languageCode = selectedLang,
                                    onPartialResult = { partialText -> taskTitle = partialText.take(50) },
                                    onResult = { spokenText ->
                                        isListening = false
                                        taskTitle = spokenText.take(50)
                                    },
                                    onError = { err ->
                                        isListening = false
                                        errorMessage = err
                                    }
                                )
                            }
                        }
                    },
                    modifier = Modifier
                        .size(52.dp)
                        .background(
                            color = if (isListening) Color(0xFFE53935) else Color(theme.accentHex),
                            shape = CircleShape
                        )
                ) {
                    Icon(
                        imageVector = if (isListening) Icons.Default.Stop else Icons.Default.Mic,
                        contentDescription = if (isListening) "녹음 중지" else "음성 입력",
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            // 50자 글자 수 카운터 표시
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp, end = 6.dp),
                horizontalArrangement = Arrangement.End
            ) {
                Text(
                    text = "${taskTitle.length}/50자",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (taskTitle.length >= 50) Color(0xFFE53935) else Color.Gray
                )
            }

            if (isListening) {
                Spacer(modifier = Modifier.height(8.dp))
                val msg = if (selectedLang == "en-US") {
                    "🎙️ Listening... Tap ■ (Stop) when finished!"
                } else {
                    "🎙️ 듣고 있어요... 말씀이 끝나면 ■ (네모)를 눌러주세요!"
                }
                Text(msg, color = Color(0xFFE53935), fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
            if (errorMessage != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Text("⚠️ $errorMessage", color = Color.Red, fontSize = 13.sp)
            }

            Spacer(modifier = Modifier.height(20.dp))

            // 2. 마감 날짜 선택
            Text("마감 날짜 변경:", fontSize = 13.sp, color = Color.Gray)
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                dateOptions.forEach { (label, dateVal) ->
                    val isSelected = selectedDueDate == dateVal
                    Box(
                        modifier = Modifier
                            .background(
                                color = if (isSelected) Color(theme.accentHex) else Color(0xFFF5F5F5),
                                shape = RoundedCornerShape(12.dp)
                            )
                            .clickable { selectedDueDate = dateVal }
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                    ) {
                        Text(
                            text = label,
                            fontSize = 14.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSelected) Color.White else Color(0xFF555555)
                        )
                    }
                }

                // 캘린더 아이콘
                IconButton(
                    onClick = { showDatePicker = true },
                    modifier = Modifier
                        .size(44.dp)
                        .background(
                            color = if (isCustomDateSelected) Color(theme.accentHex).copy(alpha = 0.15f) else Color(0xFFF5F5F5),
                            shape = RoundedCornerShape(12.dp)
                        )
                ) {
                    Icon(
                        imageVector = Icons.Default.CalendarToday,
                        contentDescription = "캘린더로 날짜 선택",
                        tint = if (isCustomDateSelected) Color(theme.accentHex) else Color(0xFF555555),
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            if (isCustomDateSelected) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "📅 선택한 마감일: ${DateTimeUtils.formatKoreanDate(selectedDueDate)}",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(theme.accentHex)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 2-1. 특정 시각 소리 알람 설정
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = if (hasAlarm) Color(theme.accentHex).copy(alpha = 0.08f) else Color(0xFFF7F7F7),
                        shape = RoundedCornerShape(14.dp)
                    )
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("⏰", fontSize = 20.sp)
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "특정 시각 소리 알람",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(theme.textColor)
                        )
                        val amPm = if (alarmHour < 12) "오전" else "오후"
                        val displayHour = if (alarmHour % 12 == 0) 12 else alarmHour % 12
                        val formattedTime = "$amPm $displayHour:${String.format(java.util.Locale.KOREA, "%02d", alarmMinute)}"
                        Text(
                            text = if (hasAlarm) "$formattedTime 모닝콜처럼 소리 울림" else "정해진 시간에 소리로 깨워줘요",
                            fontSize = 12.sp,
                            color = if (hasAlarm) Color(theme.accentHex) else Color.Gray,
                            fontWeight = if (hasAlarm) FontWeight.SemiBold else FontWeight.Normal
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (hasAlarm) {
                        TextButton(onClick = { showTimePicker = true }) {
                            Text("시간 변경", fontSize = 13.sp, color = Color(theme.accentHex), fontWeight = FontWeight.Bold)
                        }
                    }
                    Switch(
                        checked = hasAlarm,
                        onCheckedChange = { checked ->
                            hasAlarm = checked
                            if (checked) {
                                showTimePicker = true
                            }
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = Color(theme.accentHex)
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 3. 수정 완료 버튼
            Button(
                onClick = {
                    if (taskTitle.isNotBlank()) {
                        val alarmTimeStr = if (hasAlarm) {
                            String.format(java.util.Locale.KOREA, "%02d:%02d", alarmHour, alarmMinute)
                        } else null
                        onUpdateTask(taskTitle, selectedDueDate, alarmTimeStr, hasAlarm)
                        voiceManager.destroy()
                        onDismiss()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(theme.accentHex)),
                enabled = taskTitle.isNotBlank()
            ) {
                Text("수정 완료", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 4. 삭제 버튼 (빨간색)
            Button(
                onClick = { showDeleteConfirmDialog = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFEBEE))
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "삭제",
                    tint = Color(0xFFD32F2F),
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("이 할 일 삭제하기", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color(0xFFD32F2F))
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    // 시:분 선택 타임피커
    if (showTimePicker) {
        TimePickerDialog(
            context,
            { _, h, m ->
                alarmHour = h
                alarmMinute = m
                hasAlarm = true
                showTimePicker = false
            },
            alarmHour,
            alarmMinute,
            false
        ).apply {
            setOnDismissListener { showTimePicker = false }
            show()
        }
    }

    // 캘린더 데이트피커
    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = System.currentTimeMillis()
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { millis ->
                        selectedDueDate = DateTimeUtils.formatDateString(millis)
                    }
                    showDatePicker = false
                }) {
                    Text("선택", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text("취소", color = Color.Gray)
                }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }

    // 삭제 확인 다이얼로그
    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            title = { Text("할 일 삭제", fontWeight = FontWeight.Bold) },
            text = { Text("'${task.title}'을(를) 정말 삭제할까요?") },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirmDialog = false
                        voiceManager.destroy()
                        onDeleteTask()
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))
                ) {
                    Text("삭제", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = false }) {
                    Text("취소")
                }
            }
        )
    }
}
