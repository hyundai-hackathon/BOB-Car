package com.bobcar.vehicle.server

import com.bobcar.shared.server.ServerConfig
import com.bobcar.vehicle.BuildConfig

/** This build's server ([ServerConfig], shared module) and this vehicle's app-made ID (no VIN). */
data class VehicleServer(val config: ServerConfig, val vehicleId: String) {

    /** A server is set and allowed for this build, sign-in can work, and the vehicle ID is known. */
    val configured: Boolean get() = config.configured && vehicleId.isNotEmpty()

    companion object {
        val current: VehicleServer by lazy {
            VehicleServer(
                ServerConfig(
                    serverUrl = BuildConfig.SERVER_URL.trim(),
                    authUrl = BuildConfig.AUTH_URL.trim(),
                    apiKey = BuildConfig.FIREBASE_API_KEY.trim(),
                    debug = BuildConfig.DEBUG,
                ),
                vehicleId = BuildConfig.VEHICLE_ID.trim(),
            )
        }
    }
}
