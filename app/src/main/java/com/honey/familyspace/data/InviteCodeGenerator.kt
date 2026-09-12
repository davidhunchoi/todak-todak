package com.honey.familyspace.data

import java.security.SecureRandom

/**
 * 4자리 숫자 일회성 초대 코드 생성기 (예: "1234")
 *
 * 숫자만 사용해 어르신도 쉽게 입력할 수 있도록 설계.
 * 코드는 10분간 유효하며 상대가 연결하는 순간 소멸하는 1회용.
 */
object InviteCodeGenerator {

    private const val CODE_LENGTH = 4
    private val random = SecureRandom()

    /**
     * 4자리 숫자 코드 생성 (예: "0837")
     */
    fun generateRawCode(): String {
        val sb = StringBuilder(CODE_LENGTH)
        repeat(CODE_LENGTH) {
            sb.append(random.nextInt(10))
        }
        return sb.toString()
    }

    /**
     * 표시용 코드 (4자리 숫자, 하이픈 없음)
     */
    fun generateFormattedCode(): String {
        return generateRawCode()
    }

    /**
     * 사용자가 입력한 코드 정규화 (하이픈 및 공백 제거)
     */
    fun normalizeCode(input: String): String {
        return input.replace("-", "")
            .replace(" ", "")
            .trim()
    }

    /**
     * 유효한 4자리 숫자 코드 포맷인지 검증
     */
    fun isValidCode(input: String): Boolean {
        val normalized = normalizeCode(input)
        if (normalized.length != CODE_LENGTH) return false
        return normalized.all { it.isDigit() }
    }
}
