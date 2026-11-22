package ru.bookapk.reader

import android.content.Context
import android.net.Uri
import android.util.Xml
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.zip.ZipFile

/**
 * Converts reflowable ebook formats into the reader's existing paragraph model.
 * WHY: the current reader already persists exact paragraph/offset positions, bookmarks and notes.
 */
object EbookImporter {
    fun convert(context: Context, source: Uri, format: String): File {
        val outputDirectory = File(context.filesDir, "books").apply { mkdirs() }
        val output = File(outputDirectory, "${java.util.UUID.randomUUID()}.txt")
        try {
            val text = when (format.lowercase()) {
                "epub" -> readEpub(context, source)
                "fb2" -> readFb2(context, source)
                else -> throw IllegalArgumentException("Формат электронной книги не поддерживается.")
            }
            require(text.isNotBlank()) { "В книге не найден читаемый текст." }
            output.writeText(text, StandardCharsets.UTF_8)
            return output
        } catch (error: Exception) {
            output.delete()
            throw error
        }
    }

    private fun readEpub(context: Context, source: Uri): String {
        val input = context.contentResolver.openInputStream(source)
            ?: throw IllegalStateException("Не удалось открыть EPUB-файл.")
        val temp = File(context.cacheDir, "ebook-${java.util.UUID.randomUUID()}.epub")
        input.use { stream -> temp.outputStream().use { stream.copyTo(it) } }
        try {
            ZipFile(temp).use { zip ->
                val container = zip.getEntry("META-INF/container.xml")
                    ?: throw IllegalArgumentException("В EPUB отсутствует container.xml.")
                val containerDoc = zip.getInputStream(container).bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
                val rootfile = Jsoup.parse(containerDoc, "", Parser.xmlParser()).getElementsByTag("rootfile").first()
                    ?: throw IllegalArgumentException("В EPUB не найден файл package.opf.")
                val opfPath = rootfile.attr("full-path")
                require(opfPath.isNotBlank() && !opfPath.startsWith("/") && !opfPath.split("/").contains("..")) {
                    "Некорректный путь к оглавлению EPUB."
                }
                val opfEntry = zip.getEntry(opfPath)
                    ?: throw IllegalArgumentException("Файл package.opf не найден.")
                val opf = zip.getInputStream(opfEntry).bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
                val packageDoc = Jsoup.parse(opf, "", Parser.xmlParser())
                val manifest = packageDoc.getElementsByTag("item").associateBy(
                    { it.attr("id") }, { it.attr("href") }
                )
                val spineIds = packageDoc.getElementsByTag("itemref").map { it.attr("idref") }
                require(spineIds.isNotEmpty()) { "В EPUB не найден порядок глав (spine)." }

                val opfUri = URI(opfPath)
                val tocTitles = readEpubTocTitles(zip, packageDoc, manifest, opfUri)
                val result = StringBuilder()
                spineIds.forEachIndexed { index, id ->
                    val href = manifest[id] ?: return@forEachIndexed
                    val entryPath = opfUri.resolve(href).normalize().path.removePrefix("/")
                    require(!entryPath.split("/").contains("..")) { "Некорректный путь к главе EPUB." }
                    val entry = zip.getEntry(entryPath) ?: return@forEachIndexed
                    val html = zip.getInputStream(entry).bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
                    val document = Jsoup.parse(html)
                    document.select("script,style,nav,head").remove()
                    val body = document.body()
                    val blocks = body.select("h1,h2,h3,h4,h5,h6,p,li,blockquote")
                        .map { it.text().trim() }.filter { it.isNotBlank() }
                    val chapterText = (if (blocks.isNotEmpty()) blocks else listOf(body.text().trim()))
                        .filter { it.isNotBlank() }
                    if (chapterText.isNotEmpty()) {
                        if (result.isNotEmpty()) result.appendLine().appendLine()
                        val tocTitle = tocTitles[entryPath]
                        val first = chapterText.first()
                        val hasExplicitHeading = first.matches(
                            Regex("(?i)^(глава|часть|chapter|part|book|пролог|эпилог).*")
                        )
                        if (!tocTitle.isNullOrBlank() && !first.equals(tocTitle, true)) {
                            result.appendLine(tocTitle).appendLine()
                        } else if (!hasExplicitHeading && tocTitle.isNullOrBlank()) {
                            result.appendLine("Глава ${index + 1}").appendLine()
                        }
                        chapterText.forEach { result.appendLine(it).appendLine() }
                    }
                }
                return result.toString().trim()
            }
        } finally {
            temp.delete()
        }
    }

