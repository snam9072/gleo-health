package com.gleohealth.app

import com.gleohealth.app.engine.Trip
import com.gleohealth.app.vehicle.VehicleSnapshot

/** 화면과 Gleo 액션 수신기가 같은 상태를 보도록 하는 앱 안 저장소 (기기 밖으로 내보내지 않음 — D11). */
object AppState {
    @Volatile var snapshot = VehicleSnapshot()
    @Volatile var trip = Trip()
    val monthTrips = mutableListOf<Trip>()
}
