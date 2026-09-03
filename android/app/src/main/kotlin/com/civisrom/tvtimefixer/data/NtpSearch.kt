package com.civisrom.tvtimefixer.data

/**
 * Найденный сервер: либо страна из справочника, либо альтернативный адрес.
 *
 * [country] нужна экрану, чтобы показать код и название на языке интерфейса.
 */
data class NtpMatch(val server: String, val country: NtpCountry?)

/** Сколько строк показывать: длинный список на телефоне листать неудобно. */
private const val SEARCH_LIMIT = 12

/**
 * До какой длины разрешено укорачивать запрос, отбрасывая окончание.
 *
 * Три буквы — граница, ниже которой совпадений становится слишком много:
 * «рос» ещё осмысленно, «ро» нашло бы половину справочника.
 */
private const val MIN_STEM = 3

/**
 * Ищет сервер по коду страны, её названию на любом из двух языков или по части
 * адреса.
 *
 * Вынесено из экрана в чистую функцию намеренно: промах здесь выглядит как
 * «поиск не работает», и отличить его от опечатки пользователя без теста
 * невозможно. Страны идут первыми — по коду страны ищут чаще, чем по адресу.
 *
 * Если точного совпадения нет, запрос укорачивается с конца. Это не вольность,
 * а необходимость для русского языка: справочник хранит «Россия», а клавиатура
 * подставляет «России» — родительный падеж в текстах встречается чаще, и
 * автодополнение предлагает именно его. Точное сравнение отвечало бы пустотой
 * на совершенно правильный ввод. У «Армении» конкурирующей формы нет, поэтому
 * она искалась, а «Россия» — нет; разобрано на живом устройстве.
 */
fun searchNtpServers(query: String): List<NtpMatch> {
    val needle = query.trim().lowercase()
    if (needle.isEmpty()) return emptyList()

    // Точное совпадение пробуется первым, и обычно им всё и заканчивается.
    // Отбрасывание окончания — запасной путь, чтобы порядок и состав выдачи
    // при обычном вводе не менялись.
    var attempt = needle
    while (true) {
        val found = matchAll(attempt)
        if (found.isNotEmpty() || attempt.length <= MIN_STEM) return found.take(SEARCH_LIMIT)
        attempt = attempt.dropLast(1)
    }
}

private fun matchAll(needle: String): List<NtpMatch> {
    val countries = NtpData.countries
        .filter {
            it.code.lowercase().contains(needle) ||
                it.nameEn.lowercase().contains(needle) ||
                it.nameRu.lowercase().contains(needle)
        }
        .map { NtpMatch(it.server, it) }

    val alternatives = NtpData.alternativeServers
        .filter { it.lowercase().contains(needle) }
        .map { NtpMatch(it, null) }

    return countries + alternatives
}
