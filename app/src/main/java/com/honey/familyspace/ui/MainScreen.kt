package com.honey.familyspace.ui

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.honey.familyspace.data.SpaceRepository
import com.honey.familyspace.data.TaskRepository
import com.honey.familyspace.model.DailyRoutine
import com.honey.familyspace.model.Space
import com.honey.familyspace.model.Task
import com.honey.familyspace.model.ThemeColor
import com.honey.familyspace.notification.OngoingNotificationManager
import com.honey.familyspace.util.DateTimeUtils
import kotlinx.coroutines.launch

/**
 * 컴맹 아내 맞춤형 메인 화면 (Adaptive Multi-Space & Theme Color)
 */
@Composable
fun MainScreen(
    spaceRepo: SpaceRepository,
    taskRepo: TaskRepository,
    dataStore: DataStoreManager
) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()

    val mySpaces by spaceRepo.observeMySpaces().collectAsState(initial = emptyList())
    val activeSpaceId by dataStore.activeSpaceIdFlow.collectAsState(initial = null)

    val currentSpace = mySpaces.find { it.id == activeSpaceId } ?: mySpaces.firstOrNull()
    val currentTheme = currentSpace?.getTheme() ?: ThemeColor.CORAL

    val tasks by if (currentSpace != null) {
        taskRepo.observeTasks(currentSpace.id).collectAsState(initial = emptyList())
    } else {
        remember { mutableStateOf(emptyList<Task>()) }
    }

    val routines by if (currentSpace != null) {
        taskRepo.observeRoutines(currentSpace.id).collectAsState(initial = emptyList())
    } else {
        remember { mutableStateOf(emptyList<DailyRoutine>()) }
    }

    val todayDate = DateTimeUtils.getTodayDateString()
    var showAddSheet by remember { mutableStateOf(false) }
    var showInviteDialog by remember { mutableStateOf(false) }
    var showJoinDialog by remember { mutableStateOf(false) }
    var generatedCode by remember { mutableStateOf<String?>(null) }
    var updateInfo by remember { mutableStateOf<AppUpdateManager.UpdateInfo?>(null) }

    // 앱 실행 시 백엔드 서버에 새 버전(업데이트) 있는지 자동 확인
    LaunchedEffect(Unit) {
        val info = AppUpdateManager.checkForUpdate(context)
        if (info != null && info.hasUpdate) {
            updateInfo = info
        }
    }

    Scaffold(
        containerColor = Color(currentTheme.backgroundHex),
        floatingActionButton = {
            if (currentSpace != null) {
                FloatingActionButton(
                    onClick = { showAddSheet = true },
                    containerColor = Color(currentTheme.accentHex),
                    contentColor = Color.White,
                    shape = CircleShape,
                    modifier = Modifier.size(64.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = "할 일 추가", modifier = Modifier.size(32.dp))
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            // 1. 적응형 상단 헤더 (방이 1개일 땐 단일 헤더, 2개 이상일 땐 알약 탭)
            if (mySpaces.size <= 1) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "🏠 ${currentSpace?.title ?: "우리 공간"}",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color(currentTheme.textColor)
                    )

                    IconButton(onClick = { showJoinDialog = true }) {
                        Icon(Icons.Default.PersonAdd, contentDescription = "초대/연결", tint = Color(currentTheme.accentHex))
                    }
                }
            } else {
                // 다중 스페이스 알약 탭
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(mySpaces) { space ->
                        val isSelected = space.id == currentSpace?.id
                        val spaceTheme = space.getTheme()
                        Box(
                            modifier = Modifier
                                .background(
                                    color = if (isSelected) Color(spaceTheme.accentHex) else Color.White,
                                    shape = RoundedCornerShape(20.dp)
                                )
                                .clickable {
                                    scope.launch { dataStore.setActiveSpaceId(space.id) }
                                }
                                .padding(horizontal = 18.dp, vertical = 10.dp)
                        ) {
                            Text(
                                text = space.title,
                                fontSize = 15.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) Color.White else Color(0xFF666666)
                            )
                        }
                    }

                    item {
                        IconButton(onClick = { showJoinDialog = true }) {
                            Icon(Icons.Default.Add, contentDescription = "새 방 추가", tint = Color(currentTheme.accentHex))
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 스페이스가 전혀 없는 초기 상태
            if (mySpaces.isEmpty()) {
                EmptySpaceGuide(
                    onCreateSpace = { title, theme ->
                        scope.launch {
                            val result = spaceRepo.createSpace(title, theme)
                            result.onSuccess { (space, code) ->
                                dataStore.setActiveSpaceId(space.id)
                                generatedCode = code
                                showInviteDialog = true
                            }
                        }
                    },
                    onJoinClick = { showJoinDialog = true }
                )
                return@Column
            }

            // 2. 매일 루틴 카드 (약 먹기 안심 체크)
            val routine = routines.firstOrNull()
            if (routine != null) {
                val isDone = routine.isCompletedToday(todayDate)

                Card(
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isDone) Color(0xFFE8F5E9) else Color.White
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "💊 매일 안심 루틴",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isDone) Color(0xFF2E7D32) else Color(currentTheme.accentHex)
                            )

                            if (isDone) {
                                Text(
                                    text = "${routine.lastCompletedTime} 완료",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF2E7D32)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = routine.title,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(currentTheme.textColor)
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Button(
                            onClick = {
                                if (!isDone) {
                                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                    scope.launch {
                                        currentSpace?.let { space ->
                                            taskRepo.checkRoutineDone(space.id, routine.id)
                                        }
                                        OngoingNotificationManager.updateOngoingNotification(context)
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isDone) Color(0xFFC8E6C9) else Color(currentTheme.accentHex)
                            ),
                            enabled = !isDone
                        ) {
                            Text(
                                text = if (isDone) "✅ 오늘 약 복용 완료" else "먹었어요! 터치해서 체크 💊",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isDone) Color(0xFF1B5E20) else Color.White
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))
            }

            // 3. 오늘 할 일 상태 요약 배너
            val uncompletedCount = tasks.count { !it.isCompleted }
            val bannerText = if (uncompletedCount == 0) "오늘 챙길 일을 모두 마쳤어요! 💖" else "오늘 챙길 일 ${uncompletedCount}개 남음"

            Text(
                text = bannerText,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = Color(currentTheme.textColor)
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 4. 할 일 리스트
            if (tasks.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text("등록된 할 일이 없습니다.\n하단의 + 버튼을 눌러 추가해 보세요 ☀️", color = Color.Gray, fontSize = 15.sp)
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    items(tasks) { task ->
                        TaskCardItem(
                            task = task,
                            todayDate = todayDate,
                            theme = currentTheme,
                            onToggle = {
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                scope.launch {
                                    currentSpace?.let { space ->
                                        taskRepo.toggleTask(space.id, task.id, task.isCompleted)
                                    }
                                    OngoingNotificationManager.updateOngoingNotification(context)
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    // 할 일 추가 바텀시트
    if (showAddSheet && currentSpace != null) {
        AddTaskBottomSheet(
            theme = currentTheme,
            onDismiss = { showAddSheet = false },
            onAddTask = { title, dueDate ->
                scope.launch {
                    taskRepo.addTask(currentSpace.id, title, dueDate)
                    OngoingNotificationManager.updateOngoingNotification(context)
                }
            }
        )
    }

    // 6자리 초대 코드 확인 다이얼로그
    if (showInviteDialog && generatedCode != null) {
        AlertDialog(
            onDismissRequest = { showInviteDialog = false },
            title = { Text("우리 집 초대 코드", fontWeight = FontWeight.Bold) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("상대방 폰에 아래 6자리 코드를 입력해 주세요:")
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = generatedCode ?: "",
                        fontSize = 32.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color(currentTheme.accentHex)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("(연결 완료 시 코드는 자동으로 소멸됩니다)", fontSize = 12.sp, color = Color.Gray)
                }
            },
            confirmButton = {
                TextButton(onClick = { showInviteDialog = false }) {
                    Text("확인")
                }
            }
        )
    }

    // 인앱 자체 자동 업데이트 알림 다이얼로그 (대안 B)
    updateInfo?.let { info ->
        AlertDialog(
            onDismissRequest = { updateInfo = null },
            shape = RoundedCornerShape(24.dp),
            containerColor = Color.White,
            title = {
                Text("🌸 새로운 토닥토닥 업데이트", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2D2422))
            },
            text = {
                Column {
                    Text("최신 버전: v${info.versionName}", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFF7A66))
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(info.changelog, fontSize = 14.sp, color = Color(0xFF555555))
                    Spacer(modifier = Modifier.height(10.dp))
                    Text("지금 바로 1초 만에 업데이트하시겠어요?", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xFF2D2422))
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val apkUrl = info.apkUrl
                        updateInfo = null
                        AppUpdateManager.startDownloadAndInstall(context, apkUrl)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF7A66)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("지금 업데이트 🚀", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { updateInfo = null }) {
                    Text("나중에", color = Color.Gray)
                }
            }
        )
    }

    // 6자리 초대 코드 입력 및 새 방 생성 다이얼로그
    if (showJoinDialog) {
        JoinSpaceDialog(
            theme = currentTheme,
            onDismiss = { showJoinDialog = false },
            onJoinCode = { code ->
                scope.launch {
                    val result = spaceRepo.joinSpaceByCode(code)
                    result.onSuccess { joined ->
                        dataStore.setActiveSpaceId(joined.id)
                        showJoinDialog = false
                    }
                }
            },
            onCreateNew = { title, theme ->
                scope.launch {
                    val result = spaceRepo.createSpace(title, theme)
                    result.onSuccess { (space, code) ->
                        dataStore.setActiveSpaceId(space.id)
                        generatedCode = code
                        showJoinDialog = false
                        showInviteDialog = true
                    }
                }
            }
        )
    }
}

/**
 * 개별 할 일 카드 컴포넌트
 */
@Composable
private fun TaskCardItem(
    task: Task,
    todayDate: String,
    theme: ThemeColor,
    onToggle: () -> Unit
) {
    val badge = task.getDueBadge(todayDate)

    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (task.isCompleted) Color(0xFFF9F9F9) else Color.White
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() }
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
                Text(
                    text = task.title,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (task.isCompleted) Color(0xFF9E9E9E) else Color(theme.textColor),
                    textDecoration = if (task.isCompleted) TextDecoration.LineThrough else TextDecoration.None
                )

                if (badge.label.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = badge.label,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(badge.badgeColorHex)
                    )
                }
            }
        }
    }
}

