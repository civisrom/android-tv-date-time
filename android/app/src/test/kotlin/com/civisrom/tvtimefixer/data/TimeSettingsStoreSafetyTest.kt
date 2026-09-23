package com.civisrom.tvtimefixer.data

import com.civisrom.tvtimefixer.device.*
import java.io.File
import java.io.IOException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TimeSettingsStoreSafetyTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun saved(id: Char, raw: String = "") = SavedTimeSettings(
        TimeDeviceIdentity(id.toString().repeat(64), DeviceIdentityKind.SERIAL), 100L,
        TimeSettings(mapOf(TimeSetting.NTP to raw, TimeSetting.AUTO_TIME to "null",
            TimeSetting.AUTO_TIME_ZONE to "0", TimeSetting.TIME_ZONE to "UTC")))

    @Test fun `failed temporary write preserves the previously readable baseline byte for byte`() {
        val file = File(temporary.root, "time.bin")
        val store = TimeSettingsStore(file)
        val first = saved('a', "old.example")
        store.saveSnapshot(first)
        val bytes = file.readBytes()
        val blockedTemporary = File(temporary.root, "time.bin.tmp")
        assertTrue(blockedTemporary.mkdir())
        File(blockedTemporary, "block").writeText("temporary write fixture")
        assertThrows(IOException::class.java) { store.saveSnapshot(saved('a', "new.example"), replace = true) }
        assertArrayEquals(bytes, file.readBytes())
        assertEquals(listOf(first), TimeSettingsStore(file).read().snapshots)
    }

    @Test fun `replacing a profile for one identity preserves the same named profile for another identity`() {
        val file = File(temporary.root, "time.bin")
        val store = TimeSettingsStore(file)
        val a = TimeProfile("Home", saved('a', "a.example"))
        val b = TimeProfile("Home", saved('b', "b.example"))
        store.saveProfile(a)
        store.saveProfile(b)
        val replacement = TimeProfile("Home", saved('a', "replacement.example"))
        store.saveProfile(replacement, replace = true)
        assertEquals(setOf(b, replacement), TimeSettingsStore(file).read().profiles.toSet())
    }

    @Test fun `truncated valid storage cannot be silently overwritten with a new baseline`() {
        val file = File(temporary.root, "time.bin")
        val store = TimeSettingsStore(file)
        store.saveSnapshot(saved('a', "null"))
        val truncated = file.readBytes().dropLast(1).toByteArray()
        file.writeBytes(truncated)
        assertThrows(IOException::class.java) { store.saveSnapshot(saved('b')) }
        assertArrayEquals(truncated, file.readBytes())
    }
}
