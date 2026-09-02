package com.civisrom.tvtimefixer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Проверяет данные, сгенерированные из shared/ntp-data.json. Числа здесь
 * намеренно жёсткие: если справочник поменяется, тест обязан упасть и заставить
 * пересмотреть обе половины проекта, а не тихо разъехаться с десктопом.
 */
class NtpDataTest {

    @Test
    fun `справочник стран не пуст и без дубликатов`() {
        assertEquals(77, NtpData.countries.size)
        assertEquals(NtpData.countries.size, NtpData.countries.map { it.code }.distinct().size)
    }

    @Test
    fun `альтернативных серверов ровно столько же, сколько в десктопной версии`() {
        assertEquals(45, NtpData.alternativeServers.size)
    }

    @Test
    fun `каждый адрес в справочнике проходит собственную валидацию`() {
        // Иначе можно было бы предложить пользователю адрес, который программа
        // сама же и отвергнет при вводе вручную
        NtpData.allServers.forEach { server ->
            assertTrue("не проходит валидацию: $server", isValidNtpServer(server))
        }
    }

    @Test
    fun `коды стран двухбуквенные и в нижнем регистре`() {
        NtpData.countries.forEach { country ->
            assertTrue("странный код: ${country.code}", Regex("^[a-z]{2}$").matches(country.code))
        }
    }

    @Test
    fun `названия заполнены на обоих языках`() {
        NtpData.countries.forEach {
            assertTrue("пустое en-название у ${it.code}", it.nameEn.isNotBlank())
            assertTrue("пустое ru-название у ${it.code}", it.nameRu.isNotBlank())
        }
    }

    @Test
    fun `поиск по коду работает`() {
        assertEquals("ru.pool.ntp.org", NtpData.byCode["ru"]?.server)
        assertEquals("Россия", NtpData.byCode["ru"]?.nameRu)
    }
}
