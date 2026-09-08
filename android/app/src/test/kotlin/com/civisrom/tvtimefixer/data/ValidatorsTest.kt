package com.civisrom.tvtimefixer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ValidatorsTest {

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
        for (port in listOf("+5555", "-5555", "", "55 55", "５５５５")) {
            assertNull(port, parseDeviceAddress("192.168.1.20:$port"))
        }
        assertEquals(DeviceAddress("192.168.1.20", 1), parseDeviceAddress("192.168.1.20:1"))
        assertEquals(DeviceAddress("192.168.1.20", 65535), parseDeviceAddress("192.168.1.20:65535"))
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
