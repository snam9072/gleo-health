package com.gleohealth.app.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * 음성 안내 1회. 같은 문장을 연달아 되풀이하지 않는다(명세 D4: 반복 경고는 순정 경고음이 맡음).
 * Pleos TextToSpeech SDK 로 바꿀 자리 — 지금은 Android 표준 TTS (TODO: Pleos TTS 연동 확인 후 교체).
 */
class Voice(context: Context) {
    private var ready = false
    private var last: String? = null
    private val tts = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
    }.also { it.language = Locale.KOREAN }

    fun speakOnce(text: String) {
        if (!ready || text == last) return
        last = text
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, text.hashCode().toString())
    }

    fun release() = tts.shutdown()
}
