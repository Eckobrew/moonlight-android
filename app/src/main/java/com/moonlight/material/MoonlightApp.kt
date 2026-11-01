package com.moonlight.material

import android.app.Application
import com.moonlight.material.core.security.CertificateManager
import com.moonlight.material.core.utils.AppLogger

class MoonlightApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLogger.info("Moonlight Material Android initialized", "APP")
        CertificateManager.initialize(this)
    }
}
