package com.honey.familyspace.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 6자리 일회성 초대 코드 생성기 단위 테스트
 */
class InviteCodeTest {

    @Test
    fun `초대코드_생성_길이_및_혼동_문자_배제_검증`() {
        // 100번 반복 생성하여 규칙 준수 확인
        repeat(100) {
            val code = InviteCodeGenerator.generateRawCode()
            assertEquals("코드는 정확히 6자리여야 함", 6, code.length)

            // 컴맹/어르신 혼동 방지를 위해 0, O, 1, I는 포함되지 않아야 함
            assertFalse("숫자 0은 포함되지 않아야 함", code.contains('0'))
            assertFalse("영문 O는 포함되지 않아야 함", code.contains('O'))
            assertFalse("숫자 1은 포함되지 않아야 함", code.contains('1'))
            assertFalse("영문 I는 포함되지 않아야 함", code.contains('I'))
        }
    }

    @Test
    fun `초대코드_하이픈_포맷팅_검증`() {
        val formatted = InviteCodeGenerator.generateFormattedCode()
        assertEquals(7, formatted.length)
        assertEquals('-', formatted[3])
    }

    @Test
    fun `초대코드_정규화_검증`() {
        // 소문자, 공백, 하이픈이 섞인 입력
        val rawInput = " h79-k2p "
        val normalized = InviteCodeGenerator.normalizeCode(rawInput)
        assertEquals("H79K2P", normalized)
    }

    @Test
    fun `초대코드_유효성_검사_검증`() {
        assertTrue(InviteCodeGenerator.isValidCode("H79-K2P"))
        assertTrue(InviteCodeGenerator.isValidCode("h79k2p"))

        // 길이 미달
        assertFalse(InviteCodeGenerator.isValidCode("H79"))
        // 헷갈리는 문자(0, O 등) 포함 시 부적격
        assertFalse(InviteCodeGenerator.isValidCode("H79O2P"))
        assertFalse(InviteCodeGenerator.isValidCode("H7902P"))
    }
}
