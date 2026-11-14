package com.gleohealth.app.gleo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GleoGuardTest {
    @Before fun reset() = GleoGuard.resetForTest()

    @Test fun same_action_within_2s_is_dropped() {
        assertTrue(GleoGuard.allow("CHECK", 10_000))
        assertFalse(GleoGuard.allow("CHECK", 11_999))
        assertTrue(GleoGuard.allow("SCORE", 11_999)) // 다른 함수는 따로 센다
        assertTrue(GleoGuard.allow("CHECK", 12_000))
    }

    @Test fun known_non_system_sender_is_rejected_in_both_modes() {
        for (strict in listOf(false, true)) {
            assertTrue(GleoGuard.acceptSender(GleoGuard.Sender.Known(isSystemApp = true), strict))
            assertFalse(GleoGuard.acceptSender(GleoGuard.Sender.Known(isSystemApp = false), strict))
        }
    }

    @Test fun unknown_sender_depends_on_strict_switch() {
        assertTrue(GleoGuard.acceptSender(GleoGuard.Sender.Unknown, strict = false)) // 1차 제출본: Gleo 연동 우선
        assertFalse(GleoGuard.acceptSender(GleoGuard.Sender.Unknown, strict = true)) // 에뮬레이터 확인 뒤 켬
    }

    @Test fun response_goes_only_to_system_receivers() {
        assertEquals(listOf("ai.pleos.a", "ai.umos.b"), GleoGuard.responseTargets(listOf("ai.umos.b", "ai.pleos.a", "ai.umos.b", "com.gleohealth.app"), "com.gleohealth.app"))
        assertNull(GleoGuard.responseTargets(emptyList(), "com.gleohealth.app"))
        assertNull(GleoGuard.responseTargets(listOf("com.gleohealth.app"), "com.gleohealth.app"))
    }
}