/**
 * 스페이스가 전혀 없을 때 보여주는 시작 가이드
 */
@Composable
private fun EmptySpaceGuide(
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
 * 초대 코드 입력 및 새 방 개설 다이얼로그
 */
@Composable
private fun JoinSpaceDialog(
    theme: ThemeColor,
    onDismiss: () -> Unit,
    onJoinCode: (code: String) -> Unit,
    onCreateNew: (title: String, theme: ThemeColor) -> Unit
) {
    var inputCode by remember { mutableStateOf("") }
    var newTitle by remember { mutableStateOf("") }
    var isCreating by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isCreating) "새 방 만들기" else "초대 코드로 연결", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                if (isCreating) {
                    OutlinedTextField(
                        value = newTitle,
                        onValueChange = { newTitle = it },
                        label = { Text("방 이름 (예: 엄마와 나)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                } else {
                    OutlinedTextField(
                        value = inputCode,
                        onValueChange = { inputCode = it },
                        label = { Text("6자리 초대 코드 (예: H79-K2P)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (isCreating && newTitle.isNotBlank()) {
                        onCreateNew(newTitle, ThemeColor.GREEN)
                    } else if (!isCreating && inputCode.isNotBlank()) {
                        onJoinCode(inputCode)
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(theme.accentHex))
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
