package com.gleohealth.app.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * 음성 안내 1회. 같은 문장을 연달아 되풀이하지 않는다(명세 D4: 반복 경고는 순정 경고음이 맡음).
 * Pleos TextToSpeech SDK 로 바꿀 자리 — 지금은 Android 표준 TTS (TODO: Pleos TTS 연동 확인 후 교체).
 */
class Voice(context: Context) {
    @Volatile private var ready = false
    private var last: String? = null
    private lateinit var tts: TextToSpeech

    init {
        // 언어는 엔진 준비가 끝난 뒤에 정한다(감사 P2-5). 한국어를 못 쓰면 읽지 않는다.
        tts = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS && tts.setLanguage(Locale.KOREAN) >= TextToSpeech.LANG_AVAILABLE
        }
    }

    fun speakOnce(text: String) {
        if (!ready || text == last) return
        last = text
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, text.hashCode().toString())
    }

    fun release() = tts.shutdown()
}
