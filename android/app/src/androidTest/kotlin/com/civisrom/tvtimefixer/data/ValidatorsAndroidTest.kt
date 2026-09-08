package com.civisrom.tvtimefixer.data

import org.junit.Assert.*
import org.junit.Test

/** Android использует Unicode-классы регулярных выражений, в отличие от JVM по умолчанию. */
class ValidatorsAndroidTest {
    @Test fun addresses_and_pairing_codes_require_ASCII_digits_on_Android() {
        for (address in listOf("１９２.１６８.１.２０", "١٩٢.١٦٨.١.٢٠")) {
            assertFalse(isValidIpv4(address))
            assertFalse(isValidNtpServer(address))
            assertNull(parseDeviceAddress(address))
            assertNull(parseDeviceAddress("$address:5555"))
        }
        for (code in listOf("１２３４５６", "١٢٣٤٥٦")) assertFalse(isValidPairingCode(code))
        for (port in listOf("５５５５", "٥٥٥٥")) assertNull(parseDeviceAddress("192.168.1.20:$port"))
        assertEquals(DeviceAddress("192.168.1.20", 5555), parseDeviceAddress("192.168.1.20:5555"))
        assertTrue(isValidPairingCode("123456"))
    }
}
