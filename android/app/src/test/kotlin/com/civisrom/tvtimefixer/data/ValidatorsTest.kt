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
