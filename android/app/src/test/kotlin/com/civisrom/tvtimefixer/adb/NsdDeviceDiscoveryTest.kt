package com.civisrom.tvtimefixer.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class NsdDeviceDiscoveryTest {
    private val nsd = NsdManager()
    private val discovery = NsdDeviceDiscovery(Context(nsd))
    private val pair = NsdServiceInfo("expired-pair", SERVICE_TLS_PAIRING)
    private val connect = NsdServiceInfo("active-connect", SERVICE_TLS_CONNECT)

    @After fun close() { discovery.close(); Build.VERSION.SDK_INT = 34 }

    @Test fun `API 34 missing first update cannot block another service`() {
        Build.VERSION.SDK_INT = 34
        discovery.start()
        nsd.discovery.getValue(SERVICE_TLS_PAIRING).onServiceFound(pair)
        nsd.discovery.getValue(SERVICE_TLS_CONNECT).onServiceFound(connect)
        assertEquals(2, nsd.callbacks.size)
        nsd.callbacks[1].onServiceUpdated(connect)
        assertEquals(listOf("active-connect"), discovery.state.value.devices.map { it.name })
    }

    @Test fun `stop restart ignores old discoveries updates and failures`() {
        discovery.start()
        val oldListener = nsd.discovery.getValue(SERVICE_TLS_PAIRING)
        oldListener.onServiceFound(pair)
        discovery.stop()
        assertEquals(1, nsd.unregistered.size)
        discovery.start()
        oldListener.onServiceFound(connect)
        oldListener.onStartDiscoveryFailed(SERVICE_TLS_PAIRING, 1)
        nsd.callbacks[0].onServiceUpdated(pair)
        assertTrue(discovery.state.value.devices.isEmpty())
        assertTrue(discovery.state.value.available)
        assertEquals(1, nsd.callbacks.size)
        nsd.discovery.getValue(SERVICE_TLS_CONNECT).onServiceFound(connect)
        assertEquals(2, nsd.callbacks.size)
        discovery.stop()
        nsd.callbacks[1].onServiceUpdated(connect)
        assertTrue(discovery.state.value.devices.isEmpty())
        assertFalse(discovery.state.value.searching)
    }

    @Test fun `duplicate found is deduplicated and lost service invalidates callback`() {
        discovery.start()
        val listener = nsd.discovery.getValue(SERVICE_TLS_PAIRING)
        listener.onServiceFound(pair)
        listener.onServiceFound(pair)
        assertEquals(1, nsd.callbacks.size)
        nsd.callbacks[0].onServiceUpdated(pair)
        listener.onServiceLost(pair)
        assertEquals(1, nsd.unregistered.size)
        listener.onServiceFound(pair)
        nsd.callbacks[1].onServiceUpdated(pair)
        nsd.callbacks[0].onServiceLost()
        assertEquals(1, discovery.state.value.devices.size)
    }

    @Test fun `registration failure permits retry without blocking other services`() {
        discovery.start()
        val listener = nsd.discovery.getValue(SERVICE_TLS_PAIRING)
        listener.onServiceFound(pair)
        nsd.callbacks[0].onServiceInfoCallbackRegistrationFailed(1)
        listener.onServiceFound(pair)
        assertEquals(2, nsd.callbacks.size)
        nsd.callbacks[1].onServiceUpdated(pair)
        assertEquals(1, discovery.state.value.devices.size)
    }

    @Test fun `API 33 serializes resolve and restarts with pending work`() {
        Build.VERSION.SDK_INT = 33
        discovery.start()
        nsd.discovery.getValue(SERVICE_TLS_PAIRING).onServiceFound(pair)
        nsd.discovery.getValue(SERVICE_TLS_CONNECT).onServiceFound(connect)
        assertEquals(1, nsd.resolves.size)
        nsd.resolves[0].onResolveFailed(pair, 1)
        assertEquals(2, nsd.resolves.size)
        discovery.stop()
        discovery.start()
        nsd.discovery.getValue(SERVICE_TLS_CONNECT).onServiceFound(connect)
        assertEquals(3, nsd.resolves.size)
        nsd.resolves[1].onServiceResolved(connect)
        assertTrue(discovery.state.value.devices.isEmpty())
        nsd.resolves[2].onServiceResolved(connect)
        assertEquals(1, discovery.state.value.devices.size)
    }
}
