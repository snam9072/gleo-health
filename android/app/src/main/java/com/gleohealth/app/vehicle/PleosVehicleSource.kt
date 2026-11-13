package com.gleohealth.app.vehicle

import android.car.Car
import android.car.VehicleGear
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.util.Log
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.listener.SeatBeltBuckledStateListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import com.gleohealth.app.engine.Gear
import com.gleohealth.app.engine.Seat
import com.gleohealth.app.engine.SeatState
import kotlin.math.roundToInt

/**
 * Pleos Vehicle SDK 2.3.2 로 좌석별 착석·안전벨트 체결을 구독하고(조회 권한만, 시트를 움직이지 않음 — D2),
 * AAOS 표준 CarPropertyManager 로 속도(PERF_VEHICLE_SPEED)·기어(GEAR_SELECTION)를 구독한다.
 */
class PleosVehicleSource(private val context: Context) : VehicleSource {
    override val name = "Pleos Vehicle SDK"

    private val vehicle = Vehicle(context)
    private var car: Car? = null
    private var props: CarPropertyManager? = null
    private var listener: ((VehicleSnapshot) -> Unit)? = null
    private var snapshot = VehicleSnapshot(seats = Seat.entries.associateWith { SeatState() })
    private val seatListeners = mutableListOf<Pair<SeatArea, Any>>()

    // 한국·좌핸들 기준: 운전석 = 1열 왼쪽
    private val areaOf = mapOf(
        Seat.DRIVER to SeatValue.ROW_1_LEFT,
        Seat.PASSENGER to SeatValue.ROW_1_RIGHT,
        Seat.REAR_LEFT to SeatValue.ROW_2_LEFT,
        Seat.REAR_RIGHT to SeatValue.ROW_2_RIGHT,
    )

    fun isSupported(): Boolean = runCatching { Car.createCar(context) != null }.getOrDefault(false)

    private fun emit(update: (VehicleSnapshot) -> VehicleSnapshot) {
        snapshot = update(snapshot)
        listener?.invoke(snapshot)
    }

    private fun setSeat(seat: Seat, f: (SeatState) -> SeatState) =
        emit { s -> s.copy(seats = s.seats + (seat to f(s.seats[seat] ?: SeatState()))) }

    override fun start(onChange: (VehicleSnapshot) -> Unit) {
        listener = onChange
        vehicle.initialize()
        val seatApi = vehicle.getSeat()
        for ((seat, value) in areaOf) {
            val area = SeatArea(value)
            val occ = object : SeatOccupancyListener {
                override fun onSeatOccupancyUpdated(p0: Boolean?) = setSeat(seat) { it.copy(occupied = p0 == true) }
                override fun onFailed(p0: Exception) { Log.w(TAG, "occupancy $seat: $p0") }
            }
            val belt = object : SeatBeltBuckledStateListener {
                override fun onSeatBeltBuckledStateUpdated(p0: Boolean?) = setSeat(seat) { it.copy(buckled = p0 == true) }
                override fun onFailed(p0: Exception) { Log.w(TAG, "belt $seat: $p0") }
            }
            seatApi.registerSeatOccupancy(area, occ)
            seatApi.registerSeatBeltBuckledState(area, belt)
            seatListeners += area to occ
            seatListeners += area to belt
        }

        car = Car.createCar(context)
        props = car?.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
        props?.registerCallback(propCallback, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_ONCHANGE)
        props?.registerCallback(propCallback, VehiclePropertyIds.GEAR_SELECTION, CarPropertyManager.SENSOR_RATE_ONCHANGE)
        onChange(snapshot)
    }

    private val propCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            when (value.propertyId) {
                VehiclePropertyIds.PERF_VEHICLE_SPEED -> {
                    val kmh = ((value.value as? Float ?: 0f) * 3.6f).roundToInt().coerceAtLeast(0)
                    emit { it.copy(speedKmh = kmh) }
                }
                VehiclePropertyIds.GEAR_SELECTION -> {
                    val gear = when (value.value as? Int) {
                        VehicleGear.GEAR_PARK -> Gear.P
                        VehicleGear.GEAR_REVERSE -> Gear.R
                        VehicleGear.GEAR_NEUTRAL -> Gear.N
                        else -> Gear.D
                    }
                    emit { it.copy(gear = gear) }
                }
            }
        }
        override fun onErrorEvent(propId: Int, zone: Int) { Log.w(TAG, "property error $propId") }
    }

    override fun stop() {
        val seatApi = runCatching { vehicle.getSeat() }.getOrNull()
        for ((area, l) in seatListeners) {
            when (l) {
                is SeatOccupancyListener -> seatApi?.unregisterSeatOccupancy(area, l)
                is SeatBeltBuckledStateListener -> seatApi?.unregisterSeatBeltBuckledState(area, l)
            }
        }
        seatListeners.clear()
        props?.unregisterCallback(propCallback)
        car?.disconnect()
        vehicle.release()
        listener = null
    }

    private companion object { const val TAG = "GleoHealth" }
}
