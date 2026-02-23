package com.fluxsync.core.protocol

import kotlinx.serialization.Serializable

@Serializable
enum class ChannelType {
    WIFI,
    USB_ADB,
}
