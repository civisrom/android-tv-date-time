package com.civisrom.tvtimefixer.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Разбор типа mDNS-сервиса.
 *
 * Промах здесь не падает и не логируется: устройство просто не попадает в
 * список, как будто в сети никого нет. А отдают тип прошивки по-разному —
 * с точкой на конце, с доменом, в другом регистре.
 */
class DeviceDiscoveryTest {

    @Test
    fun `три типа adb разбираются`() {
        assertEquals(
            DiscoveredDevice.Kind.AWAITING_PAIRING,
            serviceKindOf("_adb-tls-pairing._tcp"),
        )
        assertEquals(
            DiscoveredDevice.Kind.READY_TO_CONNECT,
            serviceKindOf("_adb-tls-connect._tcp"),
        )
        assertEquals(DiscoveredDevice.Kind.LEGACY, serviceKindOf("_adb._tcp"))
    }

    @Test
    fun `завершающая точка и домен не мешают`() {
        val expected = DiscoveredDevice.Kind.READY_TO_CONNECT
        assertEquals(expected, serviceKindOf("_adb-tls-connect._tcp."))
        assertEquals(expected, serviceKindOf("_adb-tls-connect._tcp.local."))
        assertEquals(expected, serviceKindOf("_adb-tls-connect._tcp.local"))
        assertEquals(expected, serviceKindOf("  _adb-tls-connect._tcp.  "))
    }

    @Test
    fun `регистр не имеет значения`() {
        assertEquals(DiscoveredDevice.Kind.LEGACY, serviceKindOf("_ADB._TCP"))
        assertEquals(
            DiscoveredDevice.Kind.AWAITING_PAIRING,
            serviceKindOf("_ADB-TLS-Pairing._Tcp.Local."),
        )
    }

    @Test
    fun `спаривание и подключение не путаются между собой`() {
        // Порты у них разные, и подставить один вместо другого — самая частая
        // причина неудачи; здесь это должно расходиться жёстко
        assertEquals(
            DiscoveredDevice.Kind.AWAITING_PAIRING,
            serviceKindOf(SERVICE_TLS_PAIRING),
        )
        assertEquals(
            DiscoveredDevice.Kind.READY_TO_CONNECT,
            serviceKindOf(SERVICE_TLS_CONNECT),
        )
    }

    @Test
    fun `чужие сервисы отбрасываются`() {
        assertNull(serviceKindOf("_googlecast._tcp"))
        assertNull(serviceKindOf("_http._tcp.local."))
        assertNull(serviceKindOf(""))
        assertNull(serviceKindOf("_adb._udp"))
    }
}
