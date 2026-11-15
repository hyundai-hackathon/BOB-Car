package com.bobcar.shared.server

import android.content.Context

/**
 * One token provider per process, shared by every server call of the app (one cached ID token).
 * The app's [ServerConfig] is fixed for the process, so the first call's config is kept.
 */
object ServerClients {

    @Volatile
    private var tokens: IdTokenProvider? = null

    fun tokens(context: Context, config: ServerConfig): IdTokenProvider =
        tokens ?: synchronized(this) {
            tokens ?: IdTokenProvider(config, KeystoreTokenStore(context)).also { tokens = it }
        }
}
