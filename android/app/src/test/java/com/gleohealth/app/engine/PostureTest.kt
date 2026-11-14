package com.gleohealth.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** mvp/engine.test.mjs 의 D28 예시와 같다 */
class PostureTest {
    @Test fun backrest_ratio_boundaries() {
        assertEquals("세움", Posture.advice(2, 10).label)
        assertEquals("보통", Posture.advice(3, 9).label) // 정확히 1/3
        assertEquals("보통", Posture.advice(6, 9).label) // 정확히 2/3
        assertEquals("눕힘", Posture.advice(7, 9).label)
        assertEquals(0, Posture.advice(5, 9).tips.size)
    }

    @Test fun lumbar_and_same_posture_tips() {
        assertEquals(1, Posture.advice(5, 9, lumbar = 0).tips.size)
        assertEquals(0, Posture.advice(5, 9, lumbar = null).tips.size)
        assertEquals(0, Posture.advice(5, 9, sameMin = 59).tips.size)
        assertTrue(Posture.advice(5, 9, sameMin = 60).tips[0].contains("한 시간째"))
    }
}
