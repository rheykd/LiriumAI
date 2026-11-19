package com.localmodels.app.data

import org.junit.Assert.*
import org.junit.Test

class SystemPromptTest {
    @Test fun permissionsAndToolExamplesMatchActualConfiguration() {
        val online = SystemPrompt.build(true)
        assertTrue(online.contains("SEARCH погода"))
        assertTrue(online.contains("FETCH https://"))
        assertTrue(online.contains("Доступ к файлам устройства выключен"))
        val offline = SystemPrompt.build(false)
        assertTrue(offline.contains("Интернет выключен"))
        assertFalse(offline.contains("SEARCH погода"))
        assertTrue(SystemPrompt.build(false, "Разрешено чтение выбранных файлов").contains("Разрешено чтение выбранных файлов"))
    }

    @Test fun recognizesWeatherRefusalFromScreenshot() {
        assertTrue(Web.deniesInternet("Однако, я не могу предоставить актуальную информацию о текущей погоде без доступа к интернету."))
        assertFalse(Web.deniesInternet("Если у вас нет доступа к интернету, проверьте соединение."))
    }

    @Test fun instructionDoesNotTurnGreetingOrHistoryQuestionIntoDateReport() {
        val prompt = SystemPrompt.build(true)
        assertTrue(prompt.contains("На приветствие отвечай приветствием"))
        assertTrue(prompt.contains("без необходимости для текущего вопроса"))
        assertTrue(prompt.contains("только к сообщениям текущего чата"))
    }
}
