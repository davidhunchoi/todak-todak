package com.honey.familyspace.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/**
 * STT manager.
 * Single-shot startListening() + long-session startContinuousListening().
 * Continuous mode auto-restarts on silence / no-match so speech does not cut off.
 */
class VoiceInputManager(private val context: Context) {

    companion object {
        fun createGoogleSpeechIntent(prompt: String = "tell task"): Intent {
            return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ko-KR")
                putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, "ko-KR")
                putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra("android.speech.extra.PREFER_OFFLINE", false)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 15000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
            }
        }

        fun pickBestResult(matches: List<String>?): String {
            if (matches.isNullOrEmpty()) return ""
            return matches.filter { it.isNotBlank() }.maxByOrNull { it.trim().length } ?: ""
        }

        fun errorMessage(error: Int, targetLang: String): String {
            val en = targetLang.startsWith("en")
            return when (error) {
                SpeechRecognizer.ERROR_NO_MATCH -> if (en) "Could not recognize speech. Please try again." else "말씀하신 내용을 인식하지 못했습니다. 다시 말씀해 주세요."
                SpeechRecognizer.ERROR_NETWORK -> if (en) "Please check your network connection." else "네트워크 연결을 확인해 주세요."
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> if (en) "Network timeout. Please try again." else "네트워크 시간이 초과됐습니다. 다시 시도해 주세요."
                SpeechRecognizer.ERROR_AUDIO -> if (en) "Please check your microphone." else "마이크 상태를 확인해 주세요."
                SpeechRecognizer.ERROR_SERVER -> if (en) "Server error. Please try again later." else "음성 서버 오류입니다. 잠시 후 다시 시도해 주세요."
                SpeechRecognizer.ERROR_CLIENT -> if (en) "Client error. Please try again." else "클라이언트 오류입니다. 다시 시도해 주세요."
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> if (en) "No speech detected. Please try again." else "음성이 감지되지 않았습니다. 다시 말씀해 주세요."
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> if (en) "Recognizer is busy. Please try again." else "음성 인식기가 사용 중입니다. 다시 시도해 주세요."
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> if (en) "Microphone permission is required. Please allow it in Settings." else "마이크 권한이 필요합니다. 설정에서 허용해 주세요."
                else -> if (en) "Voice recognition error occurred." else "음성 인식 중 오류가 발생했습니다."
            }
        }

        private fun isTransient(error: Int): Boolean {
            return error == SpeechRecognizer.ERROR_NO_MATCH ||
                error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
                error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT
        }
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private var continuousMode = false
    private var stopRequested = false
    private var sessionStartMs = 0L
    private var maxSessionMs = 30000L
    private var restartHandler: Handler? = null
    private val combinedFinals = mutableListOf<String>()
    private var pendingFinal: ((String) -> Unit)? = null
    private var lastPreview = ""

    private fun targetLanguage(languageCode: String?): String {
        return when {
            !languageCode.isNullOrBlank() -> languageCode
            Locale.getDefault().language == "en" -> "en-US"
            else -> "ko-KR"
        }
    }

    private fun buildIntent(targetLang: String, prompt: String): Intent {
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, targetLang)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, targetLang)
            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, targetLang)
            putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayOf(targetLang))
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra("android.speech.extra.PREFER_OFFLINE", false)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 15000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
        }
    }

    fun startListening(
        languageCode: String? = null,
        onPartialResult: ((String) -> Unit)? = null,
        onResult: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onError("STT not available on this device.")
            return
        }
        val targetLang = targetLanguage(languageCode)
        continuousMode = false
        stopRequested = false
        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) {
                    onError(errorMessage(error, targetLang))
                }
                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val best = pickBestResult(matches)
                    if (best.isNotBlank()) onResult(best)
                    else onError(if (targetLang.startsWith("en")) "No speech detected." else "인식된 내용이 없습니다.")
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val best = pickBestResult(matches)
                    if (best.isNotBlank()) onPartialResult?.invoke(best)
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
        val prompt = if (targetLang.startsWith("en")) "Please speak your task" else "할 일을 말씀해 주세요"
        speechRecognizer?.startListening(buildIntent(targetLang, prompt))
    }

    /**
     * Long session: accumulates chunks and auto-restarts on silence so mid-speech cutoff
     * does not end the session. Call stopContinuous() from a Done button to finalize.
     */
    fun startContinuousListening(
        languageCode: String? = null,
        maxSessionMs: Long = 30000L,
        onPartialCombined: ((String) -> Unit)? = null,
        onFinalCombined: (String) -> Unit,
        onFatalError: (String) -> Unit
    ) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onFatalError("STT not available on this device.")
            return
        }
        val targetLang = targetLanguage(languageCode)
        continuousMode = true
        stopRequested = false
        this.maxSessionMs = maxSessionMs
        sessionStartMs = System.currentTimeMillis()
        combinedFinals.clear()
        lastPreview = ""
        pendingFinal = onFinalCombined
        restartHandler?.removeCallbacksAndMessages(null)
        restartHandler = Handler(Looper.getMainLooper())

        fun combinedText(): String = combinedFinals.joinToString(" ").trim()

        fun emitFinalOnce(text: String) {
            val cb = pendingFinal
            pendingFinal = null
            if (cb != null && text.isNotBlank()) {
                continuousMode = false
                cb(text.trim())
            }
        }

        fun startRound() {
            if (stopRequested) return
            speechRecognizer?.destroy()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onError(error: Int) {
                        if (stopRequested) return
                        val elapsed = System.currentTimeMillis() - sessionStartMs
                        // Transient silence errors: restart silently instead of failing
                        if (isTransient(error) && elapsed < this@VoiceInputManager.maxSessionMs) {
                            restartHandler?.postDelayed({ startRound() }, 300)
                            return
                        }
                        if (combinedText().isNotBlank()) {
                            emitFinalOnce(combinedText())
                            return
                        }
                        if (lastPreview.isNotBlank()) {
                            emitFinalOnce(lastPreview)
                            return
                        }
                        pendingFinal = null
                        onFatalError(errorMessage(error, targetLang))
                    }
                    override fun onResults(results: Bundle?) {
                        if (stopRequested) return
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val best = pickBestResult(matches)
                        if (best.isNotBlank()) {
                            combinedFinals.add(best.trim())
                            lastPreview = combinedText()
                            onPartialCombined?.invoke(combinedText())
                        }
                        val elapsed = System.currentTimeMillis() - sessionStartMs
                        if (elapsed < this@VoiceInputManager.maxSessionMs) {
                            restartHandler?.postDelayed({ startRound() }, 250)
                        } else {
                            val full = combinedText().ifBlank { lastPreview }
                            if (full.isNotBlank()) emitFinalOnce(full)
                            else {
                                pendingFinal = null
                                onFatalError(errorMessage(SpeechRecognizer.ERROR_NO_MATCH, targetLang))
                            }
                        }
                    }
                    override fun onPartialResults(partialResults: Bundle?) {
                        if (stopRequested) return
                        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val best = pickBestResult(matches)
                        if (best.isNotBlank()) {
                            val preview = (combinedText() + " " + best.trim()).trim()
                            lastPreview = preview
                            onPartialCombined?.invoke(preview)
                        }
                    }
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }
            val prompt = if (targetLang.startsWith("en")) "Please speak your task" else "할 일을 말씀해 주세요"
            try {
                speechRecognizer?.startListening(buildIntent(targetLang, prompt))
            } catch (e: Exception) {
                onFatalError(e.localizedMessage ?: "Failed to start recognizer.")
            }
        }

        startRound()
        // Safety timeout: force finalize at max duration
        restartHandler?.postDelayed({
            if (!stopRequested && continuousMode) {
                stopRequested = true
                try { speechRecognizer?.stopListening() } catch (e: Exception) {}
                val full = combinedText()
                if (full.isNotBlank()) onFinalCombined(full)
                else onFatalError(if (targetLang.startsWith("en")) "No speech detected." else "인식된 내용이 없습니다.")
            }
        }, maxSessionMs + 2000)
    }

    /** Finalize continuous session and emit combined result. */
    fun stopContinuous() {
        stopRequested = true
        restartHandler?.removeCallbacksAndMessages(null)
        val full = combinedFinals.joinToString(" ").trim().ifBlank { lastPreview.trim() }
        val cb = pendingFinal
        pendingFinal = null
        continuousMode = false
        try {
            speechRecognizer?.cancel()
        } catch (e: Exception) {}
        if (cb != null && full.isNotBlank()) {
            cb(full)
        }
        // empty case: caller keeps PROCESSING -> falls back to error via timeout;
        // to avoid hang, do nothing here and let Activity handle empty text.
    }

    fun stopListening() {
        try {
            speechRecognizer?.stopListening()
        } catch (e: Exception) {}
    }

    fun cancel() {
        stopRequested = true
        try {
            speechRecognizer?.cancel()
        } catch (e: Exception) {}
    }

    fun destroy() {
        stopRequested = true
        restartHandler?.removeCallbacksAndMessages(null)
        restartHandler = null
        continuousMode = false
        try { speechRecognizer?.destroy() } catch (e: Exception) {}
        speechRecognizer = null
        combinedFinals.clear()
    }
}
