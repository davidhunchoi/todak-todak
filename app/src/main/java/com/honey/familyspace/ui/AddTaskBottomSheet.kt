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
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
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
import com.honey.familyspace.model.ThemeColor
import com.honey.familyspace.util.DateTimeUtils

/**
 * 컴맹 아내 맞춤형 초간단 할 일 추가 바텀시트
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AddTaskBottomSheet(
    theme: ThemeColor,
    onDismiss: () -> Unit,
    onAddTask: (title: String, dueDate: String) -> Unit
) {
    val context = LocalContext.current
    var taskTitle by remember { mutableStateOf("") }
    var selectedDueDate by remember { mutableStateOf(DateTimeUtils.getTodayDateString()) }
    var isListening by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val quickTags = listOf(
        "🛒 마트 장보기",
        "🧺 세탁소 맡기기",
        "💊 병원 / 약 챙기기",
        "🗑️ 분리수거하기",
        "🧹 청소 / 환기",
        "💳 관리비 / 공과금"
    )

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
            // 헤더
            Text(
                text = "✨ 새로운 할 일 등록",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Color(theme.textColor)
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 1. 입력창 & 음성 인식(마이크) 버튼
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = taskTitle,
                    onValueChange = { taskTitle = it },
                    placeholder = { Text("할 일을 입력하거나 마이크를 누르세요", fontSize = 15.sp) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(theme.accentHex),
                        unfocusedBorderColor = Color(0xFFE0E0E0)
                    ),
                    singleLine = true
                )

                Spacer(modifier = Modifier.width(10.dp))

                // 커다란 마이크 버튼
                IconButton(
                    onClick = {
                        isListening = true
                        errorMessage = null
                        voiceManager.startListening(
                            onResult = { spokenText ->
                                isListening = false
                                taskTitle = spokenText
                            },
                            onError = { err ->
                                isListening = false
                                errorMessage = err
                            }
                        )
                    },
                    modifier = Modifier
                        .size(52.dp)
                        .background(
                            color = if (isListening) Color(0xFFFF5252) else Color(theme.accentHex),
                            shape = CircleShape
                        )
                ) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = "음성 입력",
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            if (isListening) {
                Spacer(modifier = Modifier.height(6.dp))
                Text("🎙️ 듣고 있어요... 말씀해 주세요!", color = Color(theme.accentHex), fontSize = 13.sp)
            }
            if (errorMessage != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Text("⚠️ $errorMessage", color = Color.Red, fontSize = 13.sp)
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 2. 자주 쓰는 1-Tap 추천 태그
            Text("자주 쓰는 추천 태그 (터치 시 자동 완성):", fontSize = 13.sp, color = Color.Gray)
            Spacer(modifier = Modifier.height(8.dp))

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                quickTags.forEach { tag ->
                    Box(
                        modifier = Modifier
                            .background(Color(0xFFF5F5F5), RoundedCornerShape(12.dp))
                            .clickable {
                                taskTitle = tag
                            }
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text(text = tag, fontSize = 13.sp, color = Color(0xFF333333))
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // 3. 1-Tap 마감일 선택
            Text("마감 날짜 선택:", fontSize = 13.sp, color = Color.Gray)
            Spacer(modifier = Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
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
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 4. 등록하기 대형 버튼
            Button(
                onClick = {
                    if (taskTitle.isNotBlank()) {
                        onAddTask(taskTitle, selectedDueDate)
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
                Text("등록하기", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
