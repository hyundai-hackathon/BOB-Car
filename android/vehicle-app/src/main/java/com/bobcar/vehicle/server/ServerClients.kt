package com.bobcar.vehicle.server

import android.content.Context

/** One token provider per process, shared by the upload worker and server reads (one cached ID token). */
object ServerClients {

    @Volatile
    private var tokens: IdTokenProvider? = null

    fun tokens(context: Context): IdTokenProvider =
        tokens ?: synchronized(this) {
            tokens ?: IdTokenProvider(ServerConfig.current, KeystoreTokenStore(context)).also { tokens = it }
        }
}
