package com.rumi.hermesvoice.phone

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.rumi.hermesvoice.core.diag.DiagConn
import com.rumi.hermesvoice.core.diag.DiagNetwork
import com.rumi.hermesvoice.core.diag.DiagTransport

/**
 * One instant's connectivity category, read from the system's own capability flags. It is context for a diagnostics report and
 * is never consulted before sending: an unvalidated network can still reach a local or VPN server. No SSID, address, operator
 * or host is read. Any failure (including a missing permission) is an honest "unknown".
 */
object PhoneNetworkSnapshot {
    fun read(context: Context): DiagNetwork {
        return try {
            readOrThrow(context)
        } catch (_: Exception) {
            DiagNetwork.UNKNOWN
        }
    }

    private fun readOrThrow(context: Context): DiagNetwork {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return DiagNetwork.UNKNOWN
        val network = manager.activeNetwork ?: return DiagNetwork(DiagConn.NONE)
        val caps = manager.getNetworkCapabilities(network) ?: return DiagNetwork(DiagConn.NONE)
        return map(
            hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            transports = buildSet {
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add(DiagTransport.WIFI)
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add(DiagTransport.CELLULAR)
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add(DiagTransport.VPN)
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add(DiagTransport.ETHERNET)
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) add(DiagTransport.BLUETOOTH)
            },
        )
    }

    internal fun map(hasInternet: Boolean, validated: Boolean, transports: Set<DiagTransport>): DiagNetwork {
        val conn = when {
            validated -> DiagConn.VALIDATED
            hasInternet -> DiagConn.UNVALIDATED
            else -> DiagConn.NONE
        }
        return DiagNetwork(conn, if (transports.isEmpty()) setOf(DiagTransport.OTHER) else transports)
    }

    fun conn(context: Context): DiagConn = read(context).conn
}
