package com.civisrom.tvtimefixer.ui

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Сверяет values/ и values-ru/ напрямую по файлам.
 *
 * В десктопной половине проекта расхождение плейсхолдеров между языками уже
 * ловилось таким же тестом, и не зря: в Android промах превращается в
 * IllegalFormatException прямо на экране пользователя, причём только у того,
 * у кого выбран «неудачный» язык.
 */
class StringResourcesTest {

    private fun read(path: String): Map<String, String> {
        val file = File(path)
        assertTrue("нет файла $path", file.isFile)
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = document.getElementsByTagName("string")
        return (0 until nodes.length).associate { index ->
            val node = nodes.item(index)
            val name = node.attributes.getNamedItem("name").nodeValue
            name to (node.textContent ?: "")
        }
    }

    private val english by lazy { read("src/main/res/values/strings.xml") }
    private val russian by lazy { read("src/main/res/values-ru/strings.xml") }

    /** %1$s, %2$d и подобные — их набор обязан совпадать между языками. */
    private fun placeholders(value: String): List<String> =
        Regex("""%\d+\$[a-zA-Z]|%[a-zA-Z]""").findAll(value).map { it.value }.sorted().toList()

    @Test
    fun `наборы ключей совпадают`() {
        assertEquals(
            "ключи есть в английском, но нет в русском",
            emptySet<String>(),
            english.keys - russian.keys,
        )
        assertEquals(
            "ключи есть в русском, но нет в английском",
            emptySet<String>(),
            russian.keys - english.keys,
        )
    }

    @Test
    fun `плейсхолдеры совпадают в каждой строке`() {
        val mismatched = english.keys.filter { key ->
            placeholders(english.getValue(key)) != placeholders(russian.getValue(key))
        }
        assertEquals("расходятся плейсхолдеры", emptyList<String>(), mismatched)
    }

    @Test
    fun `нет пустых переводов`() {
        assertEquals(
            "пустые значения",
            emptyList<String>(),
            (english + russian).filterValues { it.isBlank() }.keys.toList(),
        )
    }

    /**
     * Имена собственные совпадают в обоих языках не по недосмотру.
     *
     * Список намеренно короткий и перечисляется поимённо: если он начнёт расти,
     * это и будет признаком, что перевод забыли.
     */
    private val sameInBothLanguages = setOf("app_name", "info_android")

    @Test
    fun `русский перевод действительно переведён`() {
        val untranslated = english.keys
            .filter { it !in sameInBothLanguages }
            .filter { english.getValue(it) == russian.getValue(it) }
        assertEquals("строки не переведены", emptyList<String>(), untranslated)
    }

    @Test
    fun `есть строки для всех состояний, которые видит пользователь`() {
        val required = listOf(
            "error_invalid_address", "error_unreachable", "error_pairing_required",
            "error_not_authorized", "error_pairing_rejected", "error_unknown",
            "ntp_applied", "ntp_invalid", "ntp_not_confirmed", "ntp_failed",
            "discovery_kind_awaiting_pairing", "discovery_kind_ready", "discovery_kind_legacy",
            "discovery_permission_needed", "discovery_grant_permission",
            "pairing_port_warning", "action_failed",
        )
        assertEquals("не хватает строк", emptyList<String>(), required - english.keys)
    }
}
