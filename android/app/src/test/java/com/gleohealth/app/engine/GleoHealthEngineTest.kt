package com.gleohealth.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** mvp/engine.test.mjs 와 같은 명세 예시 (D1~D11) */
class GleoHealthEngineTest {
    private fun seats(vararg o: Pair<Seat, SeatState>): Map<Seat, SeatState> =
        mapOf(Seat.DRIVER to SeatState(true, true), Seat.PASSENGER to SeatState(), Seat.REAR_LEFT to SeatState(), Seat.REAR_RIGHT to SeatState()) + o

    private fun drive(s: Map<Seat, SeatState>) = Signal(speedKmh = 60, gear = Gear.D, seats = s)
    private fun park(s: Map<Seat, SeatState>) = Signal(speedKmh = 0, gear = Gear.P, seats = s)

    private fun run(start: Trip, signals: List<Signal>): Pair<Trip, List<Event>> {
        var t = start
        val ev = mutableListOf<Event>()
        for (s in signals) { val r = GleoHealthEngine.tick(t, s); t = r.trip; ev += r.events }
        return t to ev
    }
    private fun List<Signal>.times(n: Int) = (1..n).flatMap { this }

    private val passengerOpen = seats(Seat.PASSENGER to SeatState(true, false))
    private val passengerOk = seats(Seat.PASSENGER to SeatState(true, true))

    @Test fun d4_belt_announcedOnceOnDepart_noRepeat_emptySeatNotRecorded() {
        var (t, ev) = run(Trip(), listOf(park(passengerOpen)))
        assertTrue(ev.none { it.type == EventType.BELT })
        val r = run(t, listOf(drive(passengerOpen)).times(5))
        val belts = r.second.filter { it.type == EventType.BELT }
        assertEquals(1, belts.size)
        assertEquals("조수석 안전벨트를 매 주세요", belts[0].text)
        assertEquals(0, r.first.seatMin.getValue(Seat.REAR_LEFT).occupied)
    }

    @Test fun d5_restAtTwoHours_parkFifteenMinutesResets() {
        var r = run(Trip(), listOf(drive(seats())).times(119))
        assertTrue(r.second.none { it.type == EventType.REST })
        r = run(r.first, listOf(drive(seats())))
        assertEquals(1, r.second.count { it.type == EventType.REST })
        r = run(r.first, listOf(park(seats())).times(14))
        assertEquals(120, r.first.continuousMin)
        r = run(r.first, listOf(park(seats())))
        assertEquals(0, r.first.continuousMin)
    }

    @Test fun d6_postureOfferOnlyParkedAndBuckledAfterRest() {
        var r = run(Trip(), listOf(drive(seats())).times(30))
        r = run(r.first, listOf(park(seats(Seat.DRIVER to SeatState(true, false)))).times(15))
        assertTrue(r.second.none { it.type == EventType.POSTURE })
        r = run(r.first, listOf(park(seats())))
        assertEquals(1, r.second.count { it.type == EventType.POSTURE })
    }

    @Test fun d7_scoreExamples_100_80_93_3() {
        assertEquals(100.0, GleoHealthEngine.tripScore(run(Trip(), listOf(drive(seats())).times(60)).first).total, 0.0)
        val half = run(Trip(), listOf(drive(passengerOpen)).times(30) + listOf(drive(passengerOk)).times(30)).first
        assertEquals(75.0, GleoHealthEngine.tripScore(half).belt, 0.0)
        assertEquals(80.0, GleoHealthEngine.tripScore(half).total, 0.0)
        val long = GleoHealthEngine.tripScore(run(Trip(), listOf(drive(seats())).times(180)).first)
        assertEquals(66.7, long.rest, 0.0)
        assertEquals(93.3, long.total, 0.0)
    }

    @Test fun d8_d11_consentScoreOnly_threshold80() {
        val t80 = run(Trip(), listOf(drive(passengerOpen)).times(30) + listOf(drive(passengerOk)).times(30)).first
        assertNull(GleoHealthEngine.monthlyReport(listOf(t80), consent = false).payload)
        val m = GleoHealthEngine.monthlyReport(listOf(t80), consent = true)
        assertTrue(m.eligible)
        assertEquals(InsurerPayload(80.0, true), m.payload)
        val t79 = run(Trip(), listOf(drive(passengerOpen)).times(31) + listOf(drive(passengerOk)).times(29)).first
        assertFalse(GleoHealthEngine.monthlyReport(listOf(t79), consent = true).eligible)
    }

    @Test fun d9_noDashboardWhileMoving() {
        assertEquals("정차 후 볼 수 있어요", GleoHealthEngine.canOpenDashboard(drive(seats())))
        assertNull(GleoHealthEngine.canOpenDashboard(park(seats())))
    }
}