    /**
     * Reads EPUB 2 NCX or EPUB 3 navigation labels.
     * WHY: real chapter names are more useful in the existing TXT table of contents than synthetic numbers.
     */
    private fun readEpubTocTitles(
        zip: ZipFile,
        packageDoc: org.jsoup.nodes.Document,
        manifest: Map<String, String>,
        opfUri: URI
    ): Map<String, String> {
        val result = linkedMapOf<String, String>()
        val navItem = packageDoc.getElementsByTag("item").firstOrNull {
            it.attr("properties").split(Regex("\\s+")).contains("nav")
        }
        if (navItem != null) {
            val navPath = opfUri.resolve(navItem.attr("href")).normalize()
            val navEntry = zip.getEntry(navPath.path.removePrefix("/"))
            if (navEntry != null) {
                val html = zip.getInputStream(navEntry).bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
                val navDoc = Jsoup.parse(html)
                navDoc.select("nav a[href]").forEach { link ->
                    val label = link.text().trim()
                    val target = navPath.resolve(link.attr("href")).normalize().path.removePrefix("/")
                    if (label.isNotBlank()) result.putIfAbsent(target, label)
                }
            }
        }

        if (result.isEmpty()) {
            val ncxHref = manifest.values.firstOrNull { href ->
                packageDoc.getElementsByTag("item").any {
                    it.attr("href") == href && it.attr("media-type") == "application/x-dtbncx+xml"
                }
            }
            if (ncxHref != null) {
                val ncxPath = opfUri.resolve(ncxHref).normalize()
                val ncxEntry = zip.getEntry(ncxPath.path.removePrefix("/"))
                if (ncxEntry != null) {
                    val xml = zip.getInputStream(ncxEntry).bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
                    val ncx = Jsoup.parse(xml, "", Parser.xmlParser())
                    ncx.getElementsByTag("navPoint").forEach { point ->
                        val label = point.getElementsByTag("text").first()?.text()?.trim().orEmpty()
                        val href = point.getElementsByTag("content").first()?.attr("src").orEmpty()
                        if (label.isNotBlank() && href.isNotBlank()) {
                            val target = ncxPath.resolve(href).normalize().path.removePrefix("/")
                            result.putIfAbsent(target, label)
                        }
                    }
                }
            }
        }
        return result
    }

    private fun readFb2(context: Context, source: Uri): String {
        val input = context.contentResolver.openInputStream(source)
            ?: throw IllegalStateException("Не удалось открыть FB2-файл.")
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)
        val result = StringBuilder()
        var inBody = false
        var skipBody = false
        var sectionDepth = 0

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    when (parser.name.lowercase()) {
                        "body" -> {
                            val bodyName = parser.getAttributeValue(null, "name")
                            inBody = bodyName.isNullOrBlank() || bodyName.equals("main", true)
                            skipBody = !inBody
                        }
                        "section" -> if (inBody && !skipBody) {
                            sectionDepth++
                        }
                        "title" -> if (inBody && !skipBody && sectionDepth > 0) {
                            val title = readElementText(parser)
                            if (title.isNotBlank()) result.appendLine().appendLine(title).appendLine()
                        }
                        "p", "subtitle", "v", "text-author" -> if (inBody && !skipBody) {
                            val value = readElementText(parser).trim()
                            if (value.isNotBlank()) result.appendLine(value).appendLine()
                        }
                        "empty-line" -> if (inBody && !skipBody) result.appendLine()
                    }
                }
                XmlPullParser.END_TAG -> when (parser.name.lowercase()) {
                    "section" -> if (inBody && sectionDepth > 0) sectionDepth--
                    "body" -> { inBody = false; skipBody = false }
                }
            }
            parser.next()
        }
        input.close()
        return result.toString().trim()
    }

    private fun readElementText(parser: XmlPullParser): String {
        val depth = parser.depth
        val value = StringBuilder()
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.TEXT, XmlPullParser.CDSECT -> value.append(parser.text).append(' ')
                XmlPullParser.END_TAG -> if (parser.depth == depth) break
            }
        }
        return value.toString().replace(Regex("\\s+"), " ").trim()
    }
}
