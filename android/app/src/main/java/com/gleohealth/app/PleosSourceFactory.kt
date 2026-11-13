package com.gleohealth.app

import android.content.Context
import com.gleohealth.app.vehicle.VehicleSource

/** Pleos Vehicle SDK 를 쓸 수 있으면 그 구현을, 아니면 null(→ 시연용 수동 신호). */
object PleosSourceFactory {
    fun createOrNull(context: Context): VehicleSource? = runCatching {
        com.gleohealth.app.vehicle.PleosVehicleSource(context).takeIf { it.isSupported() }
    }.getOrNull()
}
