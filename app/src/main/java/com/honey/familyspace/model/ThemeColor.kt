package com.honey.familyspace.model

/**
 * 상대방 혼선 방지를 위한 스페이스별 고유 5종 감성 파스텔 테마 컬러
 *
 * @property displayName 사용자에게 노출되는 한글 테마 이름
 * @property backgroundHex 메인 화면 및 상단 헤더 배경색 (연하고 부드러운 파스텔 톤)
 * @property cardBackgroundHex 카드 컴포넌트 배경색 (고대비 깔끔한 톤)
 * @property accentHex 버튼, 체크박스, 뱃지 등 포인트 강조색
 * @property textColor 텍스트 기본 색상
 */
enum class ThemeColor(
    val displayName: String,
    val backgroundHex: Long,
    val cardBackgroundHex: Long,
    val accentHex: Long,
    val textColor: Long
) {
    // 부부 추천: 따뜻하고 포근한 복숭아빛 코랄
    CORAL(
        displayName = "로맨틱 코랄 (부부 추천)",
        backgroundHex = 0xFFFFF5F0,
        cardBackgroundHex = 0xFFFFFFFF,
        accentHex = 0xFFFF7A66,
        textColor = 0xFF2D2422
    ),

    // 부모님 추천: 차분하고 편안한 샐비어 민트 그린
    GREEN(
        displayName = "힐링 그린 (부모님 추천)",
        backgroundHex = 0xFFF1F8F4,
        cardBackgroundHex = 0xFFFFFFFF,
        accentHex = 0xFF52A87A,
        textColor = 0xFF1E2B23
    ),

    // 친구 추천: 산뜻하고 청량한 스카이 블루
    BLUE(
        displayName = "산뜻 블루 (친구/모임 추천)",
        backgroundHex = 0xFFF0F7FF,
        cardBackgroundHex = 0xFFFFFFFF,
        accentHex = 0xFF4A90E2,
        textColor = 0xFF1E2835
    ),

    // 화사하고 밝은 크림 레몬 옐로우
    YELLOW(
        displayName = "크림 옐로우",
        backgroundHex = 0xFFFFFDF0,
        cardBackgroundHex = 0xFFFFFFFF,
        accentHex = 0xFFE5A823,
        textColor = 0xFF302B1D
    ),

    // 우아하고 부드러운 소프트 라벤더
    LAVENDER(
        displayName = "소프트 라벤더",
        backgroundHex = 0xFFF9F3FF,
        cardBackgroundHex = 0xFFFFFFFF,
        accentHex = 0xFF9E65CD,
        textColor = 0xFF2B2035
    );

    companion object {
        /**
         * 문자열로부터 안전하게 ThemeColor를 찾거나 기본값(CORAL) 반환
         */
        fun fromString(name: String?): ThemeColor {
            return entries.find { it.name.equals(name, ignoreCase = true) } ?: CORAL
        }
    }
}
