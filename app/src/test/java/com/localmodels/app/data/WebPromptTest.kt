package com.localmodels.app.data

import java.time.ZonedDateTime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class WebPromptTest {
    private val now = ZonedDateTime.parse("2026-11-19T22:57:00+03:00[Europe/Moscow]")

    @Test fun screenshotRequestSearchesWithoutModelToolCommand() = runBlocking {
        var calls = 0
        val stages = mutableListOf<String>()
        val prompt = Web.preparePrompt("Посмотри в интернете какое сегодня число", true, now = now,
            onStatus = { stages += it }, searcher = { query ->
                calls++
                assertTrue(query.contains("какое сегодня число"))
                "Какое сегодня число: календарь\nhttps://example.com/calendar\nСегодня число 19 ноября 2026"
            }, fetcher = { "Какое сегодня число: 19 ноября 2026 года." })
        assertEquals(1, calls)
        assertEquals(listOf("поиск в интернете", "чтение подходящего источника"), stages)
        assertTrue(prompt.usedWeb)
        assertTrue(prompt.text.contains("https://example.com/calendar"))
        assertTrue(prompt.text.contains("19 ноября 2026"))
        assertFalse(prompt.text.contains("Europe/Moscow"))
        assertFalse(prompt.text.contains("22:57"))
        val refusal = "Извините, я не имею доступа к интернету."
        val corrected = Web.correctedAnswer(refusal, prompt)
        assertTrue(corrected.contains("19 ноября 2026"))
        assertFalse(corrected.contains("Europe/Moscow"))
        assertTrue(corrected.contains("по часам устройства"))
        assertFalse(Web.deniesInternet(corrected))
        assertNull(Web.followUp(refusal, prompt))
        assertNull(Web.followUp("SEARCH unrelated topic", prompt))
    }

    @Test fun disabledMeansNoRetrievalOrPromptChanges() = runBlocking {
        val text = "Посмотри в интернете https://example.com"
        val prompt = Web.preparePrompt(text, false, now = now,
            searcher = { error("network must stay off") }, fetcher = { error("network must stay off") })
        assertEquals(text, prompt.text)
        assertFalse(prompt.usedWeb)
        assertEquals("Я не имею доступа к интернету", Web.correctedAnswer("Я не имею доступа к интернету", prompt))
    }

    @Test fun urlReadsPageAndPreservesAttachmentText() = runBlocking {
        val prompt = Web.preparePrompt("Посмотри ссылку\n[файл] abc", true,
            question = "Посмотри https://example.com", now = now,
            searcher = { error("should fetch URL instead") }, fetcher = { url ->
                assertEquals("https://example.com", url)
                "Текст страницы"
            })
        assertTrue(prompt.text.contains("[файл] abc"))
        assertTrue(prompt.text.contains("Текст страницы"))
        assertTrue(prompt.usedWeb)
    }

    @Test fun greetingDoesNotSearchAndNormalAnswersArePreserved() = runBlocking {
        val prompt = Web.preparePrompt("Привет", true, now = now,
            searcher = { error("greetings do not need search") }, fetcher = { error("no URL") })
        assertFalse(prompt.usedWeb)
        assertEquals("Привет", prompt.text)
        assertTrue(prompt.evidence.isEmpty())
        assertNull(prompt.dateAnswer)
        assertEquals("Привет!", Web.correctedAnswer("Привет!", prompt))
        assertFalse(Web.deniesInternet("Если у вас нет доступа к интернету, проверьте Wi-Fi."))
    }

    @Test fun conversationAndOrdinaryQuestionsReceiveNoDeviceInformation() = runBlocking {
        for (question in listOf("Что мы с тобой уже обсуждали", "Привет, как сегодня дела?", "Объясни рекурсию", "Что такое часовой пояс?", "Сколько времени готовить рис?", "Скажи дату рождения Пушкина")) {
            val prompt = Web.preparePrompt(question, true, now = now,
                searcher = { error("no search needed") }, fetcher = { error("no URL") })
            assertEquals(question, prompt.text)
            assertTrue(prompt.evidence.isEmpty())
            assertNull(prompt.dateAnswer)
        }
    }

    @Test fun clockQuestionWorksOfflineAndOnlyIncludesRelevantClockFields() = runBlocking {
        val date = Web.preparePrompt("Какая сегодня дата?", false, now = now,
            searcher = { error("offline") }, fetcher = { error("offline") })
        assertFalse(date.usedWeb)
        assertTrue(date.text.contains("19 ноября 2026"))
        assertFalse(date.text.contains("Europe/Moscow"))
        assertFalse(date.text.contains("22:57"))
        val time = Web.preparePrompt("Сколько сейчас времени?", false, now = now)
        assertTrue(time.text.contains("22:57"))
        assertTrue(time.text.contains("Europe/Moscow"))
        assertFalse(time.text.contains("19 ноября 2026"))
    }

    @Test fun ordinaryWebSearchDoesNotAddDeviceDateOrTimezone() = runBlocking {
        val prompt = Web.preparePrompt("Найди в интернете описание рекурсии", true, now = now,
            searcher = { "Описание рекурсии\nhttps://example.com/recursion\nРекурсия — вызов функцией самой себя." },
            fetcher = { "Описание рекурсии: рекурсия — вызов функцией самой себя." })
        assertTrue(prompt.usedWeb)
        assertTrue(prompt.evidence.contains("example.com/recursion"))
        assertFalse(prompt.text.contains("19 ноября 2026"))
        assertFalse(prompt.text.contains("Europe/Moscow"))
    }

    @Test fun dateDependentTasksStillReceiveCurrentDate() = runBlocking {
        val prompt = Web.preparePrompt("Сколько дней до Нового года?", true, now = now)
        assertTrue(prompt.text.contains("19 ноября 2026"))
        assertFalse(prompt.usedWeb)
        assertFalse(prompt.text.contains("Europe/Moscow"))
        assertNull(prompt.dateAnswer)
    }

    @Test fun explicitSearchAndFreshInformationAreDetected() {
        for (question in listOf("Поищи новости", "Посмотри\nв интернете курс доллара", "Какая погода в Казани?", "Look up on the web today's headlines")) {
            assertTrue(question, Web.needsSearch(question))
        }
        assertFalse(Web.needsSearch("Что такое интернет?"))
        assertTrue(SharedFileTools.needsLocalFiles("найди файл notes.txt"))
        assertFalse(SharedFileTools.needsLocalFiles("найди файл в интернете"))
        assertTrue(Web.asksDate("Сегодня какое число?"))
        assertFalse(Web.asksDate("Какое число больше: 2 или 3?"))
    }
}
