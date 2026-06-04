package com.androiddrop

import android.app.Application
import android.content.Context

/**
 * Minimal Application subclass so non-Activity code (Uploader, the TLS trust manager)
 * can reach an application Context without plumbing it through every call.
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        ctx = applicationContext
    }

    companion object {
        lateinit var ctx: Context
            private set
    }
}
