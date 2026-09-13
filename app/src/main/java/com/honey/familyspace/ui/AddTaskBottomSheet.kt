package com.honey.familyspace.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.honey.familyspace.model.ThemeColor
import com.honey.familyspace.util.DateTimeUtils
import kotlinx.coroutines.launch

/**
 * 컴맹 아내 맞춤형 초간단 할 일 추가 바텀시트
 *
 * @param onAddTask 등록 콜백: (제목, 마감일 "YYYY-MM-DD", 매일 반복 여부)
 *                   매일 반복 선택 시 dueDate는 무시되고 매일 루틴으로 등록됨
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun AddTaskBottomSheet(
    theme: ThemeColor,
    onDismiss: () -> Unit,
    onAddTask: (title: String, dueDate: String, isDaily: Boolean, alarmTime: String?, hasAlarm: Boolean) -> Unit
) {
    val context = LocalContext.current
    var taskTitle by remember { mutableStateOf("") }
    var selectedDueDate by remember { mutableStateOf("") }
    var isDaily by remember { mutableStateOf(false) }
    var isListening by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showDatePicker by remember { mutableStateOf(false) }

    val dataStore = remember { com.honey.familyspace.data.DataStoreManager(context) }
    val scope = rememberCoroutineScope()
    val customTags by dataStore.customTagsFlow.collectAsState(initial = emptyList())
    var showAddTagDialog by remember { mutableStateOf(false) }
    var newTagInput by remember { mutableStateOf("") }
    var tagToDelete by remember { mutableStateOf<String?>(null) }

    // 특정 시각 소리 알람 상태
    var hasAlarm by remember { mutableStateOf(false) }
    var alarmHour by remember { mutableStateOf(10) }
    var alarmMinute by remember { mutableStateOf(0) }
    var showTimePicker by remember { mutableStateOf(false) }

    // 구글 공식 신경망 음성 인식 다이얼로그 런처 (정확도 95%+)
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

    // 오늘/내일/기한없음 외의 날짜가 캘린더로 선택되었는지 여부
    val isCustomDateSelected = selectedDueDate.isNotBlank() &&
        selectedDueDate != DateTimeUtils.getTodayDateString() &&
        selectedDueDate != DateTimeUtils.getTomorrowDateString()

    val initialLang = remember {
        if (java.util.Locale.getDefault().language == "en") "en-US" else "ko-KR"
    }
    var selectedLang by remember { mutableStateOf(initialLang) }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val dateOptions = listOf(
        "오늘" to DateTimeUtils.getTodayDateString(),
        "내일" to DateTimeUtils.getTomorrowDateString(),
        "기한 없음" to ""
    )

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
            // 헤더 & 언어 전환 토글
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (selectedLang == "en-US") "✨ Add New Task" else "✨ 새로운 할 일 등록",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(theme.textColor)
                )

                // 원터치 언어 전환 토글 [🇰🇷 한국어] ↔ [🇺🇸 English]
                Box(
                    modifier = Modifier
                        .background(
                            color = Color(theme.accentHex).copy(alpha = 0.18f),
                            shape = RoundedCornerShape(12.dp)
                        )
                        .clickable {
                            selectedLang = if (selectedLang == "ko-KR") "en-US" else "ko-KR"
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = if (selectedLang == "ko-KR") "🇰🇷 한국어" else "🇺🇸 English",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(theme.textColor)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 1. 입력창 & 음성 인식(마이크) 버튼
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = taskTitle,
                    onValueChange = { if (it.length <= 50) taskTitle = it },
                    placeholder = {
                        Text(
                            if (selectedLang == "en-US") "Type a task or tap mic" else "할 일을 입력하거나 마이크를 누르세요",
                            fontSize = 15.sp
                        )
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(theme.accentHex),
                        unfocusedBorderColor = Color(0xFFE0E0E0)
                    ),
                    singleLine = true
                )

                Spacer(modifier = Modifier.width(10.dp))

                // 커다란 마이크 / 녹음 토글 버튼 (구글 공식 고성능 신경망 음성인식 다이얼로그 연동)
                IconButton(
                    onClick = {
                        errorMessage = null
                        try {
                            val prompt = if (selectedLang == "en-US") "Please speak your task 🎙️" else "할 일을 말씀해 주세요 🎙️"
                            val intent = VoiceInputManager.createGoogleSpeechIntent(prompt)
                            googleSpeechLauncher.launch(intent)
                        } catch (e: Exception) {
                            // 구글 공식 다이얼로그 미설치 기기 시 내장 음성인식 엔진으로 안전하게 폴백
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

            Spacer(modifier = Modifier.height(16.dp))

            // 2. 자주 쓰는 1-Tap 추천 태그 (사용자 직접 등록/삭제)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("자주 쓰는 추천 태그 (길게 눌러 삭제):", fontSize = 13.sp, color = Color.Gray)
                TextButton(onClick = {
                    newTagInput = ""
                    showAddTagDialog = true
                }) {
                    Text("➕ 태그 추가", fontSize = 12.sp, color = Color(theme.accentHex), fontWeight = FontWeight.Bold)
                }
            }

            if (customTags.isEmpty()) {
                Text(
                    text = "등록된 태그가 없습니다. [+ 태그 추가]를 눌러 나만의 태그를 만들어 보세요 🏷️",
                    fontSize = 12.sp,
                    color = Color.Gray.copy(alpha = 0.7f),
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    customTags.forEach { tag ->
                        Box(
                            modifier = Modifier
                                .background(Color(0xFFF5F5F5), RoundedCornerShape(12.dp))
                                .combinedClickable(
                                    onClick = { taskTitle = tag },
                                    onLongClick = { tagToDelete = tag }
                                )
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Text(text = tag, fontSize = 13.sp, color = Color(0xFF333333))
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // 2-1. 매일 반복 토글 (약 먹기 같은 매일 하는 일)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = if (isDaily) Color(theme.accentHex).copy(alpha = 0.15f) else Color(0xFFF5F5F5),
                        shape = RoundedCornerShape(12.dp)
                    )
                    .clickable { isDaily = !isDaily }
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Column {
                    Text(
                        "🔁 매일 반복되는 일",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isDaily) Color(theme.accentHex) else Color(0xFF555555)
                    )
                    if (isDaily) {
                        Text(
                            "나와 아내(상대) 각각 체크하는 매일 루틴으로 등록돼요",
                            fontSize = 12.sp,
                            color = Color.Gray
                        )
                    }
                }
                Text(
                    text = if (isDaily) "ON" else "OFF",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isDaily) Color(theme.accentHex) else Color(0xFFAAAAAA)
                )
            }

            Spacer(modifier = Modifier.height(18.dp))

            // 3. 1-Tap 마감일 선택 (매일 반복이 아닐 때만 표시)
            if (!isDaily) {
                Text("마감 날짜 선택:", fontSize = 13.sp, color = Color.Gray)
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

                    // 캘린더 아이콘: 원하는 날짜 직접 선택
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

                // 캘린더로 선택한 날짜가 있으면 표시
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

                // 3-1. 특정 시각 소리 알람 설정 (모닝콜/약 먹기 등 지정 시각 정각 소리 알람)
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
            } else {
                Spacer(modifier = Modifier.height(24.dp))
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

            // 4. 등록하기 대형 버튼
            Button(
                onClick = {
                    if (taskTitle.isNotBlank()) {
                        val alarmTimeStr = if (hasAlarm && !isDaily) {
                            String.format(java.util.Locale.KOREA, "%02d:%02d", alarmHour, alarmMinute)
                        } else null
                        onAddTask(taskTitle, selectedDueDate, isDaily, alarmTimeStr, hasAlarm && !isDaily)
                        voiceManager.destroy()
                        onDismiss()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(theme.accentHex)),
                enabled = taskTitle.isNotBlank()
            ) {
                Text(
                    if (isDaily) "🔁 매일 반복으로 등록하기" else "등록하기",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    // 커스텀 태그 추가 다이얼로그
    if (showAddTagDialog) {
        AlertDialog(
            onDismissRequest = { showAddTagDialog = false },
            title = { Text("자주 쓰는 태그 추가 🏷️", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("자주 입력하는 할 일 내용을 태그로 등록하세요.", fontSize = 14.sp, color = Color.Gray)
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = newTagInput,
                        onValueChange = { newTagInput = it.take(20) },
                        placeholder = { Text("예: 💊 비타민 챙기기") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val trimmed = newTagInput.trim()
                        if (trimmed.isNotBlank() && trimmed !in customTags) {
                            scope.launch {
                                dataStore.saveCustomTags(customTags + trimmed)
                            }
                        }
                        showAddTagDialog = false
                    },
                    enabled = newTagInput.isNotBlank()
                ) {
                    Text("추가", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddTagDialog = false }) {
                    Text("취소", color = Color.Gray)
                }
            }
        )
    }

    // 커스텀 태그 삭제 확인 다이얼로그
    tagToDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { tagToDelete = null },
            title = { Text("태그 삭제", fontWeight = FontWeight.Bold) },
            text = { Text("'$target' 태그를 추천 목록에서 삭제할까요?", fontSize = 15.sp) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        dataStore.saveCustomTags(customTags.filter { it != target })
                    }
                    tagToDelete = null
                }) {
                    Text("삭제", color = Color(0xFFE53935), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { tagToDelete = null }) {
                    Text("취소", color = Color.Gray)
                }
            }
        )
    }
}
