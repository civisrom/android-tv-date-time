package com.civisrom.tvtimefixer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Каркасный тест: проверяет, что перечисление режимов существует и различимо.
 * Сама detectDeviceMode() требует android.content.Context и покрывается
 * инструментальным тестом позже — на JVM её не вызвать.
 */
class DeviceModeTest {

    @Test
    fun `оба режима различимы`() {
        assertNotEquals(DeviceMode.HANDHELD, DeviceMode.TELEVISION)
        assertEquals(2, DeviceMode.entries.size)
    }
}
