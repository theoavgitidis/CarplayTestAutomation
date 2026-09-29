package com.example.adb_connection.data.wifi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ConnectivityStateTest {

    @Test
    fun `network selection retains the latest available network despite duplicate callbacks`() {
        val selections = NetworkSelectionState<String>()

        selections.selectWifi("wifi-1")
        selections.selectWifi("wifi-1")
        selections.selectEthernet("ethernet-1")
        selections.selectEthernet("ethernet-1")

        assertEquals("wifi-1", selections.wifiNetwork())
        assertEquals("ethernet-1", selections.ethernetNetwork())
    }

    @Test
    fun `network selection safely handles concurrent selection clearing and reads`() {
        val selections = NetworkSelectionState<String>()
        val executor = Executors.newFixedThreadPool(3)
        val start = CountDownLatch(1)
        val complete = CountDownLatch(3)

        executor.execute {
            start.await()
            repeat(10_000) { selections.selectWifi("wifi-$it") }
            complete.countDown()
        }
        executor.execute {
            start.await()
            repeat(10_000) {
                selections.clearWifiIfSelected("wifi-$it")
                selections.clearEthernetIfSelected("ethernet-$it")
            }
            complete.countDown()
        }
        executor.execute {
            start.await()
            repeat(10_000) {
                selections.wifiNetwork()
                selections.ethernetNetwork()
                selections.isSelectedWifi("wifi-$it")
            }
            complete.countDown()
        }

        start.countDown()
        assertTrue(complete.await(10, TimeUnit.SECONDS))
        executor.shutdownNow()
    }

    @Test
    fun `loss only clears the network it observed while another callback selects a replacement`() {
        val selections = NetworkSelectionState<String>()
        selections.selectWifi("wifi-lost")

        val executor = Executors.newFixedThreadPool(2)
        val selectedReplacement = CountDownLatch(1)
        val lossCompleted = CountDownLatch(1)
        executor.execute {
            selections.selectWifi("wifi-replacement")
            selectedReplacement.countDown()
        }
        executor.execute {
            selectedReplacement.await()
            assertFalse(selections.clearWifiIfSelected("wifi-lost"))
            lossCompleted.countDown()
        }

        assertTrue(lossCompleted.await(10, TimeUnit.SECONDS))
        assertEquals("wifi-replacement", selections.wifiNetwork())
        selections.clearWifi()
        assertNull(selections.wifiNetwork())
        executor.shutdownNow()
    }

    @Test
    fun `losing WiFi preserves the connected Ethernet state`() {
        val connected = ConnectivityState(
            headunitWifiStatus = ConnectionStatus.CONNECTED,
            macEthernetStatus = ConnectionStatus.CONNECTED
        )

        val afterWifiLoss = connected.withHeadunitWifiStatus(ConnectionStatus.DISCONNECTED)

        assertEquals(ConnectionStatus.DISCONNECTED, afterWifiLoss.headunitWifiStatus)
        assertEquals(ConnectionStatus.CONNECTED, afterWifiLoss.macEthernetStatus)
    }

    @Test
    fun `losing Ethernet preserves the connected Headunit WiFi state`() {
        val connected = ConnectivityState(
            headunitWifiStatus = ConnectionStatus.CONNECTED,
            macEthernetStatus = ConnectionStatus.CONNECTED
        )

        val afterEthernetLoss = connected.withMacEthernetStatus(ConnectionStatus.DISCONNECTED)

        assertEquals(ConnectionStatus.CONNECTED, afterEthernetLoss.headunitWifiStatus)
        assertEquals(ConnectionStatus.DISCONNECTED, afterEthernetLoss.macEthernetStatus)
    }
}
