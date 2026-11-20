package com.localmodels.app.data

import java.net.URI
import java.util.Locale
import kotlin.math.ceil

/** A fixed search budget: one result page and at most one relevant source. */
object FocusedSearch {
    data class Hit(val title: String, val url: String, val snippet: String)
    private val stop = setOf("привет", "пожалуйста", "мне", "нам", "ты", "вы", "как", "что", "это", "про", "для", "какая", "какое", "какой", "какие", "сколько", "стоит", "стоят", "где", "когда", "почему", "можно", "нужно", "актуальные", "последние", "свежие", "сегодня", "сейчас", "сегодняшнюю", "сегодняшняя", "дату", "дата", "информацию", "информация", "расскажи", "покажи", "узнать", "найти", "the", "what", "where", "when", "how", "much", "does", "cost", "about", "information", "find", "is", "in", "on", "for", "and", "today", "please", "hello")

    fun query(question: String): String {
        var q = question.trim().replace(Regex("\\s+"), " ")
        q = q.replace(Regex("(?iu)^(?:привет|здравствуй(?:те)?|hello|hi)[,! .]*"), "")
        q = q.replace(Regex("(?iu)^(?:(?:пожалуйста[, ]+)?(?:посмотри|найди|поищи|погугли|узнай|проверь|покажи|search|look up|check)(?: мне)?(?: в интернете| в сети| на сайтах| online| the web)?(?: for)?[, :]+)"), "")
        q = q.replace(Regex("(?iu)(?<![\\p{L}\\p{N}])(?:питере|питер|спб)(?![\\p{L}\\p{N}])"), "Санкт-Петербург")
        if (q.contains("погод", true)) {
            q = q.replace(Regex("(?iu)(?:сегодняшнюю дату|сегодняшняя дата|какое сегодня число|дату на сегодня)\\s*(?:и|,)\\s*"), "")
            if (question.contains("сегодня", true) && !q.contains("сегодня", true)) q += " сегодня"
        }
        return q.trim(' ', ',', '.', '?', '!').take(300)
    }

    private fun terms(text: String): Set<String> = Regex("[\\p{L}\\p{N}]+")
        .findAll(text.lowercase(Locale.ROOT).replace('ё', 'е'))
        .map { it.value }.filter { it.length >= 3 && it !in stop }
        .map { if (it.any(Char::isDigit)) it else it.take(5) }.toSet()

    internal fun score(text: String, query: String): Int {
        val wanted = terms(query)
        if (wanted.isEmpty()) return 0
        val available = terms(text)
        val matches = wanted.intersect(available).size
        // Short queries must retain every term, including a city or model name.
        val minimum = if (wanted.size <= 3) wanted.size else ceil(wanted.size * 0.7).toInt()
        if (wanted.first() !in available || matches < minimum) return 0
        if (wanted.any { term -> term.any(Char::isDigit) && term !in available }) return 0
        return matches * 10
    }

    internal fun parse(raw: String): List<Hit> = raw.split(Regex("\\n\\s*\\n"))
        .mapNotNull { block ->
            val lines = block.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val index = lines.indexOfFirst { it.startsWith("https://") || it.startsWith("http://") }
            if (index < 0) return@mapNotNull null
            val uri = runCatching { URI(lines[index]) }.getOrNull() ?: return@mapNotNull null
            if (uri.host.isNullOrBlank()) return@mapNotNull null
            Hit(lines.take(index).joinToString(" "), lines[index], lines.drop(index + 1).joinToString(" "))
        }.distinctBy { it.url }.take(10)

    internal fun extract(page: String, query: String): String {
        if (score(page, query) == 0) return ""
        val blocks = page.split(Regex("\\n+|(?<=[.!?])\\s+")).map { it.trim() }.filter { it.length >= 5 }
        val ranked = blocks.indices.sortedByDescending { score(blocks[it], query) }
        val selected = linkedSetOf<Int>()
        for (index in ranked) {
            if (score(blocks[index], query) == 0) continue
            selected += index
            if (index + 1 < blocks.size && (score(blocks[index + 1], query) > 0 ||
                (query.contains("погод", true) && Regex("(?iu)°|температур|ветер|осадк|влажност").containsMatchIn(blocks[index + 1])))) selected += index + 1
            if (selected.size >= 6) break
        }
        return selected.sorted().joinToString("\n") { blocks[it] }.take(2600)
    }

    suspend fun run(
        question: String,
        searcher: suspend (String) -> String,
        fetcher: suspend (String) -> String,
        onStatus: (String) -> Unit = {},
        onSource: (SourceRef) -> Unit = {}
    ): String {
        val query = query(question)
        val hits = parse(searcher(query))
        val hit = hits.maxByOrNull { score(it.title + " " + it.snippet, query) }
            ?.takeIf { score(it.title + " " + it.snippet, query) > 0 }
            ?: return "По запросу «$query» подходящих источников не найдено. Посторонние результаты отброшены. " +
                "Не подставляй факты из других тем; попроси уточнить запрос, если нужно."
        onStatus("чтение подходящего источника")
        val page = fetcher(hit.url)
        val relevant = extract(page, query)
        val snippet = hit.snippet.takeIf { score(it, query) > 0 }?.take(1000).orEmpty()
        onSource(SourceRef(hit.title, hit.url, relevant.ifBlank { snippet }))
        return "Запрос: $query\nИсточник: ${hit.title}\n${hit.url}\n" +
            when {
                relevant.isNotBlank() -> "Выдержки по запросу:\n$relevant"
                snippet.isNotBlank() -> "Подтверждена только выдержка поисковой выдачи:\n$snippet\nСтраница не дала дополнительной информации."
                else -> "Найдена ссылка по теме, но факты со страницы получить не удалось. Не выдумывай ответ."
            } + "\nПоиск завершён. Другие страницы не открывались."
    }
}
