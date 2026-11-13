package com.siddhantdalal.bikelink

import java.net.Inet4Address
import java.net.NetworkInterface

/** Where this phone is on the network, and whether that's the iPhone's Personal Hotspot. */
object HotspotLink {

    // An iPhone's Personal Hotspot always gives out addresses in 172.20.10.0/28, over Wi-Fi and over Bluetooth.
    private const val IPHONE_HOTSPOT_PREFIX = "172.20.10."

    /** The phone's IPv4 address on Wi-Fi or Bluetooth tethering, e.g. 172.20.10.3, or null if it has none. */
    fun localAddress(): String? =
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && (it.name.startsWith("wlan") || it.name == "bt-pan") }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull()
            ?.hostAddress

    fun isIphoneHotspot(address: String): Boolean = address.startsWith(IPHONE_HOTSPOT_PREFIX)
}
