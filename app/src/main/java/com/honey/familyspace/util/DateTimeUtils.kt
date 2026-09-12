package com.honey.familyspace.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 날짜 및 시간 처리 유틸리티
 */
object DateTimeUtils {

    private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.KOREA)
    private val TIME_KOREAN_FORMAT = SimpleDateFormat("a hh:mm", Locale.KOREA)
    private val DAY_OF_WEEK_FORMAT = SimpleDateFormat("E", Locale.KOREA)
    private val MONTH_DAY_FORMAT = SimpleDateFormat("M월 d일", Locale.KOREA)

    /**
     * 오늘 날짜 문자열 반환 ("YYYY-MM-DD")
     */
    fun getTodayDateString(): String {
        return DATE_FORMAT.format(Date())
    }

    /**
     * 내일 날짜 문자열 반환 ("YYYY-MM-DD")
     */
    fun getTomorrowDateString(): String {
        val tomorrow = Date(System.currentTimeMillis() + 24 * 60 * 60 * 1000)
        return DATE_FORMAT.format(tomorrow)
    }

    /**
     * 현재 한국어 시각 반환 (예: "오전 08:25", "오후 06:10")
     * 아내분이 1초 만에 인지하기 가장 편안한 형태
     */
    fun getCurrentKoreanTimeString(timestamp: Long = System.currentTimeMillis()): String {
        return TIME_KOREAN_FORMAT.format(Date(timestamp))
    }

    /**
     * 특정 타임스탬프를 YYYY-MM-DD 로 변환
     */
    fun formatDateString(timestamp: Long): String {
        return DATE_FORMAT.format(Date(timestamp))
    }

    /**
     * "YYYY-MM-DD" 를 사람이 읽기 쉬운 한국어 날짜로 변환 (예: "2026-09-15 (화)")
     * 파싱 실패 시 원본 문자열 반환
     */
    fun formatKoreanDate(dateString: String): String {
        return try {
            val parsed = DATE_FORMAT.parse(dateString) ?: return dateString
            val dayOfWeek = DAY_OF_WEEK_FORMAT.format(parsed)
            val monthDay = MONTH_DAY_FORMAT.format(parsed)
            "$monthDay ($dayOfWeek)"
        } catch (e: Exception) {
            dateString
        }
    }
}
