package com.sokolovskiy.insider

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket

object NetUtil {

    data class Address(val ip: String, val iface: String) {
        /** Rough guess for the UI: hotspot interfaces are usually ap*, swlan*, wlan1. */
        val isHotspot get() = iface.startsWith("ap") || iface.startsWith("swlan") || iface == "wlan1"
    }

    /** IPv4 LAN addresses of this phone, Wi-Fi/hotspot first, mobile data excluded. */
    fun lanAddresses(): List<Address> {
        val out = mutableListOf<Address>()
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                val name = ni.name ?: continue
                if (name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("dummy") ||
                    name.startsWith("tun") || name.startsWith("ppp")
                ) continue
                for (addr in ni.inetAddresses) {
                    if (addr is Inet4Address && addr.isSiteLocalAddress) {
                        out += Address(addr.hostAddress ?: continue, name)
                    }
                }
            }
        } catch (_: Exception) {
        }
        return out.sortedBy {
            when {
                it.iface.startsWith("wlan0") -> 0
                it.isHotspot -> 1
                it.iface.startsWith("wlan") -> 2
                else -> 3
            }
        }
    }

    fun freePort(preferred: Int): Int {
        for (p in preferred until preferred + 20) {
            try {
                ServerSocket(p).use { return p }
            } catch (_: Exception) {
            }
        }
        return preferred
    }

    fun qrSvg(text: String): String {
        val hints = mapOf(EncodeHintType.MARGIN to 1, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M)
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
        val sb = StringBuilder()
        sb.append("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${m.width} ${m.height}" shape-rendering="crispEdges">""")
        sb.append("""<rect width="100%" height="100%" fill="#fff"/><path fill="#000" d="""")
        for (y in 0 until m.height) for (x in 0 until m.width) {
            if (m.get(x, y)) sb.append("M$x,${y}h1v1h-1z")
        }
        sb.append("\"/></svg>")
        return sb.toString()
    }
}
