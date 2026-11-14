package com.gleohealth.app.store

import com.gleohealth.app.engine.Gear
import com.gleohealth.app.engine.GleoHealthEngine
import com.gleohealth.app.engine.Seat
import com.gleohealth.app.engine.SeatState
import com.gleohealth.app.engine.Signal
import com.gleohealth.app.engine.Trip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.crypto.KeyGenerator

class TripStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val sealer = AesGcmSealer { key }

    /** 혼자 60분, 조수석 30분 미체결 → 명세 D7 예시 80.0 이 나오는 주행 */
    private fun sampleTrip(): Trip {
        var t = Trip()
        val both = mapOf(Seat.DRIVER to SeatState(true, true), Seat.PASSENGER to SeatState(true, false))
        val buckled = mapOf(Seat.DRIVER to SeatState(true, true), Seat.PASSENGER to SeatState(true, true))
        repeat(30) { t = GleoHealthEngine.tick(t, Signal(1, 60, Gear.D, both)).trip }
        repeat(30) { t = GleoHealthEngine.tick(t, Signal(1, 60, Gear.D, buckled)).trip }
        return t
    }

    @Test fun restart_keeps_month_score() {
        val f = tmp.newFile("trips.bin")
        val store = TripStore(f, sealer) { "2026-11" }
        val l = Ledger("2026-11", current = sampleTrip(), finished = listOf(StoredTrip("2026-11", sampleTrip())), consent = true)
        store.save(l)
        val back = TripStore(f, sealer) { "2026-11" }.load() // 앱 재시작
        assertEquals(l, back)
        val before = GleoHealthEngine.monthlyReport(l.monthTrips(), true).monthScore
        assertEquals(80.0, before, 0.0)
        assertEquals(before, GleoHealthEngine.monthlyReport(back.monthTrips(), true).monthScore, 0.0)
    }

    @Test fun file_has_no_plaintext_and_new_iv_each_save() {
        val f = tmp.newFile("trips.bin")
        val store = TripStore(f, sealer) { "2026-11" }
        val l = Ledger("2026-11", current = sampleTrip())
        store.save(l)
        val a = f.readBytes()
        val text = String(a, Charsets.ISO_8859_1)
        assertFalse(text.contains("2026-11"))
        assertFalse(text.contains("v1"))
        assertFalse(text.contains("60,"))
        store.save(l)
        assertNotEquals(a.toList(), f.readBytes().toList()) // 같은 내용이라도 IV 가 달라 암호문이 다르다
    }

    @Test fun tampered_or_garbage_file_starts_empty_without_crash() {
        val f = tmp.newFile("trips.bin")
        val store = TripStore(f, sealer) { "2026-11" }
        store.save(Ledger("2026-11", current = sampleTrip()))
        val b = f.readBytes(); b[b.size - 1] = (b[b.size - 1].toInt() xor 1).toByte(); f.writeBytes(b)
        assertEquals(Ledger("2026-11"), store.load())
        f.writeBytes("not encrypted".toByteArray())
        assertEquals(Ledger("2026-11"), store.load())
        val otherKey = AesGcmSealer { KeyGenerator.getInstance("AES").apply { init(256) }.generateKey() }
        TripStore(f, otherKey) { "2026-11" }.save(Ledger("2026-11", current = sampleTrip()))
        assertEquals(Ledger("2026-11"), store.load()) // 다른 키로 만든 파일은 못 연다
    }

    @Test fun keeps_13_months_and_rolls_over_month() {
        val old = listOf("2025-09", "2025-10", "2026-10").map { StoredTrip(it, sampleTrip()) }
        val l = TripStore.rollOver(Ledger("2026-10", current = sampleTrip(), finished = old), "2026-11")
        assertEquals(listOf("2025-10", "2026-10", "2026-10"), l.finished.map { it.month })
        assertEquals(Trip(), l.current)
        assertEquals("2026-11", l.month)
        assertEquals(13, TripStore.monthsBetween("2025-10", "2026-11"))
        assertTrue(l.monthTrips() == listOf(Trip())) // 새 달은 지금 주행만
    }
}
