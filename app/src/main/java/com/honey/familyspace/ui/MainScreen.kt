package com.honey.familyspace.ui

import android.content.Intent
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.PersonAdd
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import com.honey.familyspace.notification.OngoingNotificationManager
import com.honey.familyspace.util.DateTimeUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 컴맹 아내 맞춤형 메인 화면 (Adaptive Multi-Space & Theme Color)
 * - 방 이름 길게 누르기 → 방 삭제 (상대방 동의 필요)
 */
@OptIn(ExperimentalFoundationApi::class)
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
    var joinDialogCreating by remember { mutableStateOf(false) }
    var joinError by remember { mutableStateOf<String?>(null) }
    var createError by remember { mutableStateOf<String?>(null) }
    var generatedCode by remember { mutableStateOf<String?>(null) }
    var updateInfo by remember { mutableStateOf<AppUpdateManager.UpdateInfo?>(null) }
    var editingTask by remember { mutableStateOf<Task?>(null) }

    // 주기적 잔소리 알림 설정 상태
    val reminderInterval by dataStore.reminderIntervalHoursFlow.collectAsState(initial = 2)
    val reminderNightMute by dataStore.reminderNightMuteFlow.collectAsState(initial = true)
    var showReminderSettingsDialog by remember { mutableStateOf(false) }

    // 방 삭제 관련 상태
    var showDeleteDialog by remember { mutableStateOf(false) }
    var deleteTargetSpace by remember { mutableStateOf<Space?>(null) }
    var deleteWaiting by remember { mutableStateOf(false) }
    var showDeleteConsentDialog by remember { mutableStateOf(false) }
    var consentTargetSpace by remember { mutableStateOf<Space?>(null) }

    // 앱 실행 시 백엔드 서버에 새 버전(업데이트) 있는지 확인 및 내 방 목록 동기화
    // - 자동 다운로드는 하지 않음: 업데이트 팝업에서 [지금 업데이트]를 눌렀을 때만 다운로드
    //   (이전처럼 실행 직후 토스트+자동 다운로드는 "삭제하려는데 업그레이드?" 혼란 + 다운로드/설치 충돌 원인이었음)
    LaunchedEffect(Unit) {
        val info = AppUpdateManager.checkForUpdate(context)
        if (info != null && info.hasUpdate) {
            updateInfo = info
        }
        spaceRepo.syncSpacesFromServer()

        // 리마인더 알람 스케줄 확인 및 등록
        ReminderScheduler.scheduleReminder(context, reminderInterval)
    }

    // 현재 스페이스의 매일 루틴 동기화 (화면 진입/스페이스 변경 시)
    // 상대방이 체크한 루틴 상태를 서버에서 가져와 반영
    LaunchedEffect(currentSpace?.id) {
        currentSpace?.let { space ->
            taskRepo.syncRoutinesFromServer(space.id)
        }
    }

    // 방 삭제 동의 상태 폴링 (20초마다)
    // - 상대방이 삭제를 요청했으면 동의 다이얼로그 표시
    // - 내 요청이 대기 중이면 배너 표시
    LaunchedEffect(currentSpace?.id) {
        while (true) {
            currentSpace?.let { space ->
                spaceRepo.getDeleteRequestStatus(space.id).onSuccess { status ->
                    if (status.otherRequested) {
                        consentTargetSpace = space
                        showDeleteConsentDialog = true
                    } else {
                        deleteWaiting = status.myRequested
                    }
                }
            }
            delay(20_000)
        }
    }

    // 삭제 완료 처리: 로컬에서 제거 + 다른 방으로 전환
    val removeDeletedSpace: (Space) -> Unit = { space ->
        spaceRepo.removeSpaceLocally(space.id)
        val remaining = mySpaces.filter { it.id != space.id }
        scope.launch { dataStore.setActiveSpaceId(remaining.firstOrNull()?.id ?: "") }
        Toast.makeText(context, "'${space.title}' 방이 삭제되었어요", Toast.LENGTH_SHORT).show()
        deleteWaiting = false
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
                        color = Color(currentTheme.textColor),
                        modifier = Modifier.combinedClickable(
                            onClick = {},
                            onLongClick = {
                                // 방 이름 길게 누르기 → 방 삭제 요청 (상대방 동의 필요)
                                currentSpace?.let { space ->
                                    deleteTargetSpace = space
                                    showDeleteDialog = true
                                }
                            }
                        )
                    )

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // 1. 초대 코드 입력 버튼 (상시 노출)
                        IconButton(onClick = {
                            joinDialogCreating = false
                            joinError = null
                            showJoinDialog = true
                        }) {
                            Icon(Icons.Default.Key, contentDescription = "초대 코드 입력", tint = Color(currentTheme.accentHex))
                        }

                        // 2. 방 초대하기 버튼
                        if (currentSpace != null) {
                            IconButton(onClick = {
                                scope.launch {
                                    spaceRepo.getOrRefreshInviteCode(currentSpace.id).onSuccess { code ->
                                        generatedCode = code
                                        showInviteDialog = true
                                    }
                                }
                            }) {
                                Icon(Icons.Default.PersonAdd, contentDescription = "가족 초대하기", tint = Color(currentTheme.accentHex))
                            }
                        }

                        // 3. 리마인더 알림 설정 버튼
                        IconButton(onClick = { showReminderSettingsDialog = true }) {
                            Icon(Icons.Default.Settings, contentDescription = "알림 설정", tint = Color(currentTheme.accentHex))
                        }
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
                                .combinedClickable(
                                    onClick = {
                                        scope.launch { dataStore.setActiveSpaceId(space.id) }
                                    },
                                    onLongClick = {
                                        // 알약 길게 누르기 → 해당 방 삭제 요청
                                        deleteTargetSpace = space
                                        showDeleteDialog = true
                                    }
                                )
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
                        IconButton(onClick = {
                            joinDialogCreating = false
                            joinError = null
                            showJoinDialog = true
                        }) {
                            Icon(Icons.Default.Key, contentDescription = "초대 코드 입력", tint = Color(currentTheme.accentHex))
                        }
                    }

                    item {
                        IconButton(onClick = {
                            currentSpace?.let { space ->
                                scope.launch {
                                    spaceRepo.getOrRefreshInviteCode(space.id).onSuccess { code ->
                                        generatedCode = code
                                        showInviteDialog = true
                                    }
                                }
                            }
                        }) {
                            Icon(Icons.Default.PersonAdd, contentDescription = "방 초대하기", tint = Color(currentTheme.accentHex))
                        }
                    }

                    item {
                        IconButton(onClick = { showJoinDialog = true; joinDialogCreating = true }) {
                            Icon(Icons.Default.Add, contentDescription = "새 방 추가", tint = Color(currentTheme.accentHex))
                        }
                    }

                    item {
                        IconButton(onClick = { showReminderSettingsDialog = true }) {
                            Icon(Icons.Default.Settings, contentDescription = "알림 설정", tint = Color(currentTheme.accentHex))
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 방 삭제 동의 대기 배너 (내가 삭제를 요청한 상태)
            if (deleteWaiting && currentSpace != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFFFF3E0), RoundedCornerShape(12.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Text(
                        text = "🗑️ '${currentSpace.title}' 방 삭제 동의를\n상대방이 기다리고 있어요...",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF8D6E63)
                    )
                    TextButton(onClick = {
                        scope.launch {
                            spaceRepo.cancelDeleteSpace(currentSpace.id)
                            deleteWaiting = false
                            Toast.makeText(context, "삭제 요청을 취소했어요", Toast.LENGTH_SHORT).show()
                        }
                    }) {
                        Text("요청 취소", fontSize = 13.sp, color = Color(0xFFE65100), fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
            }

            // 스페이스가 전혀 없는 초기 상태
            if (mySpaces.isEmpty()) {
                EmptySpaceGuide(
                    onCreateSpace = { title, theme ->
                        createError = null
                        scope.launch {
                            val result = spaceRepo.createSpace(title, theme)
                            result.onSuccess { (space, code) ->
                                dataStore.setActiveSpaceId(space.id)
                                generatedCode = code
                                showInviteDialog = true
                            }.onFailure { e ->
                                createError = e.message
                            }
                        }
                    },
                    onJoinClick = {
                        joinDialogCreating = false
                        showJoinDialog = true
                    }
                )
                return@Column
            }

            // 3. 오늘 할 일 상태 요약 배너 (일반 할 일 + 매일 루틴 통합)
            val uncompletedTasksCount = tasks.count { !it.isCompleted }
            val uncompletedRoutinesCount = routines.count { !it.isCompletedToday(todayDate) }
            val totalUncompletedCount = uncompletedTasksCount + uncompletedRoutinesCount
            val bannerText = if (totalUncompletedCount == 0) "오늘 챙길 일을 모두 마쳤어요! 💖" else "오늘 챙길 일 ${totalUncompletedCount}개 남음"

            Text(
                text = bannerText,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = Color(currentTheme.textColor)
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 4. 목록 (매일 반복 루틴 카드 + 일반 할 일 카드)
            if (tasks.isEmpty() && routines.isEmpty()) {
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
                    // 매일 반복 루틴 카드
                    items(routines) { routine ->
                        RoutineCardItem(
                            routine = routine,
                            todayDate = todayDate,
                            theme = currentTheme,
                            onCheck = {
                                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                scope.launch {
                                    currentSpace?.let { space ->
                                        taskRepo.checkRoutineDone(space.id, routine.id)
                                        taskRepo.syncRoutinesFromServer(space.id)
                                    }
                                    OngoingNotificationManager.updateOngoingNotification(context)
                                }
                            },
                            onLongClick = {
                                scope.launch {
                                    currentSpace?.let { space ->
                                        taskRepo.deleteRoutine(space.id, routine.id)
                                        OngoingNotificationManager.updateOngoingNotification(context)
                                        Toast.makeText(context, "'${routine.title}' 루틴을 삭제했어요", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        )
                    }

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
                            },
                            onLongClick = {
                                editingTask = task
                            }
                        )
                    }
                }
            }
        }
    }

    // 할 일 수정 및 삭제 바텀시트
    editingTask?.let { taskToEdit ->
        if (currentSpace != null) {
            EditTaskBottomSheet(
                task = taskToEdit,
                theme = currentTheme,
                onDismiss = { editingTask = null },
                onUpdateTask = { newTitle, newDueDate ->
                    scope.launch {
                        taskRepo.updateTask(currentSpace.id, taskToEdit.id, newTitle, newDueDate)
                        OngoingNotificationManager.updateOngoingNotification(context)
                    }
                },
                onDeleteTask = {
                    scope.launch {
                        taskRepo.deleteTask(currentSpace.id, taskToEdit.id)
                        OngoingNotificationManager.updateOngoingNotification(context)
                    }
                }
            )
        }
    }

    // 할 일 추가 바텀시트
    if (showAddSheet && currentSpace != null) {
        AddTaskBottomSheet(
            theme = currentTheme,
            onDismiss = { showAddSheet = false },
            onAddTask = { title, dueDate, isDaily ->
                scope.launch {
                    if (isDaily) {
                        // 매일 반복 루틴으로 등록 (나와 상대 각각 체크)
                        taskRepo.addRoutine(currentSpace.id, title)
                        taskRepo.syncRoutinesFromServer(currentSpace.id)
                    } else {
                        taskRepo.addTask(currentSpace.id, title, dueDate)
                    }
                    OngoingNotificationManager.updateOngoingNotification(context)
                }
            }
        )
    }

    // 4자리 초대 코드 확인 다이얼로그
    if (showInviteDialog && generatedCode != null) {
        val roomTitle = currentSpace?.title ?: "우리 공간"
        AlertDialog(
            onDismissRequest = { showInviteDialog = false },
            title = { Text("🏡 '${roomTitle}' 초대 코드", fontWeight = FontWeight.Bold) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (mySpaces.size > 1) {
                        Text("초대할 방 선택:", fontSize = 13.sp, color = Color.Gray)
                        Spacer(modifier = Modifier.height(6.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(mySpaces) { s ->
                                val isSel = s.id == currentSpace?.id
                                Box(
                                    modifier = Modifier
                                        .background(
                                            if (isSel) Color(currentTheme.accentHex) else Color(0xFFF0F0F0),
                                            RoundedCornerShape(12.dp)
                                        )
                                        .clickable {
                                            scope.launch {
                                                dataStore.setActiveSpaceId(s.id)
                                                spaceRepo.getOrRefreshInviteCode(s.id).onSuccess { c ->
                                                    generatedCode = c
                                                }
                                            }
                                        }
                                        .padding(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        s.title,
                                        fontSize = 13.sp,
                                        fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isSel) Color.White else Color(0xFF555555)
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(14.dp))
                    }

                    Text("초대받을 분의 토닥토닥 앱에서\n아래 4자리 코드를 입력해 주세요:", fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = generatedCode ?: "",
                        fontSize = 40.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 8.sp,
                        color = Color(currentTheme.accentHex)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "(코드는 10분간 유효하며, 연결 완료 시 자동으로 소멸됩니다)",
                        fontSize = 12.sp,
                        color = Color.Gray
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "👇 아직 앱이 설치되지 않았다면 링크를 전달해 주세요:",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(currentTheme.textColor)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        "https://todak-todak.onrender.com/download/app-latest.apk",
                        fontSize = 13.sp,
                        color = Color(currentTheme.accentHex)
                    )
                    Spacer(modifier = Modifier.height(14.dp))

                    // 상대방 초대 코드 입력 화면으로 전환 버튼
                    TextButton(
                        onClick = {
                            showInviteDialog = false
                            joinDialogCreating = false
                            joinError = null
                            showJoinDialog = true
                        },
                        modifier = Modifier.fillMaxWidth().background(Color(0xFFF5F5F5), RoundedCornerShape(12.dp))
                    ) {
                        Text("🔑 혹시 초대 코드를 받으셨나요? [코드 입력하기]", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(currentTheme.accentHex))
                    }
                }
            },
            confirmButton = {
                // 카톡/문자 등 공유 앱으로 [앱 설치 링크 + 초대 코드] 함께 전송
                TextButton(onClick = {
                    val shareText =
                        "🏡 토닥토닥 '${roomTitle}'에 초대합니다 🌸\n\n" +
                        "📝 4자리 초대 코드: ${generatedCode}\n" +
                        "(10분 안에 입력해 주세요)\n\n" +
                        "만약 아직 앱이 없다면 아래 링크를 눌러 먼저 설치해 주세요 ↓\n" +
                        "https://todak-todak.onrender.com/download/app-latest.apk\n\n" +
                        "설치 후 토닥토닥 앱 상단의 [초대 코드 입력(열쇠 아이콘)]에\n'${generatedCode}'를 입력하면 바로 연결돼요!"
                    val sendIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, shareText)
                    }
                    context.startActivity(
                        Intent.createChooser(sendIntent, "초대 코드 + 설치 링크 전송하기")
                    )
                }) {
                    Text("카톡/문자로 전송", color = Color(currentTheme.accentHex), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showInviteDialog = false }) {
                    Text("확인")
                }
            }
        )
    }

    // 주기적 잔소리 알림 설정 다이얼로그
    if (showReminderSettingsDialog) {
        var tempInterval by remember { mutableStateOf(reminderInterval) }
        var tempNightMute by remember { mutableStateOf(reminderNightMute) }

        AlertDialog(
            onDismissRequest = { showReminderSettingsDialog = false },
            shape = RoundedCornerShape(24.dp),
            containerColor = Color.White,
            title = {
                Text("⏰ 할 일 잔소리 알림 설정", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(currentTheme.textColor))
            },
            text = {
                Column {
                    Text(
                        "설정된 시간마다 오늘 남은 할 일이 있으면 화면 상단에 알림으로 알려드려요.",
                        fontSize = 13.sp,
                        color = Color.Gray
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    Text("알림 반복 간격", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color(currentTheme.textColor))
                    Spacer(modifier = Modifier.height(8.dp))

                    val intervalOptions = listOf(
                        0 to "끄기 (알림 받지 않음)",
                        1 to "1시간마다",
                        2 to "2시간마다 (권장 💡)",
                        3 to "3시간마다",
                        4 to "4시간마다"
                    )

                    intervalOptions.forEach { (hours, label) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { tempInterval = hours }
                                .padding(vertical = 6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(22.dp)
                                    .background(
                                        color = if (tempInterval == hours) Color(currentTheme.accentHex) else Color(0xFFE0E0E0),
                                        shape = CircleShape
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                if (tempInterval == hours) {
                                    Box(modifier = Modifier.size(8.dp).background(Color.White, CircleShape))
                                }
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = label,
                                fontSize = 14.sp,
                                fontWeight = if (tempInterval == hours) FontWeight.Bold else FontWeight.Normal,
                                color = if (tempInterval == hours) Color(currentTheme.textColor) else Color.DarkGray
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFFF7F7F7), RoundedCornerShape(12.dp))
                            .padding(12.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("야간 수면 보호 🌙", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Text("밤 10시 ~ 아침 8시 사이에는 알림을 울리지 않아요", fontSize = 12.sp, color = Color.Gray)
                        }
                        Switch(
                            checked = tempNightMute,
                            onCheckedChange = { tempNightMute = it }
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            dataStore.setReminderIntervalHours(tempInterval)
                            dataStore.setReminderNightMute(tempNightMute)
                            ReminderScheduler.scheduleReminder(context, tempInterval)
                            showReminderSettingsDialog = false
                            val msg = if (tempInterval == 0) "잔소리 알림을 껐어요" else "${tempInterval}시간마다 남은 할 일을 알려드릴게요 ⏰"
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(currentTheme.accentHex)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("설정 저장", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showReminderSettingsDialog = false }) {
                    Text("취소", color = Color.Gray)
                }
            }
        )
    }

    // 인앱 자체 업데이트 알림 다이얼로그
    // - 앱 실행 시 자동 다운로드하지 않고, 여기서 [지금 업데이트]를 눌렀을 때만 다운로드+설치
    // - 닫기/나중에는 다시 묻지 않음 (방 삭제 등 다른 작업 중 방해 금지)
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

    // 4자리 초대 코드 입력 및 새 방 생성 다이얼로그
    if (showJoinDialog) {
        JoinSpaceDialog(
            theme = currentTheme,
            initialCreating = joinDialogCreating,
            errorMessage = joinError,
            createError = createError,
            onDismiss = { showJoinDialog = false; joinError = null; createError = null },
            onJoinCode = { code ->
                joinError = null
                scope.launch {
                    val result = spaceRepo.joinSpaceByCode(code)
                    result.onSuccess { joined ->
                        dataStore.setActiveSpaceId(joined.id)
                        showJoinDialog = false
                    }.onFailure { e ->
                        joinError = e.message ?: "스페이스 연결에 실패했습니다."
                    }
                }
            },
            onCreateNew = { title, theme ->
                createError = null
                scope.launch {
                    val result = spaceRepo.createSpace(title, theme)
                    result.onSuccess { (space, code) ->
                        dataStore.setActiveSpaceId(space.id)
                        generatedCode = code
                        showJoinDialog = false
                        showInviteDialog = true
                    }.onFailure { e ->
                        // 같은 이름의 방 중복 등 서버/로컬 거부 사유 표시
                        createError = e.message ?: "방 만들기에 실패했습니다."
                    }
                }
            }
        )
    }

    // 방 삭제 요청 다이얼로그 (방 이름 길게 누르기로 열림)
    if (showDeleteDialog) {
        val target = deleteTargetSpace
        if (target != null) {
            AlertDialog(
                onDismissRequest = { showDeleteDialog = false },
                title = { Text("🗑️ 방 삭제", fontWeight = FontWeight.Bold) },
                text = {
                    Column {
                        Text("'${target.title}' 방을 삭제할까요?", fontSize = 15.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "삭제하려면 상대방의 동의가 필요해요.\n동의 요청을 보내면 상대방 앱에 확인 화면이 떠요.\n(방 안의 할 일과 루틴도 모두 삭제됩니다)",
                            fontSize = 13.sp,
                            color = Color.Gray
                        )
                        if (deleteWaiting) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("⏳ 상대방 동의를 기다리는 중...", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFFE65100))
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            scope.launch {
                                spaceRepo.requestDeleteSpace(target.id).onSuccess { deleted ->
                                    if (deleted) {
                                        showDeleteDialog = false
                                        removeDeletedSpace(target)
                                    } else {
                                        deleteWaiting = true
                                        Toast.makeText(context, "상대방에게 동의 요청을 보냈어요", Toast.LENGTH_SHORT).show()
                                        showDeleteDialog = false
                                    }
                                }.onFailure { e ->
                                    Toast.makeText(context, e.message ?: "삭제 요청에 실패했어요", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        enabled = !deleteWaiting,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935))
                    ) {
                        Text(if (deleteWaiting) "동의 대기 중" else "삭제 요청 보내기", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    if (deleteWaiting) {
                        TextButton(onClick = {
                            scope.launch {
                                spaceRepo.cancelDeleteSpace(target.id)
                                deleteWaiting = false
                                showDeleteDialog = false
                                Toast.makeText(context, "삭제 요청을 취소했어요", Toast.LENGTH_SHORT).show()
                            }
                        }) {
                            Text("요청 취소")
                        }
                    } else {
                        TextButton(onClick = { showDeleteDialog = false }) {
                            Text("닫기", color = Color.Gray)
                        }
                    }
                }
            )
        }
    }

    // 상대방이 보낸 삭제 동의 요청 다이얼로그 (폴링으로 감지)
    if (showDeleteConsentDialog) {
        val target = consentTargetSpace
        if (target != null) {
            AlertDialog(
                onDismissRequest = { showDeleteConsentDialog = false },
                title = { Text("🗑️ 방 삭제 동의 요청", fontWeight = FontWeight.Bold) },
                text = {
                    Text(
                        "상대방이 '${target.title}' 방의 삭제를 요청했어요.\n\n동의하면 이 방과 그 안의 할 일이 모두 삭제되고 되돌릴 수 없어요.",
                        fontSize = 14.sp
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showDeleteConsentDialog = false
                            scope.launch {
                                spaceRepo.requestDeleteSpace(target.id).onSuccess { deleted ->
                                    if (deleted) {
                                        removeDeletedSpace(target)
                                    }
                                }.onFailure { e ->
                                    Toast.makeText(context, e.message ?: "처리에 실패했어요", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935))
                    ) {
                        Text("동의하고 삭제", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        scope.launch {
                            spaceRepo.cancelDeleteSpace(target.id)
                            showDeleteConsentDialog = false
                            Toast.makeText(context, "삭제 요청을 거절했어요", Toast.LENGTH_SHORT).show()
                        }
                    }) {
                        Text("거절", color = Color.Gray)
                    }
                }
            )
        }
    }
}

/**
 * 개별 할 일 카드 컴포넌트 (짧게 누르면 완료 토글, 길게 누르면 수정/삭제)
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TaskCardItem(
    task: Task,
    todayDate: String,
    theme: ThemeColor,
    onToggle: () -> Unit,
    onLongClick: () -> Unit
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
                        text = if (task.dueDate.isNotBlank() && !task.isCompleted) {
                            // 뱃지에 실제 마감 날짜를 함께 표시 (예: "오늘 마감 · 9월 12일 (토)")
                            "${badge.label} · ${DateTimeUtils.formatKoreanDate(task.dueDate)}"
                        } else {
                            badge.label
                        },
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
 * 매일 반복 루틴 카드 컴포넌트
 * 상대 검토 등 복잡한 요소를 배제하고 직관적인 완료 토글 버튼 및 롱클릭 삭제 지원
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RoutineCardItem(
    routine: DailyRoutine,
    todayDate: String,
    theme: ThemeColor,
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
                Text(
                    text = "🔁 매일 반복",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(theme.accentHex)
                )

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
                    text = if (isDone) "✅ 완료했어요 (터치하여 취소)" else "먹었어요 / 완료하기",
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
    initialCreating: Boolean = false,
    errorMessage: String? = null,
    createError: String? = null,
    onDismiss: () -> Unit,
    onJoinCode: (code: String) -> Unit,
    onCreateNew: (title: String, theme: ThemeColor) -> Unit
) {
    var inputCode by remember { mutableStateOf("") }
    var newTitle by remember { mutableStateOf("") }
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
                        label = { Text("방 이름 (예: 엄마와 나)") },
                        isError = createError != null,
                        supportingText = {
                            if (createError != null) {
                                Text(createError, fontSize = 12.sp, color = Color(0xFFB3261E))
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
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
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (isCreating && newTitle.isNotBlank()) {
                        onCreateNew(newTitle, ThemeColor.GREEN)
                    } else if (!isCreating && isCodeValid) {
                        onJoinCode(inputCode)
                    }
                },
                enabled = if (isCreating) newTitle.isNotBlank() else isCodeValid,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(theme.accentHex),
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
