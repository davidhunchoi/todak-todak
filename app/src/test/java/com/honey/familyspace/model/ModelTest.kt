package com.honey.familyspace.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 데이터 모델 및 비즈니스 로직 단위 테스트
 */
class ModelTest {

    @Test
    fun `매일_루틴_오늘_완료_및_자정_자동_리셋_검증`() {
        val routine = DailyRoutine(
            id = "routine-1",
            title = "아침 혈압약 먹기",
            lastCompletedDate = "2026-09-12",
            lastCompletedTime = "오전 08:25"
        )

        // 1. 당일(2026-09-12)에는 완료 상태로 인식되어야 함
        assertTrue(routine.isCompletedToday("2026-09-12"))
        assertEquals("오늘 오전 08:25 복용 완료 ✅", routine.getStatusText("2026-09-12"))

        // 2. 자정이 지나 다음 날(2026-09-13)이 되면 자동으로 미완료로 리셋되어야 함
        assertFalse(routine.isCompletedToday("2026-09-13"))
        assertEquals("아직 안 드셨어요! (탭하여 완료)", routine.getStatusText("2026-09-13"))
    }

    @Test
    fun `할일_마감_뱃지_계산_검증`() {
        val today = "2026-09-12"

        // 오늘 마감
        val taskToday = Task(id = "1", title = "장보기", dueDate = "2026-09-12")
        assertEquals(DueBadge.TODAY, taskToday.getDueBadge(today))

        // 내일 마감
        val taskTomorrow = Task(id = "2", title = "세탁소", dueDate = "2026-09-13")
        assertEquals(DueBadge.TOMORROW, taskTomorrow.getDueBadge(today))

        // 기한 지난 일
        val taskOverdue = Task(id = "3", title = "관리비", dueDate = "2026-09-10")
        assertEquals(DueBadge.OVERDUE, taskOverdue.getDueBadge(today))

        // 완료된 일
        val taskDone = Task(id = "4", title = "청소", dueDate = "2026-09-10", isCompleted = true)
        assertEquals(DueBadge.DONE, taskDone.getDueBadge(today))
    }

    @Test
    fun `테마_컬러_매핑_및_기본값_검증`() {
        // 정상 매핑
        assertEquals(ThemeColor.CORAL, ThemeColor.fromString("CORAL"))
        assertEquals(ThemeColor.GREEN, ThemeColor.fromString("GREEN"))

        // 잘못된 문자열 또는 null일 경우 기본값(CORAL)으로 폴백
        assertEquals(ThemeColor.CORAL, ThemeColor.fromString("UNKNOWN_COLOR"))
        assertEquals(ThemeColor.CORAL, ThemeColor.fromString(null))
    }
}
