package com.honey.familyspace.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * 날짜 및 한국어 시각 유틸리티 단위 테스트
 */
class DateTimeUtilsTest {

    @Test
    fun `오늘과_내일_날짜_문자열_포맷_검증`() {
        val today = DateTimeUtils.getTodayDateString()
        val tomorrow = DateTimeUtils.getTomorrowDateString()

        // "YYYY-MM-DD" 포맷 검증 (길이 10, 하이픈 위치)
        assertEquals(10, today.length)
        assertEquals(10, tomorrow.length)
        assertEquals('-', today[4])
        assertEquals('-', today[7])
        assertEquals('-', tomorrow[4])
        assertEquals('-', tomorrow[7])

        // 내일 날짜가 오늘보다 사전순으로 크거나 같아야 함
        assertTrue(tomorrow >= today)
    }

    @Test
    fun `한국어_시각_포맷_검증`() {
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 8)
            set(Calendar.MINUTE, 25)
        }

        val timeString = DateTimeUtils.getCurrentKoreanTimeString(calendar.timeInMillis)
        // "오전 08:25" 형식 확인
        assertTrue("오전이 포함되어야 함", timeString.contains("오전"))
        assertTrue("08:25가 포함되어야 함", timeString.contains("08:25"))
    }
}
