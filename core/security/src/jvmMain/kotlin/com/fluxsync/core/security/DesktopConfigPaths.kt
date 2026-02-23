package com.fluxsync.core.security

import java.io.File

object DesktopConfigPaths {
    fun getCertPath(): File = File(getBaseConfigDirectory(), "certs/device.p12")

    fun getTrustStorePath(): File = File(getBaseConfigDirectory(), "certs/truststore.json")

    private fun getBaseConfigDirectory(): File {
        val osName = System.getProperty("os.name").orEmpty().lowercase()
        return if (osName.contains("win")) {
            val appData = System.getenv("APPDATA")
            if (!appData.isNullOrBlank()) {
                File(appData, "FluxSync")
            } else {
                File(System.getProperty("user.home"), "AppData/Roaming/FluxSync")
            }
        } else {
            File(System.getProperty("user.home"), ".config/fluxsync")
        }
    }
}
