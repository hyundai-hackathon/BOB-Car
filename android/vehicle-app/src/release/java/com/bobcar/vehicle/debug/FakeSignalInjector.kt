package com.bobcar.vehicle.debug

import android.content.Context
import com.bobcar.vehicle.driving.DrivingMonitor
import com.bobcar.vehicle.gateway.CarPropertyGateway
import com.bobcar.vehicle.gateway.VehicleGateway
import com.bobcar.vehicle.signal.VehicleSignals

/** Release build: fake signals are not accepted. The debug version lives in src/debug. */
object FakeSignalInjector {
    @Suppress("UNUSED_PARAMETER")
    fun register(
        context: Context,
        signals: VehicleSignals,
        vehicle: VehicleGateway,
        carProperty: CarPropertyGateway,
        monitor: DrivingMonitor,
    ): () -> Unit = {}
}
