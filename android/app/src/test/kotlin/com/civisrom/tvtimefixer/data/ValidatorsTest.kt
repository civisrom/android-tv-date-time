package com.civisrom.tvtimefixer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ValidatorsTest {

    @Test fun `IPv6 ADB round trip preserves scopes and explicit ports`() {
        for (host in listOf("::1", "2001:db8::123", "fe80::1%wlan0", "fe80::2%3", "::ffff:192.0.2.1")) {
            val endpoint = DeviceAddress(host, 37105)
            assertEquals(endpoint, parseDeviceAddress(endpoint.toString()))
            assertEquals(DeviceAddress(host, DEFAULT_ADB_PORT), parseDeviceAddress(host))
            assertTrue(hasExplicitDevicePort(endpoint.toString()))
            assertFalse(hasExplicitDevicePort(host))
        }
        assertEquals(DeviceAddress("::1", DEFAULT_ADB_PORT), parseDeviceAddress("[::1]"))
        assertFalse(hasExplicitDevicePort("[::1]"))
    }

    @Test fun `IPv6 NTP literals have no URI brackets port or interface scope`() {
        for (host in listOf("::1", "2001:db8::123", "::ffff:192.0.2.1")) assertTrue(host, isValidNtpServer(host))
        for (host in listOf("[::1]", "[::1]:123", "fe80::1%wlan0", "::ffff:010.0.0.1")) {
            assertFalse(host, isValidNtpServer(host))
        }
    }

    @Test fun `malformed IPv6 cannot turn into DNS or an implicit port`() {
        for (value in listOf("a:b", "1:2:3", "1:::2", "[::1]:0", "[::1]:65536", "[::1]:", "[::1]:+123",
            "[::1]:５５５５", "[::1]:000001", "[127.0.0.1]:5555", "[::1]garbage", "::1%", "::1%bad/name",
            "fe80::1%wlan0%2", "::ffff:010.0.0.1", "::1;id", "bad.example::1")) {
            assertNull(value, parseDeviceAddress(value))
        }
    }

    @Test
    fun `leading zero IPv4 octets cannot select a different ADB or NTP destination`() {
        for (address in listOf("010.0.0.1", "192.168.010.005", "127.0.0.01", "00.0.0.0")) {
            assertFalse(address, isValidIpv4(address))
            assertFalse(address, isValidNtpServer(address))
            assertNull(address, parseDeviceAddress(address))
            assertNull(address, parseDeviceAddress("$address:5555"))
        }
        assertTrue(isValidIpv4("0.0.0.0"))
        assertTrue(isValidNtpServer("192.168.10.5"))
        assertEquals(DeviceAddress("10.0.0.1", 5555), parseDeviceAddress("10.0.0.1:5555"))
    }

    @Test
    fun `NTP-сервер принимает домен и IPv4`() {
        assertTrue(isValidNtpServer("time.google.com"))
        assertTrue(isValidNtpServer("ru.pool.ntp.org"))
        assertTrue(isValidNtpServer("192.168.1.1"))
        assertTrue(isValidNtpServer("  time.google.com  "))
    }

    @Test
    fun `NTP-сервер отвергает мусор`() {
        assertFalse(isValidNtpServer(""))
        assertFalse(isValidNtpServer("-bad.example"))
        assertFalse(isValidNtpServer("not a host"))
        assertFalse(isValidNtpServer("999.0.0.1"))
        for (server in listOf("time.-pool.org", "time.pool-.org", "time..org",
            "https://time.google.com", "time.google.com:123", "192.168.1.1:123")) {
            assertFalse(server, isValidNtpServer(server))
        }
    }

    @Test
    fun `домен ограничен 253 символами и 63 символами в каждой метке`() {
        val longest = List(3) { "a".repeat(63) }.plus("a".repeat(61)).joinToString(".")
        assertTrue(isValidNtpServer(longest))
        assertFalse(isValidNtpServer(longest + "a"))
        assertFalse(isValidNtpServer("a".repeat(64) + ".org"))
        assertFalse(isValidNtpServer("time." + "a".repeat(64)))
        assertTrue(isValidNtpServer("time.pool-1.org"))
    }

    @Test
    fun `адрес без порта получает порт по умолчанию`() {
        assertEquals(DeviceAddress("192.168.1.20", DEFAULT_ADB_PORT), parseDeviceAddress("192.168.1.20"))
    }

    @Test
    fun `адрес с портом разбирается`() {
        assertEquals(DeviceAddress("192.168.1.20", 37105), parseDeviceAddress("192.168.1.20:37105"))
    }

    @Test
    fun `некорректный порт не подменяется молча на умолчание`() {
        // Десктопная версия ведёт себя так же: опечатка в порте должна быть
        // отвергнута, а не превращена в обращение к 5555
        assertNull(parseDeviceAddress("192.168.1.20:0"))
        assertNull(parseDeviceAddress("192.168.1.20:65536"))
        assertNull(parseDeviceAddress("192.168.1.20:abc"))
        assertNull(parseDeviceAddress("999.0.0.1:5555"))
        assertNull(parseDeviceAddress(""))
        for (port in listOf("+5555", "-5555", "", "55 55", "５５５５", "000001")) {
            assertNull(port, parseDeviceAddress("192.168.1.20:$port"))
        }
        assertEquals(DeviceAddress("192.168.1.20", 1), parseDeviceAddress("192.168.1.20:1"))
        assertEquals(DeviceAddress("192.168.1.20", 65535), parseDeviceAddress("192.168.1.20:65535"))
    }

    @Test
    fun `Unicode-цифры не допускаются в IP и коде спаривания`() {
        for (address in listOf("１９２.１６８.１.２０", "١٩٢.١٦٨.١.٢٠")) {
            assertFalse(isValidIpv4(address))
            assertFalse(isValidNtpServer(address))
            assertNull(parseDeviceAddress("$address:5555"))
        }
        for (code in listOf("１２３４５６", "١٢٣٤٥٦")) assertFalse(isValidPairingCode(code))
    }

    @Test
    fun `код спаривания — ровно шесть цифр`() {
        assertTrue(isValidPairingCode("123456"))
        assertTrue(isValidPairingCode(" 123456 "))
        assertFalse(isValidPairingCode("12345"))
        assertFalse(isValidPairingCode("1234567"))
        assertFalse(isValidPairingCode("12345a"))
        assertFalse(isValidPairingCode(""))
    }
}
