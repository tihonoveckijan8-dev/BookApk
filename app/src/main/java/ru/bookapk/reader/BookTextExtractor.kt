package ru.bookapk.reader

import android.net.Uri
import android.text.Html
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.util.zip.ZipFile

/**
 * WHY: EPUB and FB2 are reflowable text formats; extract their reading text locally
 * so the reader can reuse paragraph navigation and offline storage.
 */
object BookTextExtractor {
    fun readFb2(file: File): String {
        file.inputStream().buffered().use { input ->
            val parser = Xml.newPullParser()
            parser.setInput(input, null)
            val result = StringBuilder()
            var inBody = false
            var inBinary = false
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> when (parser.name.lowercase()) {
                        "body" -> inBody = parser.getAttributeValue(null, "name") != "notes"
                        "binary" -> inBinary = true
                        "p", "subtitle", "title", "text-author", "date", "empty-line" ->
                            if (inBody && !inBinary) result.append('\n')
                    }
                    XmlPullParser.TEXT -> if (inBody && !inBinary) result.append(parser.text)
                    XmlPullParser.END_TAG -> when (parser.name.lowercase()) {
                        "binary" -> inBinary = false
                        "p", "subtitle", "title", "text-author", "date", "empty-line" ->
                            if (inBody && !inBinary) result.append("\n\n")
                        "body" -> inBody = false
                    }
                }
                event = parser.next()
            }
            return normalize(result.toString())
        }
    }

    fun readEpub(file: File): String {
        ZipFile(file).use { zip ->
            val container = zip.getEntry("META-INF/container.xml")
                ?: error("EPUB не содержит META-INF/container.xml.")
            val containerParser = Xml.newPullParser()
            zip.getInputStream(container).use { containerParser.setInput(it, null) }
            var packagePath: String? = null
            var event = containerParser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && containerParser.name == "rootfile") {
                    packagePath = containerParser.getAttributeValue(null, "full-path")
                    if (!packagePath.isNullOrBlank()) break
                }
                event = containerParser.next()
            }
            val opfPath = packagePath ?: error("В EPUB не найден основной OPF-файл.")
            val opfEntry = zip.getEntry(opfPath) ?: error("Не найден OPF-файл книги.")
            val parser = Xml.newPullParser()
            zip.getInputStream(opfEntry).use { parser.setInput(it, null) }
            val manifest = linkedMapOf<String, String>()
            val spine = mutableListOf<String>()
            event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    when (parser.name.substringAfter(':').lowercase()) {
                        "item" -> {
                            val id = parser.getAttributeValue(null, "id")
                            val href = parser.getAttributeValue(null, "href")
                            val media = parser.getAttributeValue(null, "media-type") ?: ""
                            if (!id.isNullOrBlank() && !href.isNullOrBlank() &&
                                (media.contains("html") || href.endsWith(".xhtml", true) || href.endsWith(".html", true))) {
                                manifest[id] = href
                            }
                        }
                        "itemref" -> parser.getAttributeValue(null, "idref")?.let(spine::add)
                    }
                }
                event = parser.next()
            }
            val opfDirectory = opfPath.substringBeforeLast('/', "")
            val chapters = spine.mapNotNull { id ->
                val href = manifest[id] ?: return@mapNotNull null
                val decoded = Uri.decode(href).replace('\\', '/')
                val entryPath = listOf(opfDirectory, decoded).filter { it.isNotBlank() }.joinToString("/")
                require(!entryPath.split('/').contains("..")) { "Некорректный путь главы EPUB." }
                val entry = zip.getEntry(entryPath) ?: return@mapNotNull null
                val html = zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
                Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY).toString()
            }
            val content = normalize(chapters.joinToString("\n\n"))
            if (content.isBlank()) error("В EPUB не найден текст глав. Возможно, книга защищена DRM.")
            return content
        }
    }

    private fun normalize(value: String): String {
        val cleaned = value.replace('\u0000', ' ')
            .replace(Regex("[\\t\\u00A0 ]+"), " ")
            .replace(Regex(" *\\n *"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
        if (cleaned.isBlank()) error("В файле не найден читаемый текст.")
        return cleaned
    }
}
