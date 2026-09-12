package com.honey.familyspace.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.honey.familyspace.data.DataStoreManager
import com.honey.familyspace.data.SpaceRepository
import com.honey.familyspace.data.TaskRepository
import com.honey.familyspace.model.DailyRoutine
import com.honey.familyspace.model.Task
import com.honey.familyspace.model.ThemeColor
import com.honey.familyspace.notification.OngoingNotificationManager
import com.honey.familyspace.ui.MainActivity
import com.honey.familyspace.util.DateTimeUtils
import kotlinx.coroutines.flow.firstOrNull

/**
 * 컴맹 아내 맞춤형 4x3 대형 홈 화면 위젯 (Jetpack Glance 기반)
 */
class FamilySpaceWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val dataStore = DataStoreManager(context)
        val activeSpaceId = dataStore.activeSpaceIdFlow.firstOrNull() ?: ""

        val taskRepo = TaskRepository()
        val spaceRepo = SpaceRepository()
        val todayString = DateTimeUtils.getTodayDateString()

        val mySpaces = spaceRepo.observeMySpaces().firstOrNull() ?: emptyList()
        val currentSpace = mySpaces.find { it.id == activeSpaceId } ?: mySpaces.firstOrNull()
        val theme = currentSpace?.getTheme() ?: ThemeColor.CORAL

        val tasks = if (currentSpace != null) {
            taskRepo.observeTasks(currentSpace.id).firstOrNull() ?: emptyList()
        } else emptyList()

        val routines = if (currentSpace != null) {
            taskRepo.observeRoutines(currentSpace.id).firstOrNull() ?: emptyList()
        } else emptyList()

        val todayTasks = tasks.filter { it.dueDate.isBlank() || it.dueDate <= todayString }
        val routine = routines.firstOrNull()

        provideContent {
            WidgetContent(
                context = context,
                spaceTitle = currentSpace?.title ?: "우리 공간",
                theme = theme,
                routine = routine,
                tasks = todayTasks,
                todayString = todayString,
                activeSpaceId = currentSpace?.id ?: ""
            )
        }
    }

    @Composable
    private fun WidgetContent(
        context: Context,
        spaceTitle: String,
        theme: ThemeColor,
        routine: DailyRoutine?,
        tasks: List<Task>,
        todayString: String,
        activeSpaceId: String
    ) {
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(Color(theme.backgroundHex))
                .cornerRadius(24.dp)
                .padding(16.dp)
        ) {
            // 1. 위젯 상단 헤더 (방 이름 & 남은 할 일 수)
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "🏠 $spaceTitle",
                    style = TextStyle(
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = androidx.glance.unit.ColorProvider(Color(theme.textColor))
                    ),
                    modifier = GlanceModifier.defaultWeight()
                )

                val uncompletedCount = tasks.count { !it.isCompleted }
                val summaryText = if (uncompletedCount == 0) "모두 완료! 💖" else "${uncompletedCount}개 남음"
                Text(
                    text = summaryText,
                    style = TextStyle(
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = androidx.glance.unit.ColorProvider(Color(theme.accentHex))
                    )
                )
            }

            Spacer(modifier = GlanceModifier.height(10.dp))

            // 2. 안심 루틴 카드 (약 먹기 원터치 체크 버튼)
            if (routine != null) {
                val isDone = routine.isCompletedToday(todayString)
                val routineBg = if (isDone) Color(0xFFE8F5E9) else Color(theme.accentHex)
                val routineTextColor = if (isDone) Color(0xFF2E7D32) else Color.White
                val statusText = if (isDone) {
                    "✅ ${routine.title}: ${routine.lastCompletedTime} 복용 완료"
                } else {
                    "💊 ${routine.title}: 먹었어요! (탭하여 완료)"
                }

                Box(
                    modifier = GlanceModifier
                        .fillMaxWidth()
                        .background(routineBg)
                        .cornerRadius(14.dp)
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                        .clickable(
                            actionRunCallback<ToggleRoutineActionCallback>(
                                actionParametersOf(
                                    ActionParameters.Key<String>("spaceId") to activeSpaceId,
                                    ActionParameters.Key<String>("routineId") to routine.id
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = statusText,
                        style = TextStyle(
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = androidx.glance.unit.ColorProvider(routineTextColor)
                        )
                    )
                }

                Spacer(modifier = GlanceModifier.height(10.dp))
            }

            // 3. 오늘 할 일 리스트 (최대 3개 노출)
            if (tasks.isEmpty()) {
                Box(
                    modifier = GlanceModifier
                        .fillMaxWidth()
                        .defaultWeight(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "오늘 챙길 할 일이 없어요 ☀️",
                        style = TextStyle(
                            fontSize = 14.sp,
                            color = androidx.glance.unit.ColorProvider(Color(0xFF757575))
                        )
                    )
                }
            } else {
                LazyColumn(
                    modifier = GlanceModifier
                        .fillMaxWidth()
                        .defaultWeight()
                ) {
                    items(tasks.take(3)) { task ->
                        TaskItemRow(task, activeSpaceId, theme)
                        Spacer(modifier = GlanceModifier.height(6.dp))
                    }
                }
            }

            Spacer(modifier = GlanceModifier.height(6.dp))

            // 4. 하단 원터치 앱 열기 / 할 일 추가 버튼
            val openAppIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }

            Row(
                modifier = GlanceModifier
                    .fillMaxWidth()
                    .clickable(actionStartActivity(openAppIntent)),
                horizontalAlignment = Alignment.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = GlanceModifier
                        .background(Color(theme.accentHex))
                        .cornerRadius(12.dp)
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "+ 할 일 추가 / 앱 열기",
                        style = TextStyle(
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = androidx.glance.unit.ColorProvider(Color.White)
                        )
                    )
                }
            }
        }
    }

    @Composable
    private fun TaskItemRow(task: Task, spaceId: String, theme: ThemeColor) {
        val checkIcon = if (task.isCompleted) "☑" else "☐"
        val textColor = if (task.isCompleted) Color(0xFF9E9E9E) else Color(theme.textColor)

        Row(
            modifier = GlanceModifier
                .fillMaxWidth()
                .background(Color.White)
                .cornerRadius(12.dp)
                .padding(horizontal = 10.dp, vertical = 8.dp)
                .clickable(
                    actionRunCallback<ToggleTaskActionCallback>(
                        actionParametersOf(
                            ActionParameters.Key<String>("spaceId") to spaceId,
                            ActionParameters.Key<String>("taskId") to task.id,
                            ActionParameters.Key<Boolean>("status") to task.isCompleted
                        )
                    )
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = checkIcon,
                style = TextStyle(
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = androidx.glance.unit.ColorProvider(Color(theme.accentHex))
                )
            )

            Spacer(modifier = GlanceModifier.width(8.dp))

            Text(
                text = task.title,
                style = TextStyle(
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = androidx.glance.unit.ColorProvider(textColor)
                ),
                modifier = GlanceModifier.defaultWeight()
            )
        }
    }
}

/**
 * 위젯 내 약 복용 클릭 시 백그라운드 처리 콜백
 */
class ToggleRoutineActionCallback : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        val spaceId = parameters[ActionParameters.Key<String>("spaceId")] ?: return
        val routineId = parameters[ActionParameters.Key<String>("routineId")] ?: return

        val repo = TaskRepository()
        repo.checkRoutineDone(spaceId, routineId)

        // 위젯 및 상단바 알림 즉시 갱신
        FamilySpaceWidget().update(context, glanceId)
        OngoingNotificationManager.updateOngoingNotification(context)
    }
}

/**
 * 위젯 내 할 일 체크 클릭 시 백그라운드 처리 콜백
 */
class ToggleTaskActionCallback : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        val spaceId = parameters[ActionParameters.Key<String>("spaceId")] ?: return
        val taskId = parameters[ActionParameters.Key<String>("taskId")] ?: return
        val status = parameters[ActionParameters.Key<Boolean>("status")] ?: false

        val repo = TaskRepository()
        repo.toggleTask(spaceId, taskId, status)

        // 위젯 및 상단바 알림 즉시 갱신
        FamilySpaceWidget().update(context, glanceId)
        OngoingNotificationManager.updateOngoingNotification(context)
    }
}
