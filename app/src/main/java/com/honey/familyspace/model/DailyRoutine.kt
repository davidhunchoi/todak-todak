package com.honey.familyspace.model

/**
 * 매일 반복 루틴 데이터 모델 (예: 약 먹기, 영양제 등)
 *
 * @property id 루틴 고유 ID
 * @property spaceId 소속된 스페이스 ID
 * @property title 루틴 이름 (예: "아침 혈압약 먹기", "유산균 먹기")
 * @property iconType 아이콘 종류 ("PILL", "WATER", "WALK", "HEART")
 * @property lastCompletedDate 마지막으로 체크한 날짜 ("YYYY-MM-DD")
 * @property lastCompletedTime 마지막으로 체크한 시각 ("오전 08:25" 형식 - 아내 안심용)
 * @property targetTime 권장 시간 (예: "08:30")
 * @property createdAt 생성 일시
 */
data class DailyRoutine(
    val id: String = "",
    val spaceId: String = "",
    val title: String = "",
    val iconType: String = "PILL",
    val lastCompletedDate: String = "",
    val lastCompletedTime: String = "",
    val targetTime: String = "08:30",
    val createdAt: Long = System.currentTimeMillis()
) {
    /**
     * 오늘 날짜 기준으로 완료되었는지 확인
     * (자정이 지나면 lastCompletedDate != todayString 이므로 자동으로 미완료(false) 상태로 리셋)
     */
    fun isCompletedToday(todayDateString: String): Boolean {
        return lastCompletedDate.isNotBlank() && lastCompletedDate == todayDateString
    }

    /**
     * 아내분이 1초 만에 확인하고 안심할 수 있는 상태 문구
     * 예: "오늘 오전 08:25 복용 완료 ✅" 또는 "아직 안 먹었어요! (터치해서 체크)"
     */
    fun getStatusText(todayDateString: String): String {
        return if (isCompletedToday(todayDateString)) {
            "오늘 $lastCompletedTime 복용 완료 ✅"
        } else {
            "아직 안 드셨어요! (탭하여 완료)"
        }
    }
}
