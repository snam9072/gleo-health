package com.gleohealth.app.gleo

/**
 * Gleo 수신기의 보안 규칙 (S1·S2) — Android 에 의존하지 않는 순수 로직이라 단위 테스트한다.
 *
 * S1 요청 거르기: 수신기는 Gleo(시스템 앱)가 우리 패키지를 지정해 보내므로 exported 여야 한다.
 *   그래서 ① Android 14+ 에서 보낸 앱 신원이 공유되면 시스템 앱만 받는다 ② 같은 함수는 2초에 1번만 처리한다.
 * S2 응답 보내기: 응답을 받는 시스템 앱을 찾으면 그 패키지만 지정해 보낸다 → 요청을 위조한 앱은 응답 내용을 받지 못한다.
 *   찾지 못하면(Gleo 가 수신기를 코드로 등록하는 경우) 공식 예제대로 보내되, 내용은 Gleo 가 말할 최소값뿐이다.
 */
object GleoGuard {
    const val MIN_INTERVAL_MS = 2_000L

    sealed interface Sender {
        data object Unknown : Sender
        data class Known(val isSystemApp: Boolean) : Sender
    }

    /**
     * strict = true 면 신원을 모르는 요청도 거부한다.
     * 1차 제출본은 false(마스터 결정 2026-11-14 '연동 우선 + 엄격 스위치'): Gleo 가 보낸 앱 신원을 공유하는지
     * 에뮬레이터에서 확인되기 전에 켜면 Gleo 음성 연동 자체가 막힐 수 있다. 확인되면 build.gradle 의 GLEO_STRICT_SENDER 를 true 로.
     */
    fun acceptSender(sender: Sender, strict: Boolean): Boolean = when (sender) {
        Sender.Unknown -> !strict
        is Sender.Known -> sender.isSystemApp
    }

    private val lastHandled = mutableMapOf<String, Long>()

    /**
     * 같은 보낸 앱의 같은 함수 요청이 2초 안에 다시 오면 false.
     * 보낸 앱별로 따로 센다 — 한 앱이 계속 보내도 Gleo 의 요청 자리를 빼앗지 못한다(감사 P1-7). 표는 64개까지만 둔다.
     */
    @Synchronized
    fun allow(action: String, sender: String, nowMs: Long): Boolean {
        val key = "$sender|$action"
        val last = lastHandled[key]
        if (last != null && nowMs - last in 0 until MIN_INTERVAL_MS) return false
        if (lastHandled.size >= 64) lastHandled.clear()
        lastHandled[key] = nowMs
        return true
    }

    /** ACTION_PARAM 이 JSON 객체 문자열이든, 그것을 한 번 더 문자열로 감싼 것이든 객체로 읽는다(감사 P2-7). 4KB 넘으면 무시. */
    fun parseParams(raw: String?): org.json.JSONObject? {
        if (raw == null || raw.length > 4096) return null
        return runCatching {
            when (val v = org.json.JSONTokener(raw).nextValue()) {
                is org.json.JSONObject -> v
                is String -> org.json.JSONObject(v)
                else -> null
            }
        }.getOrNull()
    }

    @Synchronized
    fun resetForTest() = lastHandled.clear()

    /** 응답 대상: 시스템 앱 수신기 목록 → 그 패키지들만, 없으면 null(=공식 예제 방식). 우리 자신은 빼고 중복 제거. */
    fun responseTargets(systemReceivers: List<String>, self: String): List<String>? =
        systemReceivers.filter { it != self }.distinct().sorted().takeIf { it.isNotEmpty() }
}
