package ru.bookapk.reader

/**
 * A lightweight TXT structure detector. It deliberately recognizes only explicit
 * chapter markers so ordinary prose is not split into false chapters.
 */
data class TxtChapter(val title: String, val startParagraph: Int)

data class TxtDocument(
    val paragraphs: List<String>,
    val chapters: List<TxtChapter>
)

object TxtBookParser {
    private val chapterMarker = Regex(
        """^(?:#{1,3}\s+.+|(?:глава|часть|книга|раздел|chapter|part|book|section)\s+(?:[\dIVXLCDM]+|[А-ЯЁA-Z][\w-]*)(?:[.:—–-].*)?|пролог|эпилог|послесловие)\s*$""",
        RegexOption.IGNORE_CASE
    )

    fun parse(raw: String): TxtDocument {
        val normalized = raw.removePrefix("\uFEFF")
            .replace("\r\n", "\n")
            .replace('\r', '\n')
        val paragraphs = mutableListOf<String>()
        val chapters = mutableListOf<TxtChapter>()
        var buffer = StringBuilder()

        fun flushParagraph() {
            val value = buffer.toString().trim()
            if (value.isNotEmpty()) paragraphs += value
            buffer = StringBuilder()
        }

        normalized.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) {
                flushParagraph()
            } else if (chapterMarker.matches(line)) {
                flushParagraph()
                val title = line.replace(Regex("""^#{1,3}\s*"""), "").trim()
                val index = paragraphs.size
                paragraphs += title
                chapters += TxtChapter(title, index)
            } else {
                if (buffer.isNotEmpty()) buffer.append('\n')
                buffer.append(line)
            }
        }
        flushParagraph()

        if (paragraphs.isEmpty()) paragraphs += normalized.trim().ifEmpty { "В этой книге пока нет текста." }
        if (chapters.isEmpty()) chapters += TxtChapter("Начало", 0)
        else if (chapters.first().startParagraph > 0) chapters.add(0, TxtChapter("Начало", 0))

        return TxtDocument(paragraphs, chapters)
    }
}
