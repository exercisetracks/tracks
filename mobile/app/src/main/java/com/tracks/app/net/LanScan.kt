// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import java.net.Inet4Address

/**
 * Finding a Tracks server on the network the phone is already on.
 *
 * ## Why a sweep rather than mDNS
 *
 * The tidy answer is service discovery: the server advertises `_tracks._tcp`
 * and the phone asks. It is also an answer that does not work on the
 * deployment this app is written for. Tracks ships as Docker Compose, and a
 * container cannot advertise on the LAN without host networking or a separate
 * mDNS responder — so shipping only mDNS would mean a discovery feature that
 * silently finds nothing on every existing install, including the one being
 * tested against.
 *
 * A sweep needs no server change at all. It asks the same question the app
 * already asks — `/capabilities`, does this identify as Tracks — of every
 * address on the subnet, and the server that is already running answers it.
 *
 * ## What it costs
 *
 * A /24 is 254 addresses. Unused ones do not refuse the connection, they say
 * nothing, so each costs the full timeout — which is why the ports are ordered
 * by likelihood and the sweep stops at the first hit. In the documented
 * deployment the answer arrives in the first pass.
 *
 * Deliberately not run on its own. It is a button someone presses during
 * onboarding, on a network they chose to be on, looking for a machine they own.
 */
object LanScan {

    /** The phone's own IPv4 address and prefix on the active network. */
    data class Subnet(val address: String, val prefixLength: Int)

    /**
     * Where this phone sits, or null when it is not on a network worth sweeping.
     *
     * IPv4 only. A v6 subnet is 2^64 addresses and cannot be swept at all —
     * which is the honest reason, not an oversight — and every self-hosted LAN
     * this is aimed at has v4 on it.
     */
    fun subnet(context: Context): Subnet? {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: return null
        val active = manager.activeNetwork ?: return null
        val properties: LinkProperties = manager.getLinkProperties(active) ?: return null

        return properties.linkAddresses
            .firstOrNull { it.address is Inet4Address && !it.address.isLoopbackAddress }
            ?.let { Subnet(it.address.hostAddress ?: return null, it.prefixLength) }
    }

    /**
     * Every address on [subnet] except the phone's own, or empty if too large.
     *
     * The cap is a refusal, not a truncation. A /16 is 65,534 addresses and
     * sweeping a fraction of it would report "no server found" after a long
     * wait while never having looked where the server was — worse than saying
     * up front that this network is too big to search.
     *
     * Network and broadcast addresses are skipped: nothing listens on them, and
     * on some networks the broadcast probe is answered by everything at once.
     */
    fun hosts(subnet: Subnet, maxHosts: Int = MAX_HOSTS): List<String> {
        val reported = subnet.prefixLength
        if (reported < 1 || reported > 32) return emptyList()

        // A /31 or /32 says "this phone is the only host on its network", which
        // is almost never the truth on Wi-Fi — some routers, client-isolation
        // setups and VPNs report it while a real LAN sits behind them. Refusing
        // there would turn "find it on my network" into a dead end on exactly
        // those networks. So assume the near-universal /24 around this address
        // and sweep that instead. A genuine point-to-point /31 loses nothing:
        // its one neighbour is inside that /24 anyway.
        val prefix = if (reported >= 31) 24 else reported

        val size = 1L shl (32 - prefix)
        if (size - 2 > maxHosts) return emptyList()

        val own = subnet.address.toIpv4() ?: return emptyList()
        val mask = if (prefix == 0) 0L else (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
        val network = own and mask
        val broadcast = network or (size - 1)

        return ((network + 1) until broadcast)
            .filter { it != own }
            .map { it.toIpv4String() }
    }

    /**
     * Base URLs to try, ordered so the documented deployment is found first.
     *
     * The order is the whole performance story. Every address is tried on one
     * port before any is tried on the next, because a server is far more likely
     * to be on the default port at an unexpected address than on an unexpected
     * port at any address — and each pass that finds nothing is a few seconds.
     *
     * `/api` before the bare origin within a port, for the reason
     * [com.tracks.core.api.apiBaseCandidates] gives: it is where Caddy puts the
     * API in the shipped compose file.
     *
     * Only three ports, and 8000 is not among them despite being the backend's
     * own. The shipped compose binds everything except Caddy to 127.0.0.1, so
     * the backend port is not reachable across the network by design — sweeping
     * for it would be 254 addresses of guaranteed silence.
     */
    fun candidates(hosts: List<String>): List<String> =
        PORTS.flatMap { (scheme, port) ->
            val suffix = if (port == null) "" else ":$port"
            hosts.flatMap { host ->
                listOf("$scheme://$host$suffix/api", "$scheme://$host$suffix")
            }
        }

    /**
     * The origin to show and store, given the API base that answered.
     *
     * The user typed nothing here, so what goes in the field afterwards is
     * whatever the sweep found — and it should be the address they would use in
     * a browser, not the API path behind it.
     */
    fun originOf(apiBase: String): String = apiBase.removeSuffix("/api")

    /**
     * Ports worth sweeping, in order.
     *
     * 4080 is `TRACKS_PORT`'s default and the only port the shipped compose
     * exposes beyond loopback. 80 and 443 cover a deployment put behind
     * someone's own reverse proxy, which is the other setup people run.
     */
    private val PORTS: List<Pair<String, Int?>> = listOf(
        "http" to 4080,
        "http" to null,
        "https" to null,
    )

    /** A /24 and a little more. Beyond this a sweep is the wrong tool. */
    const val MAX_HOSTS = 1024
}

/** Dotted quad to a number, or null when it is not one. */
internal fun String.toIpv4(): Long? {
    val parts = split(".")
    if (parts.size != 4) return null
    var value = 0L
    for (part in parts) {
        val octet = part.toIntOrNull() ?: return null
        if (octet !in 0..255) return null
        value = (value shl 8) or octet.toLong()
    }
    return value
}

internal fun Long.toIpv4String(): String =
    "${(this shr 24) and 0xFF}.${(this shr 16) and 0xFF}.${(this shr 8) and 0xFF}.${this and 0xFF}"
