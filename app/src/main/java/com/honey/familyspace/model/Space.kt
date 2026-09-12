package com.honey.familyspace.model

/**
 * 1:1 공유 공간 (스페이스) 엔티티
 *
 * @property id 스페이스 고유 ID (Firestore 문서 ID)
 * @property title 방 이름 (예: "우리 부부", "엄마와 나", "영희와 나")
 * @property themeColor 방 고유 테마 컬러 (상대방 혼선 방지)
 * @property memberUids 참여자 UID 목록 (최대 2명인 1:1 구조)
 * @property createdBy 개설자 UID
 * @property createdAt 개설 일시 타임스탬프
 */
data class Space(
    val id: String = "",
    val title: String = "",
    val themeColor: String = ThemeColor.CORAL.name,
    val memberUids: List<String> = emptyList(),
    val createdBy: String = "",
    val createdAt: Long = System.currentTimeMillis()
) {
    /**
     * ThemeColor enum 객체로 반환
     */
    fun getTheme(): ThemeColor = ThemeColor.fromString(themeColor)

    /**
     * 상대방이 방에 참여 완료되었는지 여부
     */
    val isPaired: Boolean
        get() = memberUids.size >= 2
}
