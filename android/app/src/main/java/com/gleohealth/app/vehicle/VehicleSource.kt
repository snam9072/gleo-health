package com.gleohealth.app.vehicle

import com.gleohealth.app.engine.Gear
import com.gleohealth.app.engine.Seat
import com.gleohealth.app.engine.SeatState

/**
 * 운전석 시트 위치 — Pleos Seat 의 위치 '단계'(단위 없음, 차종마다 범위가 다름). 각도로 바꾸지 않는다(명세 D28).
 * 지원하지 않는 축은 null.
 */
data class SeatPosture(
    val backrest: Int?, val backrestMin: Int = 0, val backrestMax: Int = 0,
    val lumbar: Int? = null, val lumbarMin: Int = 0, val lumbarMax: Int = 0,
) {
    /** 등받이 단계를 0부터 센 값과 범위 — Posture.advice 입력 */
    val backrestRel get() = backrest?.let { it - backrestMin }
    val backrestSpan get() = backrestMax - backrestMin
    val lumbarRel get() = lumbar?.let { it - lumbarMin }
    val lumbarSpan get() = lumbarMax - lumbarMin
}

/** 차량 신호의 현재 값. 엔진에는 1분마다 이 값으로 Signal 을 만든다. */
data class VehicleSnapshot(
    val speedKmh: Int = 0,
    val gear: Gear = Gear.P,
    val seats: Map<Seat, SeatState> = mapOf(Seat.DRIVER to SeatState(occupied = true, buckled = true)),
    val posture: SeatPosture? = null,
)

/** 차량 신호 공급원 — Pleos Vehicle SDK 구현과 시연용(수동) 구현을 같은 모양으로 쓴다. */
interface VehicleSource {
    fun start(onChange: (VehicleSnapshot) -> Unit)
    fun stop()
    val name: String
}

/** 시연용: 화면의 버튼으로 신호를 바꾼다 (에뮬레이터 Virtual Console 을 못 쓸 때). */
class ManualVehicleSource : VehicleSource {
    override val name = "시연용 수동 신호"
    private var listener: ((VehicleSnapshot) -> Unit)? = null
    var snapshot = VehicleSnapshot(posture = SeatPosture(backrest = 3, backrestMax = 9, lumbar = 0, lumbarMax = 4))
        private set

    override fun start(onChange: (VehicleSnapshot) -> Unit) { listener = onChange; onChange(snapshot) }
    override fun stop() { listener = null }

    fun update(transform: (VehicleSnapshot) -> VehicleSnapshot) {
        snapshot = transform(snapshot)
        listener?.invoke(snapshot)
    }
}
