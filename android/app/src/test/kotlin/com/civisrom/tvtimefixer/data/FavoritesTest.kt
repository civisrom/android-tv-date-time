package com.civisrom.tvtimefixer.data

import com.civisrom.tvtimefixer.device.DeviceInfo
import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class FavoritesTest {
    private val device = FavoriteDevice("Гостиная", DeviceAddress("192.0.2.4", 37123), "serial-1", "TV")

    @Test fun persistence_keeps_names_ports_and_identity_across_restarts() {
        val folder = Files.createTempDirectory("favorites-test").toFile()
        try {
            val file = File(folder, "favorites.bin")
            val expected = Favorites(listOf(device), listOf(FavoriteNtp("Проверенный", "pool.ntp.org")))
            FavoritesStore(file).write(expected)
            assertEquals(expected, FavoritesStore(file).read())
            val edited = expected.copy(devices = listOf(device.copy(address = DeviceAddress("192.0.2.5", 40404))))
            FavoritesStore(file).write(edited)
            assertEquals(edited, FavoritesStore(file).read())
            FavoritesStore(file).write(Favorites())
            assertEquals(Favorites(), FavoritesStore(file).read())
        } finally { folder.deleteRecursively() }
    }

    @Test fun invalid_or_excessive_entries_do_not_replace_saved_devices() {
        val folder = Files.createTempDirectory("favorites-test").toFile()
        try {
            val file = File(folder, "favorites.bin")
            val store = FavoritesStore(file)
            val valid = Favorites(listOf(device))
            store.write(valid)
            listOf(Favorites(listOf(device.copy(address = DeviceAddress("192.0.2.4", 0)))),
                Favorites(listOf(device, device)), Favorites(listOf(device.copy(serial = "unknown"))),
                Favorites(servers = listOf(FavoriteNtp("Bad", "host.example; reboot"))),
                Favorites(servers = (1..31).map { FavoriteNtp("$it", "$it.pool.ntp.org") }))
                .forEach { invalid ->
                    assertThrows(IllegalArgumentException::class.java) { store.write(invalid) }
                    assertEquals(valid, store.read())
                }
            assertFalse(File(folder, "favorites.bin.tmp").exists())
        } finally { folder.deleteRecursively() }
    }

    @Test fun replacement_failure_keeps_old_file_and_reports_failure() {
        val folder = Files.createTempDirectory("favorites-test").toFile()
        try {
            val file = File(folder, "favorites.bin")
            val store = FavoritesStore(file)
            val valid = Favorites(listOf(device)); store.write(valid)
            File(folder, "favorites.bin.tmp").mkdir()
            File(folder, "favorites.bin.tmp/block").writeText("fixture")
            assertThrows(java.io.IOException::class.java) { store.write(Favorites()) }
            assertEquals(valid, store.read())
        } finally { folder.deleteRecursively() }
    }

    @Test fun another_or_unknown_device_never_matches_a_saved_target() {
        val info = DeviceInfo(serial = device.serial, model = device.model)
        assertTrue(device.matches(info))
        assertFalse(device.matches(info.copy(serial = "another")))
        assertFalse(device.matches(info.copy(model = "another")))
        assertFalse(device.matches(null))
        assertFalse(device.matches(DeviceInfo()))
    }

    @Test fun corrupt_and_oversized_storage_is_not_silently_treated_as_empty() {
        val folder = Files.createTempDirectory("favorites-test").toFile()
        try {
            val file = File(folder, "favorites.bin")
            file.writeBytes(byteArrayOf(0, 0, 0, 2))
            assertThrows(IllegalArgumentException::class.java) { FavoritesStore(file).read() }
            file.writeBytes(ByteArray(65537))
            assertThrows(IllegalArgumentException::class.java) { FavoritesStore(file).read() }
        } finally { folder.deleteRecursively() }
    }
}
