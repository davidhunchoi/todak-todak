package com.honey.familyspace.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.regex.Pattern

/**
 * 음성 입력 목적지 분류
 */
enum class VoiceTargetType {
    DEFAULT_TODO,       // 기본 할 일 (토닥토닥 가족방 / 기본 저장소)
    NAVIGATOR_GANTT,    // 마이 내비게이터 Gantt Task 연동
    LIFE_GRAPH          // 마이 내비게이터 인생 라이프 그래프 연동
}

/**
 * 음성 인식 텍스트 파싱 결과
 */
data class VoiceParseResult(
    val targetType: VoiceTargetType,
    val targetDate: String,     // "YYYY-MM-DD"
    val content: String,        // 정제된 본문 내용
    val rawText: String         // 사용자가 말한 원본 음성 텍스트
)

/**
 * 한국어 음성 텍스트에서 날짜, 대상(타겟), 본문을 스마트하게 분리 추출하는 파서
 */
object VoiceDateParser {

    private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.KOREA)

    /**
     * 음성 문자열을 분석하여 타겟, 날짜, 본문 추출
     */
    fun parse(rawInput: String): VoiceParseResult {
        val trimmed = rawInput.trim()
        if (trimmed.isBlank()) {
            return VoiceParseResult(
                targetType = VoiceTargetType.DEFAULT_TODO,
                targetDate = DateTimeUtils.getTodayDateString(),
                content = "",
                rawText = rawInput
            )
        }

        // 1. 타겟 분류 (인생 그래프 / 내비 간트 / 기본 투두)
        var targetType = VoiceTargetType.DEFAULT_TODO
        var workingText = trimmed

        val lifeGraphPrefixes = listOf("인생기록", "인생 기록", "인생그래프", "인생 그래프", "라이프그래프", "라이프 그래프", "인생로그")
        val ganttPrefixes = listOf("내비에", "네비에", "내비게이터에", "간트에", "업무에", "내비", "네비", "간트")

        for (prefix in lifeGraphPrefixes) {
            if (workingText.startsWith(prefix, ignoreCase = true)) {
                targetType = VoiceTargetType.LIFE_GRAPH
                workingText = workingText.removePrefix(prefix).trim().removePrefix(":").removePrefix(",").trim()
                break
            }
        }

        if (targetType == VoiceTargetType.DEFAULT_TODO) {
            for (prefix in ganttPrefixes) {
                if (workingText.startsWith(prefix, ignoreCase = true)) {
                    targetType = VoiceTargetType.NAVIGATOR_GANTT
                    workingText = workingText.removePrefix(prefix).trim().removePrefix(":").removePrefix(",").trim()
                    break
                }
            }
        }

        // 2. 날짜 추출 및 제거
        val (parsedDate, textWithoutDate) = extractDateAndClean(workingText)

        // 3. 서술어 및 불필요한 조사 제거 (본문 정제)
        val cleanedContent = cleanContent(textWithoutDate)

        return VoiceParseResult(
            targetType = targetType,
            targetDate = parsedDate ?: DateTimeUtils.getTodayDateString(),
            content = if (cleanedContent.isNotBlank()) cleanedContent else workingText,
            rawText = rawInput
        )
    }

    /**
     * 날짜 표현을 탐지하여 YYYY-MM-DD 로 변환하고 해당 날짜 부분을 텍스트에서 제거
     */
    private fun extractDateAndClean(input: String): Pair<String?, String> {
        var text = input.trim()
        val cal = Calendar.getInstance(Locale.KOREA)
        val today = Calendar.getInstance(Locale.KOREA)

        // 1) "오늘날짜로", "오늘 날짜로", "오늘날짜", "오늘"
        val todayPattern = Pattern.compile("^(오늘\\s*날짜(?:로)?|오늘|금일)(?:\\s+|로\\s*)?")
        val todayMatcher = todayPattern.matcher(text)
        if (todayMatcher.find()) {
            val dateStr = DATE_FORMAT.format(today.time)
            text = todayMatcher.replaceFirst("").trim()
            return Pair(dateStr, text)
        }

        // 2) "내일날짜로", "내일 날짜로", "내일", "명일"
        val tomorrowPattern = Pattern.compile("^(내일\\s*날짜(?:로)?|내일|명일)(?:\\s+|로\\s*)?")
        val tomorrowMatcher = tomorrowPattern.matcher(text)
        if (tomorrowMatcher.find()) {
            cal.time = today.time
            cal.add(Calendar.DAY_OF_YEAR, 1)
            val dateStr = DATE_FORMAT.format(cal.time)
            text = tomorrowMatcher.replaceFirst("").trim()
            return Pair(dateStr, text)
        }

        // 3) "모레날짜로", "모레", "내일 모레"
        val dayAfterTomorrowPattern = Pattern.compile("^(내일\\s*모레|모레\\s*날짜(?:로)?|모레)(?:\\s+|로\\s*)?")
        val dayAfterMatcher = dayAfterTomorrowPattern.matcher(text)
        if (dayAfterMatcher.find()) {
            cal.time = today.time
            cal.add(Calendar.DAY_OF_YEAR, 2)
            val dateStr = DATE_FORMAT.format(cal.time)
            text = dayAfterMatcher.replaceFirst("").trim()
            return Pair(dateStr, text)
        }

        // 4) "글피"
        if (text.startsWith("글피")) {
            cal.time = today.time
            cal.add(Calendar.DAY_OF_YEAR, 3)
            val dateStr = DATE_FORMAT.format(cal.time)
            text = text.removePrefix("글피").trim().removePrefix("로").trim()
            return Pair(dateStr, text)
        }

        // 5) "어제", "작일"
        val yesterdayPattern = Pattern.compile("^(어제\\s*날짜(?:로)?|어제|작일)(?:\\s+|로\\s*)?")
        val yesterdayMatcher = yesterdayPattern.matcher(text)
        if (yesterdayMatcher.find()) {
            cal.time = today.time
            cal.add(Calendar.DAY_OF_YEAR, -1)
            val dateStr = DATE_FORMAT.format(cal.time)
            text = yesterdayMatcher.replaceFirst("").trim()
            return Pair(dateStr, text)
        }

        // 6) "M월 d일", "M월d일", "YYYY년 M월 d일" 패턴 탐지
        val monthDayPattern = Pattern.compile("(?:(\\d{4})년\\s*)?(\\d{1,2})월\\s*(\\d{1,2})일(?:로|에)?")
        val monthDayMatcher = monthDayPattern.matcher(text)
        if (monthDayMatcher.find()) {
            val yearStr = monthDayMatcher.group(1)
            val month = monthDayMatcher.group(2)?.toIntOrNull() ?: 1
            val day = monthDayMatcher.group(3)?.toIntOrNull() ?: 1

            cal.time = today.time
            val year = yearStr?.toIntOrNull() ?: cal.get(Calendar.YEAR)
            cal.set(Calendar.YEAR, year)
            cal.set(Calendar.MONTH, month - 1)
            cal.set(Calendar.DAY_OF_MONTH, day)

            val dateStr = DATE_FORMAT.format(cal.time)
            text = text.replaceRange(monthDayMatcher.start(), monthDayMatcher.end(), "").trim()
            return Pair(dateStr, text)
        }

        // 7) 요일 패턴 ("이번주 금요일", "다음주 월요일", "금요일날")
        val weekdayPattern = Pattern.compile("^(이번\\s*주|다음\\s*주)?\\s*([월화수목금토일])요일(?:로|에|날)?")
        val weekdayMatcher = weekdayPattern.matcher(text)
        if (weekdayMatcher.find()) {
            val isNextWeek = weekdayMatcher.group(1)?.contains("다음") == true
            val dayChar = weekdayMatcher.group(2)
            val targetDayOfWeek = when (dayChar) {
                "일" -> Calendar.SUNDAY
                "월" -> Calendar.MONDAY
                "화" -> Calendar.TUESDAY
                "수" -> Calendar.WEDNESDAY
                "목" -> Calendar.THURSDAY
                "금" -> Calendar.FRIDAY
                "토" -> Calendar.SATURDAY
                else -> Calendar.MONDAY
            }

            cal.time = today.time
            if (isNextWeek) {
                cal.add(Calendar.WEEK_OF_YEAR, 1)
            }
            cal.set(Calendar.DAY_OF_WEEK, targetDayOfWeek)
            // 이번 주인데 이미 지난 요일이면 다음 주로 보정
            if (!isNextWeek && cal.before(today)) {
                cal.add(Calendar.WEEK_OF_YEAR, 1)
            }

            val dateStr = DATE_FORMAT.format(cal.time)
            text = weekdayMatcher.replaceFirst("").trim()
            return Pair(dateStr, text)
        }

        return Pair(null, text)
    }

    /**
     * 문장 끝의 서술어("~를 저장해줘", "~등록해줘" 등) 및 조동사 트리밍
     */
    private fun cleanContent(input: String): String {
        var text = input.trim()

        val suffixPatterns = listOf(
            Pattern.compile("[을를에]?\\s*(?:저장|등록|기록|추가|적어|써|남겨)\\s*(?:해줘요?|해주세요|해라|해|줘|주세요|바래|바랍니다)$"),
            Pattern.compile("[을를에]?\\s*(?:저장|등록|기록|추가)$"),
            Pattern.compile("(?:부탁해|부탁해요|해줘|해)$")
        )

        for (pattern in suffixPatterns) {
            val matcher = pattern.matcher(text)
            if (matcher.find()) {
                text = matcher.replaceFirst("").trim()
            }
        }

        // 시작 부분에 남아있는 불필요한 조사 ("로", "를", "을", "에") 정리
        text = text.removePrefix("로 ").removePrefix("를 ").removePrefix("을 ").removePrefix("에 ").trim()
        text = text.removePrefix("로").removePrefix("를").removePrefix("을").removePrefix("에").trim()

        return text
    }
}
