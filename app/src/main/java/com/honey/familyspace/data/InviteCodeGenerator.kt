package com.honey.familyspace.data

import java.security.SecureRandom

/**
 * 6자리 일회성 초대 코드 생성기
 *
 * 혼동하기 쉬운 문자(0과 O, 1과 I)를 배제하여 컴맹이나 어르신도 오타 없이 입력 가능하도록 설계
 */
object InviteCodeGenerator {

    // 헷갈리는 문자(0, O, 1, I)를 제외한 32개 문자셋
    private const val CHAR_POOL = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
    private const val CODE_LENGTH = 6
    private val random = SecureRandom()

    /**
     * 6자리 난수 코드 생성 (예: "H79K2P")
     */
    fun generateRawCode(): String {
        val sb = StringBuilder(CODE_LENGTH)
        repeat(CODE_LENGTH) {
            val index = random.nextInt(CHAR_POOL.length)
            sb.append(CHAR_POOL[index])
        }
        return sb.toString()
    }

    /**
     * 읽기 편한 하이픈 포맷 코드 반환 (예: "H79-K2P")
     */
    fun generateFormattedCode(): String {
        val raw = generateRawCode()
        return "${raw.substring(0, 3)}-${raw.substring(3)}"
    }

    /**
     * 사용자가 입력한 코드 정규화 (하이픈 및 공백 제거, 대문자 변환)
     */
    fun normalizeCode(input: String): String {
        return input.replace("-", "")
            .replace(" ", "")
            .trim()
            .uppercase()
    }

    /**
     * 유효한 6자리 코드 포맷인지 검증
     */
    fun isValidCode(input: String): Boolean {
        val normalized = normalizeCode(input)
        if (normalized.length != CODE_LENGTH) return false
        return normalized.all { it in CHAR_POOL }
    }
}
