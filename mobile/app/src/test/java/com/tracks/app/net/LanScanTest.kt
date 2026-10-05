// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which addresses a network sweep will actually knock on.
 *
 * Worth pinning precisely, because every mistake here is silent in the same
 * way: the sweep runs, takes its usual few seconds, and reports that no server
 * was found. Off-by-one on the broadcast address, forgetting to skip the
 * phone's own, or quietly truncating a subnet that is too large all produce
 * that identical, entirely plausible non-answer.
 */
class LanScanTest {

    private fun subnet(address: String, prefix: Int) = LanScan.Subnet(address, prefix)

    @Test
    fun `a slash 24 covers every host but the phone itself`() {
        val hosts = LanScan.hosts(subnet("192.168.1.42", 24))

        // 254 usable addresses, minus this phone.
        assertEquals(253, hosts.size)
        assertTrue("192.168.1.1" in hosts)
        assertTrue("192.168.1.254" in hosts)
        assertFalse("192.168.1.42" in hosts)
    }

    @Test
    fun `the network and broadcast addresses are skipped`() {
        val hosts = LanScan.hosts(subnet("10.0.0.5", 24))

        // Nothing listens on either, and on some networks a probe to the
        // broadcast address is answered by every host at once.
        assertFalse("10.0.0.0" in hosts)
        assertFalse("10.0.0.255" in hosts)
    }

    @Test
    fun `a small subnet yields only its real hosts`() {
        // A /30 is four addresses: network, two hosts, broadcast.
        val hosts = LanScan.hosts(subnet("192.168.5.5", 30))

        assertEquals(listOf("192.168.5.6"), hosts)
    }

    @Test
    fun `a slash 32 is swept as the surrounding slash 24`() {
        // Some Wi-Fi setups, VPNs and client-isolation networks report the phone
        // as its own /31 or /32 while a real LAN sits behind them. Refusing there
        // is a dead end on exactly those networks, so assume the /24.
        for (prefix in intArrayOf(31, 32)) {
            val hosts = LanScan.hosts(subnet("192.168.1.42", prefix))
            assertEquals("/$prefix should sweep the /24", 253, hosts.size)
            assertTrue("192.168.1.1" in hosts)
            assertTrue("192.168.1.254" in hosts)
            assertFalse("192.168.1.42" in hosts)
        }
    }

    @Test
    fun `a subnet too large to sweep is refused rather than truncated`() {
        // 65,534 addresses. Sweeping the first thousand would take just as long
        // as a real search and report "not found" without having looked where
        // the server was.
        assertTrue(LanScan.hosts(subnet("172.16.4.9", 16)).isEmpty())
        assertTrue(LanScan.hosts(subnet("10.1.2.3", 8)).isEmpty())
    }

    @Test
    fun `a slash 22 is large but still swept`() {
        val hosts = LanScan.hosts(subnet("192.168.4.10", 22))

        assertEquals(1021, hosts.size)
        // Spans the whole range, not just the phone's own third octet.
        assertTrue("192.168.4.1" in hosts)
        assertTrue("192.168.7.254" in hosts)
    }

    @Test
    fun `nonsense in gives nothing out`() {
        assertTrue(LanScan.hosts(subnet("not-an-address", 24)).isEmpty())
        assertTrue(LanScan.hosts(subnet("192.168.1.1", 0)).isEmpty())
        assertTrue(LanScan.hosts(subnet("192.168.1.1", 33)).isEmpty())
    }

    @Test
    fun `every address is tried on the likely port before any is tried elsewhere`() {
        val candidates = LanScan.candidates(listOf("10.0.0.1", "10.0.0.2"))

        // The ordering is the performance story: a server is far likelier to be
        // on the default port at an unexpected address than the reverse, and
        // each exhausted pass costs seconds.
        assertEquals(
            listOf(
                "http://10.0.0.1:4080/api",
                "http://10.0.0.1:4080",
                "http://10.0.0.2:4080/api",
                "http://10.0.0.2:4080",
            ),
            candidates.take(4),
        )
        assertTrue(candidates.indexOf("http://10.0.0.2:4080/api") < candidates.indexOf("http://10.0.0.1/api"))
    }

    @Test
    fun `plain and TLS reverse proxies are both covered`() {
        val candidates = LanScan.candidates(listOf("10.0.0.7"))

        assertTrue("http://10.0.0.7/api" in candidates)
        assertTrue("https://10.0.0.7/api" in candidates)
        // The backend's own port is not swept: the shipped compose binds it to
        // loopback, so it cannot answer across the network by design.
        assertTrue(candidates.none { it.contains(":8000") })
    }

    @Test
    fun `the origin shown afterwards is the address, not the API path`() {
        assertEquals("http://10.0.0.5:4080", LanScan.originOf("http://10.0.0.5:4080/api"))
        // A deployment answering at the origin keeps it unchanged.
        assertEquals("http://10.0.0.5:8000", LanScan.originOf("http://10.0.0.5:8000"))
    }

    @Test
    fun `addresses convert both ways`() {
        assertEquals(0L, "0.0.0.0".toIpv4())
        assertEquals(4294967295L, "255.255.255.255".toIpv4())
        assertEquals("192.168.1.42", "192.168.1.42".toIpv4()!!.toIpv4String())

        assertNull("192.168.1".toIpv4())
        assertNull("192.168.1.256".toIpv4())
        assertNull("192.168.1.x".toIpv4())
    }
}
