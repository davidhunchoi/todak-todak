package com.honey.familyspace.model

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 할 일 데이터 모델
 *
 * @property id 할 일 고유 ID
 * @property spaceId 소속된 1:1 스페이스 ID
 * @property title 할 일 내용 (예: "관리비 납부", "세탁소 정장 찾기")
 * @property dueDate 마감일자 ("YYYY-MM-DD" 형식, 비어있으면 기한 없음)
 * @property isCompleted 완료 여부
 * @property completedAt 완료 시점 타임스탬프 (밀리초)
 * @property createdBy 등록자 UID
 * @property createdAt 등록 일시 타임스탬프
 */
data class Task(
    val id: String = "",
    val spaceId: String = "",
    val title: String = "",
    val dueDate: String = "",
    val isCompleted: Boolean = false,
    val completedAt: Long? = null,
    val createdBy: String = "",
    val createdAt: Long = System.currentTimeMillis()
) {
    /**
     * 마감 상태에 따른 간결한 뱃지 텍스트 반환
     * (컴맹 아내분을 위해 복잡한 D-Day 숫자 대신 '오늘', '내일', '기한초과' 등 직관적 단어 사용)
     */
    fun getDueBadge(todayDateString: String): DueBadge {
        if (dueDate.isBlank()) return DueBadge.NONE
        if (isCompleted) return DueBadge.DONE

        return when {
            dueDate < todayDateString -> DueBadge.OVERDUE   // 기한 초과
            dueDate == todayDateString -> DueBadge.TODAY    // 오늘 마감!
            isTomorrow(dueDate, todayDateString) -> DueBadge.TOMORROW // 내일 마감
            else -> DueBadge.UPCOMING                      // 예정
        }
    }

    private fun isTomorrow(target: String, today: String): Boolean {
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.KOREA)
            val todayDate = sdf.parse(today) ?: return false
            val targetDate = sdf.parse(target) ?: return false
            val diff = (targetDate.time - todayDate.time) / (1000 * 60 * 60 * 24)
            diff == 1L
        } catch (e: Exception) {
            false
        }
    }
}

/**
 * 마감일 표시 뱃지
 */
enum class DueBadge(val label: String, val badgeColorHex: Long) {
    NONE("", 0x00000000),
    DONE("완료", 0xFF9E9E9E),
    TODAY("오늘 마감", 0xFFFF5722),
    TOMORROW("내일", 0xFFFF9800),
    OVERDUE("기한 초과!", 0xFFE91E63),
    UPCOMING("예정", 0xFF607D8B)
}
