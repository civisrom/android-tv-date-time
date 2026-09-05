package com.civisrom.tvtimefixer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Keep OS and target-SDK permission rules independent.
 */
class PermissionsTest {

    @Test
    fun `до Android 13 разрешения не нужны`() {
        assertEquals(emptyList<String>(), discoveryPermissions(23, 36))
        assertEquals(emptyList<String>(), discoveryPermissions(32, 36))
    }

    @Test
    fun `NSD does not require nearby Wi-Fi permission on Android 13 through 16`() {
        (33..36).forEach { sdk ->
            assertEquals(emptyList<String>(), discoveryPermissions(sdk, 36))
            assertEquals(emptyList<String>(), discoveryPermissions(sdk, 37))
        }
    }

    @Test
    fun `с Android 17 добавляется доступ к локальной сети`() {
        assertEquals(
            listOf("android.permission.ACCESS_LOCAL_NETWORK"),
            discoveryPermissions(37, 37),
        )
    }

    @Test
    fun `target 36 retains implicit LAN access on Android 17`() {
        assertTrue(discoveryPermissions(37, 36).isEmpty())
    }
}
