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
    // D8 → 결정 D-10(2026-11-15): 할인 판정은 월 점수가 아니라 두 조건 모두 — 벨트 95점 이상 AND 휴식 90점 이상.
    // (월 점수 80점 기준이면 조수석 30분 미착용 80점·3시간 무휴식 93.3점도 '할인 대상'이 되는 허점이 있었다)
    const val BELT_GATE = 95.0
    const val REST_GATE = 90.0
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

/** eligible = 보험 제휴 시뮬레이션 기준 충족(실제 할인 확정이 아님). monthBelt·monthRest 는 한 달 전체의 좌석·분 기준 값. */
data class MonthlyReport(val monthScore: Double, val monthBelt: Double, val monthRest: Double, val eligible: Boolean, val payload: InsurerPayload?)

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
                    events += Event(EventType.BELT, "${seat.label}, 벨트 매 주세요", seat)
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
                events += Event(EventType.REST, "${cont / 60}시간 달렸어요. 다음 휴게소에서 쉬어 갈까요?")
            }
            if (t.minutes >= Rules.ERGO_TIP_AT_MIN && !t.ergoTipGiven) {
                t = t.copy(ergoTipGiven = true)
                events += Event(EventType.ERGO_TIP, "한 시간 달렸어요. 에르고 모션 켜 볼까요?")
            }
        } else if (s.parked) {
            t = t.copy(parkMin = t.parkMin + dt)
            if (t.parkMin >= Rules.REST_RESET_PARK_MIN && t.continuousMin > 0) {
                t = t.copy(continuousMin = 0, restSuggested = false, restTaken = true, postureOffered = false)
                events += Event(EventType.REST_OK, "잘 쉬었어요. 다시 2시간 셀게요")
            }
        }

        // D6 휴식 뒤 자세 되돌리기 (P단 + 운전석 착석·체결일 때만, 주행 중엔 없음). 엔진은 시트를 움직이지 않는다(D2).
        val driver = s.seats[Seat.DRIVER] ?: SeatState()
        if (t.restTaken && !t.postureOffered && s.parked && driver.occupied && driver.buckled) {
            t = t.copy(postureOffered = true)
            events += Event(EventType.POSTURE, "출발 전에 원래 운전 자세로 돌려 드릴까요?")
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
        val occ = trips.sumOf { t -> t.seatMin.values.sumOf { it.occupied } }
        val buck = trips.sumOf { t -> t.seatMin.values.sumOf { it.buckled } }
        val belt = round1(if (occ > 0) buck.toDouble() / occ * 100 else 100.0)
        val rest = round1(if (minutes > 0) (1 - trips.sumOf { it.overLimitMin }.toDouble() / minutes) * 100 else 100.0)
        val eligible = belt >= Rules.BELT_GATE && rest >= Rules.REST_GATE
        return MonthlyReport(month, belt, rest, eligible, if (consent) InsurerPayload(month, eligible) else null)
    }

    /** D9 */
    fun canOpenDashboard(s: Signal): String? = if (s.moving) "멈추면 보여 드릴게요" else null

    private fun round1(x: Double) = Math.round(x * 10) / 10.0
}

/** 운전 자세 안내 — 웰니스 수준 (명세 D25·D28, mvp/engine.js postureAdvice 와 같은 규칙). */
data class PostureAdvice(val category: String, val label: String, val tips: List<String>)

object Posture {
    const val SAME_MIN_TIP = 60

    /** backrest·backrestMax = 등받이 위치 단계와 최대값(단위 없음), lumbar = 허리 받침 단계(없는 차는 null) */
    fun advice(backrest: Int, backrestMax: Int, lumbar: Int? = null, sameMin: Int = 0): PostureAdvice {
        val r = if (backrestMax > 0) backrest.toDouble() / backrestMax else 0.5
        val category = when { r < 1.0 / 3 -> "upright"; r > 2.0 / 3 -> "reclined"; else -> "normal" }
        val label = mapOf("upright" to "세움", "normal" to "보통", "reclined" to "눕힘").getValue(category)
        val tips = buildList {
            if (category == "upright") add("등받이를 한 칸 뒤로 눕히면 허리가 덜 힘들어요")
            if (category == "reclined") add("너무 눕히면 어깨가 떠요. 어깨가 등받이에 닿을 만큼만 세워 보세요")
            if (lumbar == 0) add("허리 받침을 조금 넣어 보세요")
            if (sameMin >= SAME_MIN_TIP) add("시트가 한 시간째 그대로예요. 다음에 멈추면 허리를 한번 펴 주세요")
        }
        return PostureAdvice(category, label, tips)
    }
}

/** 안내 말풍선 수명 — mvp/engine.js guideMs 와 같은 규칙. 일시 안내는 반드시 스스로 사라진다(2026-11-15 버그 수정). */
object Guide {
    const val MIN_MS = 3_500L
    const val MAX_MS = 7_000L
    private const val BASE_MS = 2_500L
    private const val PER_CHAR_MS = 60L
    fun durationMs(text: String): Long = (BASE_MS + text.codePointCount(0, text.length) * PER_CHAR_MS).coerceIn(MIN_MS, MAX_MS)
}
