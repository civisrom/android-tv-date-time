package com.civisrom.tvtimefixer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Набор разрешений — это то, что решает, увидит ли пользователь хоть одно
 * устройство. Ошибка здесь не падает и не логируется: mDNS просто возвращает
 * пустой список, как будто в сети никого нет.
 */
class PermissionsTest {

    @Test
    fun `до Android 13 разрешения не нужны`() {
        assertEquals(emptyList<String>(), discoveryPermissions(23))
        assertEquals(emptyList<String>(), discoveryPermissions(32))
    }

    @Test
    fun `с Android 13 нужен доступ к устройствам поблизости`() {
        assertEquals(
            listOf("android.permission.NEARBY_WIFI_DEVICES"),
            discoveryPermissions(33),
        )
        assertEquals(discoveryPermissions(33), discoveryPermissions(36))
    }

    @Test
    fun `с Android 17 добавляется доступ к локальной сети`() {
        assertEquals(
            listOf(
                "android.permission.NEARBY_WIFI_DEVICES",
                "android.permission.ACCESS_LOCAL_NETWORK",
            ),
            discoveryPermissions(37),
        )
    }

    @Test
    fun `набор только растёт с версией`() {
        // Разрешение, снятое на более новой версии, означало бы неработающее
        // обнаружение ровно на ней — такой промах взглядом не ловится
        val versions = listOf(23, 30, 33, 34, 35, 36, 37, 38)
        versions.zipWithNext().forEach { (older, newer) ->
            assertTrue(
                "на API $newer пропало разрешение, бывшее на API $older",
                discoveryPermissions(newer).containsAll(discoveryPermissions(older)),
            )
        }
    }
}
