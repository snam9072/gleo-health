package com.gleohealth.app.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/** mvp/engine.test.mjs 와 같은 예시 — 말풍선이 계속 떠 있던 버그 재발 방지 */
class GuideTest {
    @Test fun guide_always_disappears_within_bounds() {
        assertEquals(3_500L, Guide.durationMs(""))
        assertEquals(3_500L, Guide.durationMs("조수석, 벨트 매 주세요"))
        assertEquals(4_060L, Guide.durationMs("2시간 달렸어요. 다음 휴게소에서 쉬어 갈까요?"))
        assertEquals(7_000L, Guide.durationMs("가".repeat(200)))
    }
}
