package com.honey.familyspace.ui

import android.content.Intent
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Key
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

    val mySpaces by spaceRepo.observeMySpaces().collectAsState()
    val activeSpaceId by dataStore.activeSpaceIdFlow.collectAsState(initial = null)
    val myNickname by dataStore.myNicknameFlow.collectAsState(initial = "나")
    val partnerNickname by dataStore.partnerNicknameFlow.collectAsState(initial = "짝꿍")

    // 초기 로딩 플래그 (로컬 캐시가 이미 있으면 즉시 화면 노출, 없으면 로딩 인디케이터)
    var isInitialLoading by remember { mutableStateOf(mySpaces.isEmpty()) }

    // 지난 완료 기록 접기/펼치기 상태
    var showPastCompleted by remember { mutableStateOf(false) }

    // 전체보기 모드: 참여 방이 2개 이상이고 "ALL"이 선택되었을 때
    val isAllMode = (activeSpaceId == "ALL") && mySpaces.size > 1
    val currentSpace = if (isAllMode) mySpaces.firstOrNull() else (mySpaces.find { it.id == activeSpaceId } ?: mySpaces.firstOrNull())
    val currentTheme = if (isAllMode) ThemeColor.LAVENDER else (currentSpace?.getTheme() ?: ThemeColor.CORAL)

    val tasks by if (isAllMode) {
        taskRepo.observeAllTasks(mySpaces.map { it.id }).collectAsState(initial = emptyList())
    } else if (currentSpace != null) {
        taskRepo.observeTasks(currentSpace.id).collectAsState(initial = emptyList())
    } else {
        remember { mutableStateOf(emptyList<Task>()) }
    }

    val routines by if (isAllMode) {
        taskRepo.observeAllRoutines(mySpaces.map { it.id }).collectAsState(initial = emptyList())
    } else if (currentSpace != null) {
        taskRepo.observeRoutines(currentSpace.id).collectAsState(initial = emptyList())
    } else {
        remember { mutableStateOf(emptyList<DailyRoutine>()) }
    }

    val todayDate = DateTimeUtils.getTodayDateString()
    var showAddSheet by remember { mutableStateOf(false) }
    var showInviteDialog by remember { mutableStateOf(false) }
    var showMemberLimitDialog by remember { mutableStateOf(false) }
    var showJoinDialog by remember { mutableStateOf(false) }
    var joinDialogCreating by remember { mutableStateOf(false) }
    var joinError by remember { mutableStateOf<String?>(null) }
    var createError by remember { mutableStateOf<String?>(null) }
    var generatedCode by remember { mutableStateOf<String?>(null) }
    var updateInfo by remember { mutableStateOf<AppUpdateManager.UpdateInfo?>(null) }
    var editingTask by remember { mutableStateOf<Task?>(null) }

    // 롱클릭 액션 및 삭제 확인 상태 (무단 삭제 방지)
    var actionTargetTask by remember { mutableStateOf<Task?>(null) }
    var actionTargetRoutine by remember { mutableStateOf<DailyRoutine?>(null) }
    var taskToDelete by remember { mutableStateOf<Task?>(null) }
    var routineToDelete by remember { mutableStateOf<DailyRoutine?>(null) }
    var editingRoutine by remember { mutableStateOf<DailyRoutine?>(null) }
    var routineNewTitle by remember { mutableStateOf("") }

    // 방 관리 액션 (수정 / 삭제 통합) 및 다이얼로그 상태
    var actionTargetSpace by remember { mutableStateOf<Space?>(null) }
    var showSpaceActionDialog by remember { mutableStateOf(false) }
    var singleDeleteTargetSpace by remember { mutableStateOf<Space?>(null) }
    var showSingleDeleteDialog by remember { mutableStateOf(false) }

    // 방 테마 색상 변경 다이얼로그 상태
    var showThemeDialog by remember { mutableStateOf(false) }
    var themeTargetSpace by remember { mutableStateOf<Space?>(null) }

    // 방 이름 변경 다이얼로그 상태
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameTargetSpace by remember { mutableStateOf<Space?>(null) }
    var renameInputText by remember { mutableStateOf("") }

    // 화면 새로고침 상태
    var isRefreshing by remember { mutableStateOf(false) }

    // 현재 앱 버전 (실제 설치된 APK 버전 정보 실시간 조회)
    val appVersion = remember {
        try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            "v${pInfo.versionName ?: "1.3.9"}"
        } catch (e: Exception) {
            "v1.3.9"
        }
    }

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

    // 수동 새로고침 함수 (쓸어내리기 및 상단 새로고침 버튼용)
    val triggerRefresh: () -> Unit = {
        if (!isRefreshing) {
            isRefreshing = true
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            scope.launch {
                try {
                    spaceRepo.syncSpacesFromServer()
                    mySpaces.forEach { s ->
                        taskRepo.syncTasksFromServer(s.id)
                        taskRepo.syncRoutinesFromServer(s.id)
                    }
                    Toast.makeText(context, "새로고침 완료 💖", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    // 오류 무시
                } finally {
                    delay(500)
                    isRefreshing = false
                }
            }
        }
    }

    // 앱 실행 시 백엔드 서버에 새 버전(업데이트) 확인 및 내 방 목록 동기화 (화면 렌더링을 차단하지 않고 병렬 실행)
    LaunchedEffect(Unit) {
        launch {
            try {
                val info = AppUpdateManager.checkForUpdate(context)
                if (info != null && info.hasUpdate) {
                    updateInfo = info
                }
            } catch (e: Exception) {}
        }
        launch {
            try {
                spaceRepo.syncSpacesFromServer()
            } finally {
                isInitialLoading = false
            }
        }
        ReminderScheduler.scheduleReminder(context, reminderInterval)
    }

    // 🌟 3초 주기 실시간 자동 동기화 루프 (상대방 작성/체크 즉각 반영)
    LaunchedEffect(mySpaces) {
        while (true) {
            try {
                mySpaces.forEach { space ->
                    taskRepo.syncTasksFromServer(space.id)
                    taskRepo.syncRoutinesFromServer(space.id)
                }
            } catch (e: Exception) {
                // 네트워크 일시 불안정 시 다음 주기에 재시도
            }
            delay(3000)
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
                .pointerInput(Unit) {
                    detectVerticalDragGestures { _, dragAmount ->
                        // 화면을 위에서 아래로 50픽셀 이상 쓸어내리면 즉시 새로고침
                        if (dragAmount > 50) {
                            triggerRefresh()
                        }
                    }
                }
        ) {
            // 새로고침 진행 표시줄
            if (isRefreshing) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp),
                    color = Color(currentTheme.accentHex)
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            // 1. 적응형 상단 헤더 (방이 1개일 땐 단일 헤더, 2개 이상일 땐 알약 탭)
            if (mySpaces.size <= 1) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f, fill = false)
                    ) {
                        Text(
                            text = "🏠 ${currentSpace?.title ?: "우리 공간"}",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color(currentTheme.textColor),
                            modifier = Modifier.combinedClickable(
                                onClick = {
                                    // 방 이름 터치 시 방 이름 변경 팝업
                                    currentSpace?.let { space ->
                                        renameTargetSpace = space
                                        renameInputText = space.title
                                        showRenameDialog = true
                                    }
                                },
                                onLongClick = {
                                    // 방 이름 길게 누르기 → 방 관리(수정/삭제) 통합 팝업
                                    currentSpace?.let { space ->
                                        actionTargetSpace = space
                                        showSpaceActionDialog = true
                                    }
                                }
                            )
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // 0. 즉시 새로고침 버튼 (원터치 갱신)
                        IconButton(onClick = triggerRefresh) {
                            Icon(Icons.Default.Refresh, contentDescription = "새로고침", tint = Color(currentTheme.accentHex))
                        }

                        // 1. 초대 코드 입력 버튼
                        IconButton(onClick = {
                            joinDialogCreating = false
                            joinError = null
                            showJoinDialog = true
                        }) {
                            Icon(Icons.Default.Key, contentDescription = "초대 코드 입력", tint = Color(currentTheme.accentHex))
                        }

                        // 2. 방 초대하기 버튼 (2명 한정 원칙 검증)
                        if (currentSpace != null) {
                            IconButton(onClick = {
                                if (currentSpace.memberCount >= 2) {
                                    showMemberLimitDialog = true
                                } else {
                                    scope.launch {
                                        spaceRepo.getOrRefreshInviteCode(currentSpace.id).onSuccess { code ->
                                            generatedCode = code
                                            showInviteDialog = true
                                        }
                                    }
                                }
                            }) {
                                Icon(Icons.Default.PersonAdd, contentDescription = "초대하기", tint = Color(currentTheme.accentHex))
                            }
                        }

                        // 3. 리마인더 알림 설정 버튼
                        IconButton(onClick = { showReminderSettingsDialog = true }) {
                            Icon(Icons.Default.Settings, contentDescription = "알림 설정", tint = Color(currentTheme.accentHex))
                        }
                    }
                }
            } else {
                // 다중 스페이스 알약 탭 (맨 앞에 [🌈 전체보기] 탭 제공)
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // 🌈 전체보기 탭 (방 2개 이상일 때)
                    item {
                        val isSelected = isAllMode
                        Box(
                            modifier = Modifier
                                .background(
                                    color = if (isSelected) Color(0xFF673AB7) else Color.White,
                                    shape = RoundedCornerShape(20.dp)
                                )
                                .clickable {
                                    scope.launch { dataStore.setActiveSpaceId("ALL") }
                                }
                                .padding(horizontal = 18.dp, vertical = 10.dp)
                        ) {
                            Text(
                                text = "🌈 전체보기",
                                fontSize = 15.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) Color.White else Color(0xFF666666)
                            )
                        }
                    }

                    items(mySpaces) { space ->
                        val isSelected = !isAllMode && space.id == currentSpace?.id
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
                                        // 알약 길게 누르기 → 해당 방 관리(수정/삭제) 통합 팝업
                                        actionTargetSpace = space
                                        showSpaceActionDialog = true
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

                    // 상단 원터치 새로고침 버튼
                    item {
                        IconButton(onClick = triggerRefresh) {
                            Icon(Icons.Default.Refresh, contentDescription = "새로고침", tint = Color(currentTheme.accentHex))
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
                                if (space.memberCount >= 2) {
                                    showMemberLimitDialog = true
                                } else {
                                    scope.launch {
                                        spaceRepo.getOrRefreshInviteCode(space.id).onSuccess { code ->
                                            generatedCode = code
                                            showInviteDialog = true
                                        }
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

            // 스페이스가 전혀 없는 초기 상태 (Cold Start 로딩 중일 때는 로딩 바 표시하여 깜빡임 방지)
            if (mySpaces.isEmpty()) {
                if (isInitialLoading) {
                    Box(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        LinearProgressIndicator(
                            modifier = Modifier.width(120.dp),
                            color = Color(currentTheme.accentHex)
                        )
                    }
                } else {
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
                }
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

            // 4. 목록 (매일 반복 루틴 카드 + 일반 할 일 카드 + 지난 완료 기록 아코디언)
            val activeTasks = tasks.filter { !it.isCompleted }
            val completedTasks = tasks.filter { it.isCompleted }

            if (activeTasks.isEmpty() && routines.isEmpty() && completedTasks.isEmpty()) {
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
                        val spaceTitle = if (isAllMode) mySpaces.find { it.id == routine.spaceId }?.title else null
                        RoutineCardItem(
                            routine = routine,
                            todayDate = todayDate,
                            theme = currentTheme,
                            spaceBadge = spaceTitle,
                            onCheck = {
                                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                scope.launch {
                                    taskRepo.checkRoutineDone(routine.spaceId, routine.id)
                                    taskRepo.syncRoutinesFromServer(routine.spaceId)
                                    OngoingNotificationManager.updateOngoingNotification(context)
                                }
                            },
                            onLongClick = {
                                // 롱클릭 시 바로 삭제하지 않고 [수정/삭제] 선택 다이얼로그 표시
                                actionTargetRoutine = routine
                            }
                        )
                    }

                    // 진행 중인 일반 할 일 카드 목록
                    items(activeTasks) { task ->
                        val spaceTitle = if (isAllMode) mySpaces.find { it.id == task.spaceId }?.title else null
                        TaskCardItem(
                            task = task,
                            todayDate = todayDate,
                            theme = currentTheme,
                            spaceBadge = spaceTitle,
                            onToggle = {
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                scope.launch {
                                    taskRepo.toggleTask(task.spaceId, task.id, task.isCompleted)
                                    OngoingNotificationManager.updateOngoingNotification(context)
                                }
                            },
                            onLongClick = {
                                // 롱클릭 시 [수정/삭제] 선택 다이얼로그 표시
                                actionTargetTask = task
                            }
                        )
                    }

                    // 📂 지난 완료 기록 접기/펼치기 아코디언 섹션
                    if (completedTasks.isNotEmpty()) {
                        item {
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color.White.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
                                    .clickable { showPastCompleted = !showPastCompleted }
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "📂 지난 완료 기록 (${completedTasks.size}개)",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.Gray
                                )
                                Text(
                                    text = if (showPastCompleted) "접기 ▲" else "펼치기 ▼",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(currentTheme.accentHex)
                                )
                            }
                        }

                        if (showPastCompleted) {
                            items(completedTasks) { task ->
                                val spaceTitle = if (isAllMode) mySpaces.find { it.id == task.spaceId }?.title else null
                                TaskCardItem(
                                    task = task,
                                    todayDate = todayDate,
                                    theme = currentTheme,
                                    spaceBadge = spaceTitle,
                                    onToggle = {
                                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                        scope.launch {
                                            taskRepo.toggleTask(task.spaceId, task.id, task.isCompleted)
                                            OngoingNotificationManager.updateOngoingNotification(context)
                                        }
                                    },
                                    onLongClick = {
                                        actionTargetTask = task
                                    }
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 5. 하단 참여 상태 표시 (1인: '👤 혼자' vs 2인: 애칭 표기)
            if (currentSpace != null && !isAllMode) {
                val isAlone = currentSpace.memberCount <= 1
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isAlone) {
                        Surface(
                            color = Color.Black.copy(alpha = 0.05f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = "👤 혼자",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color.Gray,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    } else {
                        val myName = myNickname.ifBlank { "나" }
                        val partnerName = partnerNickname.ifBlank { "짝꿍" }
                        Surface(
                            color = Color(currentTheme.accentHex).copy(alpha = 0.12f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = "👥 $myName & $partnerName 함께 공유 중 💖",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(currentTheme.accentHex),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
            }

            Text(
                text = "토닥토닥 $appVersion",
                fontSize = 11.sp,
                color = Color.Gray.copy(alpha = 0.45f),
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
        }
    }

    // 할 일 수정 및 삭제 바텀시트
    editingTask?.let { taskToEdit ->
        EditTaskBottomSheet(
            task = taskToEdit,
            theme = currentTheme,
            onDismiss = { editingTask = null },
            onUpdateTask = { newTitle, newDueDate, newAlarmTime, newHasAlarm ->
                scope.launch {
                    taskRepo.updateTask(taskToEdit.spaceId, taskToEdit.id, newTitle, newDueDate, newAlarmTime, newHasAlarm)
                    OngoingNotificationManager.updateOngoingNotification(context)
                }
            },
            onDeleteTask = {
                // 바로 삭제하지 않고 삭제 확인 다이얼로그 팝업
                taskToDelete = taskToEdit
                editingTask = null
            }
        )
    }

    // 할 일 추가 바텀시트
    if (showAddSheet) {
        val targetSpaceId = if (isAllMode) (mySpaces.firstOrNull()?.id ?: "") else (currentSpace?.id ?: "")
        if (targetSpaceId.isNotBlank()) {
            AddTaskBottomSheet(
                theme = currentTheme,
                onDismiss = { showAddSheet = false },
                onAddTask = { title, dueDate, isDaily, alarmTime, hasAlarm ->
                    scope.launch {
                        if (isDaily) {
                            taskRepo.addRoutine(targetSpaceId, title)
                            taskRepo.syncRoutinesFromServer(targetSpaceId)
                        } else {
                            taskRepo.addTask(targetSpaceId, title, dueDate, alarmTime, hasAlarm)
                            taskRepo.syncTasksFromServer(targetSpaceId)
                        }
                        OngoingNotificationManager.updateOngoingNotification(context)
                    }
                }
            )
        }
    }

    // 🌟 [할 일] 롱클릭 시 작업 선택 다이얼로그 (수정 vs 삭제)
    actionTargetTask?.let { targetTask ->
        AlertDialog(
            onDismissRequest = { actionTargetTask = null },
            title = { Text("선택: ${targetTask.title}", fontWeight = FontWeight.Bold) },
            text = { Text("이 할 일에 대해 수행할 작업을 선택해 주세요.") },
            confirmButton = {
                Button(
                    onClick = {
                        editingTask = targetTask
                        actionTargetTask = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(currentTheme.accentHex))
                ) {
                    Text("✏️ 수정하기", color = Color.White)
                }
            },
            dismissButton = {
                Button(
                    onClick = {
                        taskToDelete = targetTask
                        actionTargetTask = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))
                ) {
                    Text("🗑️ 삭제하기", color = Color.White)
                }
            }
        )
    }

    // 🌟 [루틴] 롱클릭 시 작업 선택 다이얼로그 (이름 수정 vs 삭제)
    actionTargetRoutine?.let { targetRoutine ->
        AlertDialog(
            onDismissRequest = { actionTargetRoutine = null },
            title = { Text("선택: ${targetRoutine.title}", fontWeight = FontWeight.Bold) },
            text = { Text("이 매일 루틴에 대해 수행할 작업을 선택해 주세요.") },
            confirmButton = {
                Button(
                    onClick = {
                        editingRoutine = targetRoutine
                        routineNewTitle = targetRoutine.title
                        actionTargetRoutine = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(currentTheme.accentHex))
                ) {
                    Text("✏️ 이름 수정하기", color = Color.White)
                }
            },
            dismissButton = {
                Button(
                    onClick = {
                        routineToDelete = targetRoutine
                        actionTargetRoutine = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))
                ) {
                    Text("🗑️ 삭제하기", color = Color.White)
                }
            }
        )
    }

    // 🌟 [할 일 삭제 확인 다이얼로그] - 실수로 지우는 일 방지
    taskToDelete?.let { t ->
        AlertDialog(
            onDismissRequest = { taskToDelete = null },
            title = { Text("⚠️ 할 일 삭제", fontWeight = FontWeight.Bold) },
            text = { Text("'${t.title}' 할 일을 정말 삭제하시겠습니까?\n삭제된 할 일은 복구할 수 없습니다.") },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            taskRepo.deleteTask(t.spaceId, t.id)
                            OngoingNotificationManager.updateOngoingNotification(context)
                            Toast.makeText(context, "'${t.title}' 할 일을 삭제했어요", Toast.LENGTH_SHORT).show()
                        }
                        taskToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))
                ) {
                    Text("삭제", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { taskToDelete = null }) {
                    Text("취소")
                }
            }
        )
    }

    // 🌟 [루틴 삭제 확인 다이얼로그] - 실수로 지우는 일 방지
    routineToDelete?.let { r ->
        AlertDialog(
            onDismissRequest = { routineToDelete = null },
            title = { Text("⚠️ 매일 루틴 삭제", fontWeight = FontWeight.Bold) },
            text = { Text("'${r.title}' 매일 루틴을 정말 삭제하시겠습니까?\n삭제하시면 두 분 모두의 목록에서 완전히 사라집니다.") },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            taskRepo.deleteRoutine(r.spaceId, r.id)
                            OngoingNotificationManager.updateOngoingNotification(context)
                            Toast.makeText(context, "'${r.title}' 루틴을 삭제했어요", Toast.LENGTH_SHORT).show()
                        }
                        routineToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))
                ) {
                    Text("삭제", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { routineToDelete = null }) {
                    Text("취소")
                }
            }
        )
    }

    // 🌟 [루틴 이름 수정 다이얼로그]
    editingRoutine?.let { r ->
        AlertDialog(
            onDismissRequest = { editingRoutine = null },
            title = { Text("✏️ 매일 루틴 이름 수정", fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = routineNewTitle,
                    onValueChange = { if (it.length <= 50) routineNewTitle = it },
                    label = { Text("루틴 이름 (최대 50자)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (routineNewTitle.isNotBlank()) {
                            scope.launch {
                                taskRepo.updateRoutineTitle(r.spaceId, r.id, routineNewTitle)
                                taskRepo.syncRoutinesFromServer(r.spaceId)
                                Toast.makeText(context, "루틴 이름을 수정했어요", Toast.LENGTH_SHORT).show()
                            }
                        }
                        editingRoutine = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(currentTheme.accentHex))
                ) {
                    Text("저장", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { editingRoutine = null }) {
                    Text("취소")
                }
            }
        )
    }

    // 🌟 [방 길게 누르기 관리 통합 다이얼로그 (수정 / 삭제 / 색상 변경)]
    if (showSpaceActionDialog && actionTargetSpace != null) {
        val target = actionTargetSpace!!
        AlertDialog(
            onDismissRequest = { showSpaceActionDialog = false },
            title = { Text("🏠 '${target.title}' 방 관리", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("수행할 작업을 선택해 주세요.", fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(14.dp))
                    Button(
                        onClick = {
                            renameTargetSpace = target
                            renameInputText = target.title
                            showRenameDialog = true
                            showSpaceActionDialog = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(currentTheme.accentHex))
                    ) {
                        Text("✏️ 방 이름 수정", color = Color.White)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            themeTargetSpace = target
                            showThemeDialog = true
                            showSpaceActionDialog = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5C6BC0))
                    ) {
                        Text("🎨 방 테마 색상 변경", color = Color.White)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            showSpaceActionDialog = false
                            if (target.memberCount <= 1) {
                                // 혼자 있는 방이면 즉시 삭제 확인 다이얼로그로
                                singleDeleteTargetSpace = target
                                showSingleDeleteDialog = true
                            } else {
                                // 2명 이상이면 상대방 동의 요청 다이얼로그로
                                deleteTargetSpace = target
                                showDeleteDialog = true
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))
                    ) {
                        Text("🗑️ 방 삭제", color = Color.White)
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showSpaceActionDialog = false }) {
                    Text("닫기", color = Color.Gray)
                }
            }
        )
    }

    // 🌟 [1인 방 즉시 삭제 확인 다이얼로그]
    if (showSingleDeleteDialog && singleDeleteTargetSpace != null) {
        val target = singleDeleteTargetSpace!!
        AlertDialog(
            onDismissRequest = { showSingleDeleteDialog = false },
            title = { Text("🗑️ 혼자 있는 방 삭제", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "'${target.title}' 방은 혼자 사용하는 방입니다.\n" +
                    "삭제 시 상대방 동의 없이 즉시 삭제되며,\n방 안의 할 일과 루틴도 모두 삭제됩니다.\n\n정말 삭제하시겠습니까?",
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showSingleDeleteDialog = false
                        scope.launch {
                            spaceRepo.deleteSpaceImmediately(target.id).onSuccess {
                                removeDeletedSpace(target)
                            }.onFailure { e ->
                                Toast.makeText(context, e.message ?: "삭제 실패", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))
                ) {
                    Text("삭제", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSingleDeleteDialog = false }) {
                    Text("취소", color = Color.Gray)
                }
            }
        )
    }

    // 🌟 [방 이름 수정 다이얼로그]
    if (showRenameDialog && renameTargetSpace != null) {
        val target = renameTargetSpace!!
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("✏️ 방 이름 수정", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("새로운 방 이름을 입력해 주세요:", fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = renameInputText,
                        onValueChange = { if (it.length <= 20) renameInputText = it },
                        label = { Text("방 이름 (예: 우리 부부, 딸내미)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val trimmed = renameInputText.trim()
                        if (trimmed.isNotBlank()) {
                            val isDuplicate = mySpaces.any { it.id != target.id && it.title.trim() == trimmed }
                            if (isDuplicate) {
                                Toast.makeText(context, "이미 존재하는 방 이름입니다.", Toast.LENGTH_SHORT).show()
                            } else {
                                scope.launch {
                                    spaceRepo.updateSpaceTitle(target.id, trimmed)
                                    spaceRepo.syncSpacesFromServer()
                                    Toast.makeText(context, "방 이름을 '${trimmed}'(으)로 변경했어요 🌸", Toast.LENGTH_SHORT).show()
                                }
                                showRenameDialog = false
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(currentTheme.accentHex))
                ) {
                    Text("저장", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text("취소")
                }
            }
        )
    }

    // 🌟 [방 테마 색상 변경 다이얼로그]
    if (showThemeDialog && themeTargetSpace != null) {
        val target = themeTargetSpace!!
        var selectedTheme by remember(target) { mutableStateOf(target.getTheme()) }

        AlertDialog(
            onDismissRequest = { showThemeDialog = false },
            title = { Text("🎨 '${target.title}' 방 색상 변경", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("원하는 방 테마 색상을 선택해 주세요:", fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(14.dp))
                    ThemeColorPaletteSelector(
                        selectedTheme = selectedTheme,
                        onSelectTheme = { selectedTheme = it }
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            spaceRepo.updateSpaceTheme(target.id, selectedTheme)
                            Toast.makeText(context, "'${target.title}' 방 색상을 ${selectedTheme.displayName}(으)로 변경했어요 🌸", Toast.LENGTH_SHORT).show()
                        }
                        showThemeDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(selectedTheme.accentHex))
                ) {
                    Text("적용하기", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showThemeDialog = false }) {
                    Text("취소", color = Color.Gray)
                }
            }
        )
    }

    // 🌟 [1:1 방 2명 한정 안내 다이얼로그]
    if (showMemberLimitDialog) {
        AlertDialog(
            onDismissRequest = { showMemberLimitDialog = false },
            title = { Text("👥 1:1 방 인원 안내", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "⚠️ '${currentSpace?.title}' 방은 이미 2명이 함께하고 있어요.\n\n" +
                    "토닥토닥은 둘만의 약속을 소중히 지키기 위한 1:1 공간으로 설계되었습니다.\n\n" +
                    "다른 가족이나 친구와 함께하시려면 상단의 [+] 버튼을 눌러 새로운 방을 만들어 초대해 주세요! 🌸",
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = { showMemberLimitDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(currentTheme.accentHex))
                ) {
                    Text("확인", color = Color.White)
                }
            }
        )
    }

    // 4자리 초대 코드 확인 다이얼로그 (유효시간 30분 & '방이름 함께하기')
    if (showInviteDialog && generatedCode != null) {
        val roomTitle = currentSpace?.title ?: "우리 공간"
        AlertDialog(
            onDismissRequest = { showInviteDialog = false },
            title = { Text("💌 '${roomTitle}' 함께하기", fontWeight = FontWeight.Bold) },
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
                        "(⏳ 코드는 30분간 유효하며, 연결 완료 시 자동으로 소멸됩니다)",
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
                        "(30분 안에 입력해 주세요)\n\n" +
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

    // 주기적 잔소리 알림 및 애칭 설정 다이얼로그
    if (showReminderSettingsDialog) {
        var tempInterval by remember { mutableStateOf(reminderInterval) }
        var tempNightMute by remember { mutableStateOf(reminderNightMute) }
        var tempMyNick by remember { mutableStateOf(myNickname) }
        var tempPartnerNick by remember { mutableStateOf(partnerNickname) }

        AlertDialog(
            onDismissRequest = { showReminderSettingsDialog = false },
            shape = RoundedCornerShape(24.dp),
            containerColor = Color.White,
            title = {
                Text("⚙️ 공간 및 알림 설정", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(currentTheme.textColor))
            },
            text = {
                Column {
                    Text("우리 애칭 설정 💖", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color(currentTheme.textColor))
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("서로를 부를 애칭을 적어주시면 화면에 예쁘게 표시돼요.", fontSize = 12.sp, color = Color.Gray)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = tempMyNick,
                            onValueChange = { if (it.length <= 10) tempMyNick = it },
                            label = { Text("내 애칭") },
                            placeholder = { Text("예: 나, 남편, 허니") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        OutlinedTextField(
                            value = tempPartnerNick,
                            onValueChange = { if (it.length <= 10) tempPartnerNick = it },
                            label = { Text("상대방 애칭") },
                            placeholder = { Text("예: 짝꿍, 아내, 공주") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text("⏰ 할 일 잔소리 알림 반복 간격", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color(currentTheme.textColor))
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "설정된 시간마다 오늘 남은 할 일이 있으면 화면 상단에 알림으로 알려드려요.",
                        fontSize = 12.sp,
                        color = Color.Gray
                    )
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
                                .padding(vertical = 5.dp)
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

                    Spacer(modifier = Modifier.height(14.dp))

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
                            dataStore.setMyNickname(tempMyNick.trim())
                            dataStore.setPartnerNickname(tempPartnerNick.trim())
                            dataStore.setReminderIntervalHours(tempInterval)
                            dataStore.setReminderNightMute(tempNightMute)
                            ReminderScheduler.scheduleReminder(context, tempInterval)
                            showReminderSettingsDialog = false
                            val msg = if (tempInterval == 0) "설정을 저장했어요" else "${tempInterval}시간마다 남은 할 일을 알려드릴게요 ⏰"
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
private fun RoutineCardItem(
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
 * 5종 감성 파스텔 테마 컬러 팔레트 선택 컴포넌트
 */
@Composable
private fun ThemeColorPaletteSelector(
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
