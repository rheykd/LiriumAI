package com.localmodels.app.data

import java.net.URL
import java.time.ZonedDateTime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class FocusedSearchTest {
    private val unrelated = "Roblox voice passport verification\nhttps://example.com/roblox\nPassport verification for Roblox voice."
    private val weather = "Погода Санкт-Петербург сегодня\nhttps://example.com/weather\nПогода Санкт-Петербург: температура -3 °C, ветер 4 м/с."

    @Test fun oneSearchAndOnlyOneRelevantPage() = runBlocking {
        var searches = 0
        val pages = mutableListOf<String>()
        val result = FocusedSearch.run("Привет, посмотри в интернете погоду в питере на сегодня", {
            searches++
            assertFalse(it.contains("Привет"))
            assertFalse(it.contains("посмотри"))
            assertTrue(it.contains("Санкт-Петербург"))
            "$unrelated\n\n$weather\n\nПогода Санкт-Петербург\nhttps://example.com/second\nПогода Санкт-Петербург сегодня"
        }, { url ->
            pages += url
            "Погода Санкт-Петербург сегодня: температура -3 °C.\nВетер 4 м/с.\nRoblox требует паспорт для голосового чата."
        })
        assertEquals(1, searches)
        assertEquals(listOf("https://example.com/weather"), pages)
        assertTrue(result.contains("-3 °C"))
        assertTrue(result.contains("Ветер 4"))
        assertFalse(result.contains("Roblox"))
        assertFalse(result.contains("/second"))
    }

    @Test fun irrelevantResultsNeverReachModelOrTriggerPageRead() = runBlocking {
        val result = FocusedSearch.run("погода Санкт-Петербург сегодня", { unrelated }, { error("unrelated page must not open") })
        assertTrue(result.contains("подходящих источников не найдено"))
        assertFalse(result.contains("Roblox"))
        assertFalse(result.contains("passport"))
        assertEquals(0, FocusedSearch.score("Погода Москва сегодня", "погода Санкт-Петербург"))
    }

    @Test fun failedPageDoesNotStartTryingOtherLinks() = runBlocking {
        var fetched = 0
        val result = FocusedSearch.run("погода Санкт-Петербург", { "$weather\n\n$weather" }, { fetched++; "HTTP 403" })
        assertEquals(1, fetched)
        assertTrue(result.contains("только выдержка поисковой выдачи"))
        assertTrue(result.contains("-3 °C"))
    }

    @Test fun completedLookupCannotReplaceNormalAnswerWithAnotherGeneration() = runBlocking {
        val question = "Привет, посмотри в интернете сегодняшнюю дату и погоду в питере"
        val prepared = Web.preparePrompt(question, true,
            now = ZonedDateTime.parse("2026-11-19T23:20:00+03:00[Europe/Moscow]"),
            searcher = { weather }, fetcher = { "Погода Санкт-Петербург сегодня: -3 °C." })
        val answer = "Сегодня 19 ноября 2026 года. Погода в Санкт-Петербурге: -3 °C."
        assertNull(Web.followUp(answer, prepared))
        assertNull(Web.followUp("$answer\nSEARCH Roblox passport", prepared))
        assertNull(Web.followUp("FETCH https://example.com/roblox", prepared))
        assertEquals(answer, Web.correctedAnswer(answer, prepared))
        assertTrue(prepared.text.contains(question))
        assertFalse(prepared.evidence.contains("Roblox"))
    }

    @Test fun topicKeywordsAreRetainedAndGreetingIsRemoved() {
        val query = FocusedSearch.query("Привет, посмотри в интернете сегодняшнюю дату и погоду")
        assertEquals("погоду сегодня", query)
        assertEquals(0, FocusedSearch.score("Проверка паспортных данных Roblox", query))
        assertTrue(FocusedSearch.score("Погода сегодня", query) > 0)
    }

    @Test fun parsesTitlesSnippetsAndRealDestination() {
        val html = """<a href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fweather" class='result-link'>Погода Санкт-Петербург</a>
            <td class='result-snippet'>Температура -3 °C &amp; ветер 4 м/с</td>
            <a class='result-link' href="https://example.com/second">Вторая страница</a><td class='result-snippet'>Другая выдержка</td>"""
        val results = Web.parseSearchHtml(html)
        assertEquals(2, results.size)
        assertTrue(results[0].contains("https://example.com/weather"))
        assertTrue(results[0].contains("Температура -3 °C & ветер"))
        assertFalse(results[0].contains("Другая выдержка"))
    }

    @Test fun redirectsCannotLeaveSelectedSite() {
        assertTrue(Web.allowedRedirect(URL("https://example.com/a"), URL("https://www.example.com/b")))
        assertFalse(Web.allowedRedirect(URL("https://example.com"), URL("https://other.example.org")))
        assertFalse(Web.allowedRedirect(URL("https://example.com"), URL("http://example.com")))
    }

    @Test fun modelCannotChangeOriginalQueryOrRepeatLookup() = runBlocking {
        val question = "Какая температура в Санкт-Петербурге?"
        val prepared = Web.PreparedPrompt(question)
        var calls = 0
        val lookup: suspend (String, String) -> String? = { _, original ->
            calls++
            assertEquals(question, original)
            "Погода Санкт-Петербург: -3 °C."
        }
        assertNull(Web.followUp("FETCH https://example.com/roblox", prepared, lookup))
        val result = Web.followUp("SEARCH Roblox passport", prepared, lookup)
        assertTrue(result!!.contains(question))
        assertNull(Web.followUp("SEARCH another topic", prepared, lookup))
        assertEquals(1, calls)
    }
}
