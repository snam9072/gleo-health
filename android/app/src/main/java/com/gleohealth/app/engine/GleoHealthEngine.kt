package com.gleohealth.app.engine

/**
 * 글리오 헬스 규칙·점수 엔진 — mvp/engine.js 와 1:1 (명세 D1~D11, docs/thinking/01-hmg-app-challenge).
 * 차량 신호를 틱(기본 1분) 단위로 받아 상태를 갱신하는 순수 로직. Android·Vehicle SDK 에 의존하지 않는다.
 */
enum class Seat(val label: String) {
    DRIVER("운전석"), PASSENGER("조수석"), REAR_LEFT("뒷좌석 왼쪽"), REAR_RIGHT("뒷좌석 오른쪽")
}

enum class Gear { P, R, N, D }

data class SeatState(val occupied: Boolean = false, val buckled: Boolean = false)

data class Signal(
    val minutes: Int = 1,
    val speedKmh: Int,
    val gear: Gear,
    val seats: Map<Seat, SeatState>,
) {
    val moving get() = speedKmh > 0 && gear != Gear.P
    val parked get() = gear == Gear.P
}

enum class EventType { BELT, REST, REST_OK, POSTURE, ERGO_TIP }

data class Event(val type: EventType, val text: String, val seat: Seat? = null)

object Rules {
    const val BELT_WEIGHT = 0.8 // D7
    const val REST_WEIGHT = 0.2 // D7
    const val REST_SUGGEST_AT_MIN = 120 // D5
    const val REST_RESET_PARK_MIN = 15 // D5
    const val DISCOUNT_THRESHOLD = 80.0 // D8
    const val ERGO_TIP_AT_MIN = 60 // D6
}

data class SeatMinutes(val occupied: Int = 0, val buckled: Int = 0)

data class Trip(
    val minutes: Int = 0,
    val continuousMin: Int = 0,
    val parkMin: Int = 0,
    val overLimitMin: Int = 0,
    val seatMin: Map<Seat, SeatMinutes> = Seat.entries.associateWith { SeatMinutes() },
    val announced: Set<Seat> = emptySet(),
    val restSuggested: Boolean = false,
    val restTaken: Boolean = false,
    val postureOffered: Boolean = false,
    val ergoTipGiven: Boolean = false,
)

data class TickResult(val trip: Trip, val events: List<Event>)

data class Score(val belt: Double, val rest: Double, val total: Double)

/** D11: 보험사에는 월 점수와 대상 여부만. 좌석별 분·시각 같은 원자료는 담지 않는다. */
data class InsurerPayload(val monthScore: Double, val eligible: Boolean)

data class MonthlyReport(val monthScore: Double, val eligible: Boolean, val payload: InsurerPayload?)

object GleoHealthEngine {

    fun tick(prev: Trip, s: Signal): TickResult {
        val events = mutableListOf<Event>()
        val dt = s.minutes
        val seatMin = prev.seatMin.toMutableMap()
        val announced = prev.announced.toMutableSet()

        // D4 벨트
        for (seat in Seat.entries) {
            val st = s.seats[seat] ?: SeatState()
            if (!st.occupied || st.buckled) announced.remove(seat)
            if (s.moving && st.occupied) {
                val m = seatMin.getValue(seat)
                seatMin[seat] = m.copy(occupied = m.occupied + dt, buckled = m.buckled + if (st.buckled) dt else 0)
                if (!st.buckled && seat !in announced) {
                    announced += seat
                    events += Event(EventType.BELT, "${seat.label} 안전벨트를 매 주세요", seat)
                }
            }
        }

        var t = prev.copy(seatMin = seatMin, announced = announced)

        // D5 피로·휴식, D6 에르고 모션 안내
        if (s.moving) {
            val cont = t.continuousMin + dt
            val over = if (cont > Rules.REST_SUGGEST_AT_MIN) minOf(dt, cont - Rules.REST_SUGGEST_AT_MIN) else 0
            t = t.copy(minutes = t.minutes + dt, continuousMin = cont, parkMin = 0, overLimitMin = t.overLimitMin + over)
            if (cont >= Rules.REST_SUGGEST_AT_MIN && !t.restSuggested) {
                t = t.copy(restSuggested = true)
                events += Event(EventType.REST, "${cont / 60}시간째 운전 중이에요. 다음 휴게소에 들를까요?")
            }
            if (t.minutes >= Rules.ERGO_TIP_AT_MIN && !t.ergoTipGiven) {
                t = t.copy(ergoTipGiven = true)
                events += Event(EventType.ERGO_TIP, "주행 1시간이 지났어요. 에르고 모션 시트를 켜 보세요")
            }
        } else if (s.parked) {
            t = t.copy(parkMin = t.parkMin + dt)
            if (t.parkMin >= Rules.REST_RESET_PARK_MIN && t.continuousMin > 0) {
                t = t.copy(continuousMin = 0, restSuggested = false, restTaken = true, postureOffered = false)
                events += Event(EventType.REST_OK, "휴식이 기록됐어요. 연속 운전 시간을 다시 셉니다")
            }
        }

        // D6 휴식 뒤 자세 되돌리기 (P단 + 운전석 착석·체결일 때만, 주행 중엔 없음). 엔진은 시트를 움직이지 않는다(D2).
        val driver = s.seats[Seat.DRIVER] ?: SeatState()
        if (t.restTaken && !t.postureOffered && s.parked && driver.occupied && driver.buckled) {
            t = t.copy(postureOffered = true)
            events += Event(EventType.POSTURE, "다시 출발하기 전에, 저장된 운전 자세로 되돌릴까요?")
        }
        if (s.moving) t = t.copy(restTaken = false)

        return TickResult(t, events)
    }

    /** D7 */
    fun tripScore(trip: Trip): Score {
        val occ = trip.seatMin.values.sumOf { it.occupied }
        val buck = trip.seatMin.values.sumOf { it.buckled }
        val belt = if (occ > 0) buck.toDouble() / occ * 100 else 100.0
        val rest = if (trip.minutes > 0) (1 - trip.overLimitMin.toDouble() / trip.minutes) * 100 else 100.0
        val total = Rules.BELT_WEIGHT * belt + Rules.REST_WEIGHT * rest
        return Score(round1(belt), round1(rest), round1(total))
    }

    /** D8·D11 */
    fun monthlyReport(trips: List<Trip>, consent: Boolean): MonthlyReport {
        val minutes = trips.sumOf { it.minutes }
        val score = if (minutes > 0) trips.sumOf { tripScore(it).total * it.minutes } / minutes else 100.0
        val month = round1(score)
        val eligible = month >= Rules.DISCOUNT_THRESHOLD
        return MonthlyReport(month, eligible, if (consent) InsurerPayload(month, eligible) else null)
    }

    /** D9 */
    fun canOpenDashboard(s: Signal): String? = if (s.moving) "정차 후 볼 수 있어요" else null

    private fun round1(x: Double) = Math.round(x * 10) / 10.0
}
