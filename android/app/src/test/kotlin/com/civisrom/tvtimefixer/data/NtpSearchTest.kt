package com.civisrom.tvtimefixer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Поиск сервера времени.
 *
 * Промах здесь выглядит для человека как «поиск не работает», и отличить его
 * от собственной опечатки нельзя — поэтому проверяется машиной, а не глазами.
 */
class NtpSearchTest {

    private fun servers(query: String) = searchNtpServers(query).map { it.server }

    @Test
    fun `код страны находит её сервер`() {
        assertTrue("ru.pool.ntp.org" in servers("ru"))
        assertTrue("by.pool.ntp.org" in servers("by"))
        assertTrue("kz.pool.ntp.org" in servers("kz"))
    }

    @Test
    fun `код в верхнем регистре находит то же самое`() {
        assertEquals(servers("ru"), servers("RU"))
        assertEquals(servers("kz"), servers("Kz"))
    }

    @Test
    fun `английское название находит страну`() {
        assertTrue("ru.pool.ntp.org" in servers("Russia"))
        assertTrue("kz.pool.ntp.org" in servers("kazakhstan"))
    }

    @Test
    fun `русское название находит страну`() {
        // Подсказка на экране обещает поиск по названию, и на русском тоже
        assertTrue("ru.pool.ntp.org" in servers("Россия"))
        assertTrue("ru.pool.ntp.org" in servers("россия"))
        assertTrue("by.pool.ntp.org" in servers("Беларусь"))
        assertTrue("kz.pool.ntp.org" in servers("Казахстан"))
    }

    @Test
    fun `падежные формы находят страну`() {
        // Ровно то, на чём поиск спотыкался на живом устройстве: справочник
        // хранит «Россия», а русская клавиатура подставляет «России» —
        // родительный падеж встречается в текстах чаще, и автодополнение
        // предлагает его. Точное сравнение отвечало пустотой на правильный ввод
        assertTrue("ru.pool.ntp.org" in servers("России"))
        assertTrue("ru.pool.ntp.org" in servers("Россию"))
        assertTrue("ru.pool.ntp.org" in servers("Россией"))
        assertTrue("am.pool.ntp.org" in servers("Армении"))
        assertTrue("by.pool.ntp.org" in servers("Беларуси"))
        assertTrue("kz.pool.ntp.org" in servers("Казахстане"))
    }

    @Test
    fun `укорачивание не срабатывает, пока есть точное совпадение`() {
        // Иначе выдача при обычном вводе поехала бы: точное совпадение обязано
        // отдавать ровно себя, а не расширенный отбрасыванием букв список
        assertEquals(listOf("kz.pool.ntp.org"), servers("Казахстан"))
        assertEquals(listOf("kz.pool.ntp.org"), servers("kz"))
        assertEquals(listOf("time.cloudflare.com"), servers("cloudflare"))
        // «Беларусь» целиком совпадает только с одной страной; будь запрос
        // укорочен, сюда попал бы и Кипр — в «belarus» и «cyprus» есть «ru»
        assertEquals(listOf("by.pool.ntp.org"), servers("Беларусь"))
    }

    @Test
    fun `часть названия достаточно`() {
        assertTrue("ru.pool.ntp.org" in servers("осси"))
        assertTrue("ru.pool.ntp.org" in servers("ussia"))
    }

    @Test
    fun `альтернативные серверы ищутся по адресу`() {
        assertTrue("time.cloudflare.com" in servers("cloudflare"))
        assertTrue("time.google.com" in servers("google"))
        assertTrue(servers("vniiftri").isNotEmpty())
    }

    @Test
    fun `у страны сохраняется её описание, у альтернативного адреса - нет`() {
        val country = searchNtpServers("Россия").first { it.server == "ru.pool.ntp.org" }
        assertEquals("ru", country.country?.code)
        assertEquals("Россия", country.country?.nameRu)
        assertEquals("Russia", country.country?.nameEn)

        val alternative = searchNtpServers("cloudflare").first()
        assertEquals(null, alternative.country)
    }

    @Test
    fun `пустой запрос ничего не ищет`() {
        assertTrue(searchNtpServers("").isEmpty())
        assertTrue(searchNtpServers("   ").isEmpty())
    }

    @Test
    fun `бессмысленный запрос ничего не находит`() {
        assertTrue(searchNtpServers("такой страны нет").isEmpty())
    }

    @Test
    fun `список ограничен, чтобы экран не заливало`() {
        // 'a' встречается едва ли не везде
        assertTrue(searchNtpServers("a").size <= 12)
    }
}
