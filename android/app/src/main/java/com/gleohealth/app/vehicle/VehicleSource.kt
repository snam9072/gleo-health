package com.gleohealth.app.vehicle

import com.gleohealth.app.engine.Gear
import com.gleohealth.app.engine.Seat
import com.gleohealth.app.engine.SeatState

/** 차량 신호의 현재 값. 엔진에는 1분마다 이 값으로 Signal 을 만든다. */
data class VehicleSnapshot(
    val speedKmh: Int = 0,
    val gear: Gear = Gear.P,
    val seats: Map<Seat, SeatState> = mapOf(Seat.DRIVER to SeatState(occupied = true, buckled = true)),
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
    var snapshot = VehicleSnapshot()
        private set

    override fun start(onChange: (VehicleSnapshot) -> Unit) { listener = onChange; onChange(snapshot) }
    override fun stop() { listener = null }

    fun update(transform: (VehicleSnapshot) -> VehicleSnapshot) {
        snapshot = transform(snapshot)
        listener?.invoke(snapshot)
    }
}
