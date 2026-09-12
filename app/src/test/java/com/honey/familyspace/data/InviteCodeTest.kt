package com.honey.familyspace.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 4자리 숫자 일회성 초대 코드 생성기 단위 테스트
 */
class InviteCodeTest {

    @Test
    fun `초대코드_생성_4자리_숫자_검증`() {
        // 100번 반복 생성하여 규칙 준수 확인
        repeat(100) {
            val code = InviteCodeGenerator.generateRawCode()
            assertEquals("코드는 정확히 4자리여야 함", 4, code.length)
            assertTrue("코드는 숫자만 포함해야 함", code.all { it.isDigit() })
        }
    }

    @Test
    fun `초대코드_포맷_하이픈_없음_검증`() {
        val formatted = InviteCodeGenerator.generateFormattedCode()
        assertEquals(4, formatted.length)
        assertFalse(formatted.contains('-'))
        assertTrue(formatted.all { it.isDigit() })
    }

    @Test
    fun `초대코드_정규화_검증`() {
        // 공백, 하이픈이 섞인 입력
        val rawInput = " 12-34 "
        val normalized = InviteCodeGenerator.normalizeCode(rawInput)
        assertEquals("1234", normalized)
    }

    @Test
    fun `초대코드_유효성_검사_검증`() {
        assertTrue(InviteCodeGenerator.isValidCode("1234"))
        assertTrue(InviteCodeGenerator.isValidCode(" 12-34 "))
        assertTrue(InviteCodeGenerator.isValidCode("0837"))

        // 길이 미달/초과
        assertFalse(InviteCodeGenerator.isValidCode("123"))
        assertFalse(InviteCodeGenerator.isValidCode("12345"))

        // 숫자가 아닌 문자 포함 시 부적격
        assertFalse(InviteCodeGenerator.isValidCode("12a4"))
        assertFalse(InviteCodeGenerator.isValidCode("12-3"))
        assertFalse(InviteCodeGenerator.isValidCode("H79K2P"))
    }
}
