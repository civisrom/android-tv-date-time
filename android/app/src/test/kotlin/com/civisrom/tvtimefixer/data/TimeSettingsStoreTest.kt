package com.civisrom.tvtimefixer.data

import com.civisrom.tvtimefixer.device.*
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TimeSettingsStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun saved(identity: String = "a".repeat(64), ntp: String = "ntp://original.example:8123|ntp://backup.example") =
        SavedTimeSettings(TimeDeviceIdentity(identity, DeviceIdentityKind.ANDROID_ID), 1234,
            TimeSettings(mapOf(TimeSetting.NTP to ntp, TimeSetting.AUTO_TIME to "null",
                TimeSetting.AUTO_TIME_ZONE to "0", TimeSetting.TIME_ZONE to "UTC"), false))

    @Test fun `the first snapshot survives another change and a fresh store instance`() {
        val file = File(temporary.root, "snapshots.bin")
        val store = TimeSettingsStore(file)
        val first = saved()
        store.saveSnapshot(first)
        assertEquals(first, store.saveSnapshot(saved(ntp = "later.example")))
        assertEquals(listOf(first), TimeSettingsStore(file).read().snapshots)
        assertEquals(saved(ntp = "explicit.example"), store.saveSnapshot(saved(ntp = "explicit.example"), replace = true))
        assertFalse(File(temporary.root, "snapshots.bin.tmp").exists())
    }

    @Test fun `profiles cannot overwrite another device or silently replace an existing name`() {
        val file = File(temporary.root, "snapshots.bin")
        val store = TimeSettingsStore(file)
        val a = TimeProfile("Home", saved())
        val b = TimeProfile("Home", saved("b".repeat(64)))
        store.saveProfile(a); store.saveProfile(b)
        assertThrows(IllegalArgumentException::class.java) { store.saveProfile(a) }
        store.removeProfile(a.saved.identity, "Home")
        assertEquals(listOf(b), TimeSettingsStore(file).read().profiles)
    }

    @Test fun `corruption and oversized files are rejected without replacing the saved baseline`() {
        val file = File(temporary.root, "snapshots.bin")
        file.writeBytes(byteArrayOf(0, 0, 0, 99))
        assertThrows(IllegalArgumentException::class.java) { TimeSettingsStore(file).saveSnapshot(saved()) }
        assertArrayEquals(byteArrayOf(0, 0, 0, 99), file.readBytes())
        file.writeBytes(ByteArray(512 * 1024 + 1))
        assertThrows(IllegalArgumentException::class.java) { TimeSettingsStore(file).read() }
    }
}
