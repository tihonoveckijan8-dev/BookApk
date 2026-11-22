package ru.bookapk.reader

import android.content.Context
import android.net.Uri
import android.util.Xml
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
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
data class EbookMetadata(val title: String?, val author: String?)
data class ImportedEbook(val file: File, val title: String?, val author: String?, val cover: File?)

object EbookImporter {
    fun convert(context: Context, source: Uri, format: String): ImportedEbook {
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
            // WHY: publisher metadata is a better library label than a possibly opaque downloaded filename.
            val metadata = runCatching { readMetadata(context, source, format) }.getOrNull()
            val title = metadata?.title?.trim()?.takeIf { it.isNotBlank() }
            val author = metadata?.author?.trim()?.takeIf { it.isNotBlank() }
            // WHY: preserve a publisher-supplied raster cover separately from normalized reading text.
            val cover = if (format.equals("epub", true)) runCatching { extractEpubCover(context, source) }.getOrNull() else null
            return ImportedEbook(output, title, author, cover)
        } catch (error: Exception) {
            output.delete()
            throw error
        }
    }

    private fun readMetadata(context: Context, source: Uri, format: String): EbookMetadata? {
        return when (format.lowercase()) {
            "epub" -> {
                val input = context.contentResolver.openInputStream(source)
                    ?: throw IllegalStateException("Не удалось открыть EPUB-файл.")
                val temp = File(context.cacheDir, "ebook-meta-${java.util.UUID.randomUUID()}.epub")
                input.use { stream -> temp.outputStream().use { stream.copyTo(it) } }
                try {
                    ZipFile(temp).use { zip ->
                        val container = zip.getEntry("META-INF/container.xml") ?: return@use null
                        val containerDoc = zip.getInputStream(container).bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
                        val rootfile = Jsoup.parse(containerDoc, "", Parser.xmlParser())
                            .getElementsByTag("rootfile").firstOrNull() ?: return@use null
                        val opfPath = rootfile.attr("full-path")
                        if (opfPath.isBlank() || opfPath.startsWith("/") || opfPath.split("/").contains("..")) return@use null
                        val opfEntry = zip.getEntry(opfPath) ?: return@use null
                        val packageDoc = zip.getInputStream(opfEntry).bufferedReader(StandardCharsets.UTF_8).use {
                            Jsoup.parse(it.readText(), "", Parser.xmlParser())
                        }
                        // WHY: EPUB package metadata identifies the work and its credited creator independently of its filename.
                        val title = packageDoc.getElementsByTag("dc:title").firstOrNull()?.text()
                            ?: packageDoc.getElementsByTag("title").firstOrNull()?.text()
                        val author = packageDoc.getElementsByTag("dc:creator").firstOrNull()?.text()
                            ?: packageDoc.getElementsByTag("creator").firstOrNull()?.text()
                        EbookMetadata(title, author)
                    }
                } finally {
                    temp.delete()
                }
            }
            "fb2" -> {
                val input = context.contentResolver.openInputStream(source)
                    ?: throw IllegalStateException("Не удалось открыть FB2-файл.")
                input.use {
                    val parser = Xml.newPullParser()
                    parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                    parser.setInput(it, null)
                    var inTitleInfo = false
                    var inAuthor = false
                    var title: String? = null
                    val authorParts = mutableListOf<String>()
                    while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                        when (parser.eventType) {
                            XmlPullParser.START_TAG -> when (parser.name.substringAfter(':').lowercase()) {
                                "title-info" -> inTitleInfo = true
                                "author" -> if (inTitleInfo) inAuthor = true
                                "book-title" -> if (inTitleInfo && title.isNullOrBlank()) title = readElementText(parser)
                                "first-name", "middle-name", "last-name", "nickname" ->
                                    if (inTitleInfo && inAuthor) {
                                        readElementText(parser).takeIf { value -> value.isNotBlank() }?.let(authorParts::add)
                                    }
                            }
                            XmlPullParser.END_TAG -> when (parser.name.substringAfter(':').lowercase()) {
                                "author" -> inAuthor = false
                                "title-info" -> inTitleInfo = false
                            }
                        }
                        if (!title.isNullOrBlank() && !inTitleInfo) break
                        parser.next()
                    }
                    EbookMetadata(title, authorParts.joinToString(" ").takeIf { it.isNotBlank() })
                }
            }
            else -> null
        }
    }


    private fun extractEpubCover(context: Context, source: Uri): File? {
        val input = context.contentResolver.openInputStream(source) ?: return null
        val temp = File(context.cacheDir, "ebook-cover-${java.util.UUID.randomUUID()}.epub")
        input.use { stream -> temp.outputStream().use { stream.copyTo(it) } }
        try {
            ZipFile(temp).use { zip ->
                val container = zip.getEntry("META-INF/container.xml") ?: return null
                val containerText = zip.getInputStream(container).bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
                val rootfile = Jsoup.parse(containerText, "", Parser.xmlParser())
                    .getElementsByTag("rootfile").firstOrNull() ?: return null
                val opfPath = rootfile.attr("full-path")
                if (opfPath.isBlank() || opfPath.startsWith("/") || opfPath.split("/").contains("..")) return null
                val opfEntry = zip.getEntry(opfPath) ?: return null
                val packageDoc = zip.getInputStream(opfEntry).bufferedReader(StandardCharsets.UTF_8).use {
                    Jsoup.parse(it.readText(), "", Parser.xmlParser())
                }
                val manifestItems = packageDoc.getElementsByTag("item")
                val epub3Cover = manifestItems.firstOrNull {
                    it.attr("properties").split(Regex("\\s+")).contains("cover-image")
                }
                val legacyCoverId = packageDoc.getElementsByTag("meta").firstOrNull {
                    it.attr("name").equals("cover", true)
                }?.attr("content").orEmpty()
                val coverItem = epub3Cover ?: manifestItems.firstOrNull { it.attr("id") == legacyCoverId }
                    ?: return null
                val href = coverItem.attr("href")
                if (href.isBlank()) return null
                val imageUri = URI(opfPath).resolve(href).normalize()
                val imagePath = imageUri.path.removePrefix("/")
                if (imageUri.isAbsolute || imagePath.isBlank() || imagePath.split("/").contains("..")) return null
                val entry = zip.getEntry(imagePath) ?: return null
                val mediaType = coverItem.attr("media-type").lowercase()
                val extension = when {
                    mediaType == "image/jpeg" -> "jpg"
                    mediaType == "image/png" -> "png"
                    mediaType == "image/webp" -> "webp"
                    mediaType == "image/gif" -> "gif"
                    else -> imagePath.substringAfterLast('.', "").lowercase()
                        .takeIf { it in setOf("jpg", "jpeg", "png", "webp", "gif") } ?: return null
                }
                if (entry.size > 12L * 1024L * 1024L) return null
                val directory = File(context.filesDir, "covers").apply { mkdirs() }
                val output = File(directory, "${java.util.UUID.randomUUID()}.$extension")
                try {
                    zip.getInputStream(entry).use { imageInput ->
                        output.outputStream().buffered().use { imageOutput ->
                            val buffer = ByteArray(8192)
                            var copied = 0L
                            while (true) {
                                val count = imageInput.read(buffer)
                                if (count < 0) break
                                copied += count
                                require(copied <= 12L * 1024L * 1024L) { "Обложка EPUB слишком большая." }
                                imageOutput.write(buffer, 0, count)
                            }
                        }
                    }
                    if (output.length() > 0L) return output
                } catch (error: Exception) {
                    output.delete()
                    throw error
                }
                output.delete()
                return null
            }
        } finally {
            temp.delete()
        }
    }


    /**
     * Keeps basic inline typography while flattening EPUB XHTML into the reader's paragraph model.
     * WHY: bold/italic/underline cues survive import without introducing a second layout engine.
     */
    private fun renderEpubInline(node: Node): String {
        if (node is TextNode) return node.wholeText
        if (node !is Element) return node.childNodes().joinToString("") { renderEpubInline(it) }
        if (node.tagName() == "br") return "\n"
        val content = node.childNodes().joinToString("") { renderEpubInline(it) }
        return when (node.tagName().lowercase()) {
            "strong", "b" -> if (content.isBlank()) content else "**${content}**"
            "em", "i" -> if (content.isBlank()) content else "*${content}*"
            "u" -> if (content.isBlank()) content else "__${content}__"
            else -> content
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
                    val blockTags = setOf("h1", "h2", "h3", "h4", "h5", "h6", "p", "li", "blockquote")
                    val blocks = body.select("h1,h2,h3,h4,h5,h6,p,li,blockquote")
                        // WHY: EPUBs often wrap paragraphs in list items or blockquotes; keep the outer block once.
                        .filter { element ->
                            element.parents().none { parent -> parent.tagName() in blockTags }
                        }
                        .map { renderEpubInline(it).trim().replace(Regex("[ \\t]+"), " ").replace(Regex(" *\\n *"), "\n") }
                        .filter { it.isNotBlank() }
                    val chapterText = (if (blocks.isNotEmpty()) blocks else listOf(body.text().trim()))
                        .filter { it.isNotBlank() }
                    if (chapterText.isNotEmpty()) {
                        if (result.isNotEmpty()) result.appendLine().appendLine()
                        val tocTitle = tocTitles[entryPath]
                        val first = chapterText.first()
                        // WHY: an explicit XHTML heading is stronger evidence than guessing from its wording.
                        val markupHeading = body.select("h1,h2,h3,h4,h5,h6").first()?.text()?.trim().orEmpty()
                        val headingIsFirstBlock = markupHeading.isNotBlank() && first.equals(markupHeading, true)
                        val hasExplicitHeading = headingIsFirstBlock || first.matches(
                            Regex("(?i)^(глава|часть|chapter|part|book|пролог|эпилог).*")
                        )
                        val contentBlocks = if (!tocTitle.isNullOrBlank() && headingIsFirstBlock &&
                            !markupHeading.equals(tocTitle, true)) chapterText.drop(1) else chapterText
                        if (!tocTitle.isNullOrBlank() && !first.equals(tocTitle, true)) {
                            result.appendLine(tocTitle).appendLine()
                        } else if (!hasExplicitHeading && tocTitle.isNullOrBlank()) {
                            result.appendLine("Глава ${index + 1}").appendLine()
                        }
                        contentBlocks.forEach { result.appendLine(it).appendLine() }
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
