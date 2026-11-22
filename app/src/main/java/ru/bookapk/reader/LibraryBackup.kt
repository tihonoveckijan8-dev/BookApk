package ru.bookapk.reader

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class StagedBackupBook(val book: Book, val sourceFile: File, val sourceCoverFile: File? = null)

data class StagedLibraryBackup(
    val books: List<StagedBackupBook>,
    val categories: List<BookCategory>,
    val appearance: ReaderAppearance,
    val stagingDirectory: File
)

object LibraryBackup {
    private const val FORMAT = "bookapk-library-backup"
    private const val VERSION = 1
    private const val MAX_MANIFEST_BYTES = 10L * 1024L * 1024L
    private const val MAX_BOOK_BYTES = 512L * 1024L * 1024L
    private const val MAX_COVER_BYTES = 12L * 1024L * 1024L
    private const val MAX_TOTAL_BYTES = 1024L * 1024L * 1024L

    fun export(
        context: Context,
        destination: Uri,
        books: List<Book>,
        categories: List<BookCategory>,
        appearance: ReaderAppearance
    ) {
        val records = JSONArray()
        val files = mutableListOf<Pair<String, File>>()
        books.forEachIndexed { index, book ->
            val extension = when (book.type.lowercase()) {
                "epub", "fb2" -> "txt" // WHY: imported reflowable books are stored as normalized UTF-8 text.
                "pdf", "txt" -> book.type.lowercase()
                else -> throw IllegalArgumentException("Неподдерживаемый формат книги: " + book.title)
            }
            val source = File(book.path)
            require(source.isFile && source.length() > 0L) { "Не найден файл книги: " + book.title }
            require(source.extension.equals(extension, true)) { "Файл книги имеет неожиданный формат: " + book.title }
            val archiveName = "books/book-" + index + "." + extension
            val cover = book.coverPath.takeIf { it.isNotBlank() }?.let(::File)?.takeIf {
                it.isFile && it.length() in 1..MAX_COVER_BYTES &&
                    it.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp", "gif")
            }
            val coverArchiveName = cover?.let { "covers/cover-" + index + "." + it.extension.lowercase() }
            records.put(
                JSONObject()
                    .put("book", book.copy(path = "", coverPath = "").toJson())
                    .put("file", archiveName)
                    .put("coverFile", coverArchiveName.orEmpty())
            )
            files.add(archiveName to source)
            if (cover != null && coverArchiveName != null) files.add(coverArchiveName to cover)
        }

        val categoryArray = JSONArray()
        categories.forEach { category -> categoryArray.put(category.toJson()) }
        val appearanceJson = JSONObject()
            .put("visualStyle", appearance.visualStyle)
            .put("iconStyle", appearance.iconStyle)
            .put("readerFontSize", appearance.readerFontSize.toDouble())
            .put("readerLineSpacing", appearance.readerLineSpacing.toDouble())
            .put("readerTheme", appearance.readerTheme)

        val manifest = JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("createdAt", System.currentTimeMillis())
            .put("books", records)
            .put("categories", categoryArray)
            .put("appearance", appearanceJson)

        val output = context.contentResolver.openOutputStream(destination)
            ?: throw IllegalStateException("Не удалось открыть файл для записи.")
        ZipOutputStream(BufferedOutputStream(output)).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(manifest.toString().toByteArray(StandardCharsets.UTF_8))
            zip.closeEntry()

            files.forEach { (archiveName, source) ->
                zip.putNextEntry(ZipEntry(archiveName))
                source.inputStream().buffered().use { input -> input.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    fun stageImport(context: Context, source: Uri): StagedLibraryBackup {
        val staging = File(context.cacheDir, "bookapk-restore-" + UUID.randomUUID())
        require(staging.mkdirs()) { "Не удалось подготовить временное хранилище." }
        try {
            val extracted = mutableMapOf<String, File>()
            var manifestBytes: ByteArray? = null
            var totalBytes = 0L
            val input = context.contentResolver.openInputStream(source)
                ?: throw IllegalStateException("Не удалось открыть резервную копию.")

            ZipInputStream(BufferedInputStream(input)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) {
                        require(entry.name in setOf("books/", "covers/")) { "В архиве обнаружена недопустимая папка." }
                        zip.closeEntry()
                        continue
                    }

                    when {
                        entry.name == "manifest.json" -> {
                            require(manifestBytes == null) { "В архиве несколько манифестов." }
                            val buffer = ByteArrayOutputStream()
                            totalBytes += copyEntry(zip, buffer, MAX_MANIFEST_BYTES, totalBytes)
                            manifestBytes = buffer.toByteArray()
                        }
                        Regex("""(books/book-[0-9]+\.(pdf|txt)|covers/cover-[0-9]+\.(jpg|jpeg|png|webp|gif))""").matches(entry.name) -> {
                            require(!extracted.containsKey(entry.name)) { "В архиве повторяется файл книги или обложки." }
                            val isCover = entry.name.startsWith("covers/")
                            val fileName = entry.name.substringAfter('/')
                            val target = File(staging, fileName)
                            val entryLimit = if (isCover) MAX_COVER_BYTES else MAX_BOOK_BYTES
                            val written = target.outputStream().buffered().use { output ->
                                copyEntry(zip, output, entryLimit, totalBytes)
                            }
                            totalBytes += written
                            extracted[entry.name] = target
                        }
                        else -> throw IllegalArgumentException("Неизвестный файл в резервной копии: " + entry.name)
                    }
                    require(totalBytes <= MAX_TOTAL_BYTES) { "Резервная копия слишком большая." }
                    zip.closeEntry()
                }
            }

            val manifestRaw = manifestBytes ?: throw IllegalArgumentException("В архиве нет манифеста.")
            val manifest = JSONObject(String(manifestRaw, StandardCharsets.UTF_8))
            require(manifest.optString("format") == FORMAT) { "Это не резервная копия «Моей читалки»." }
            require(manifest.optInt("version", -1) == VERSION) { "Версия резервной копии не поддерживается." }

            val categoryArray = manifest.optJSONArray("categories") ?: JSONArray()
            require(categoryArray.length() <= 200) { "В резервной копии слишком много категорий." }
            val categories = (0 until categoryArray.length()).map { index ->
                BookCategory.fromJson(categoryArray.getJSONObject(index))
            }
            require(categories.map { it.id }.distinct().size == categories.size) { "В резервной копии есть повторяющиеся категории." }

            val appearanceJson = manifest.optJSONObject("appearance") ?: JSONObject()
            val appearance = ReaderAppearance(
                visualStyle = appearanceJson.optString("visualStyle", "classic")
                    .takeIf { it in setOf("classic", "clay", "glass") } ?: "classic",
                iconStyle = appearanceJson.optString("iconStyle", "classic")
                    .takeIf { it in setOf("classic", "clay", "glass") } ?: "classic",
                readerFontSize = appearanceJson.optDouble("readerFontSize", 18.0).toFloat().coerceIn(14f, 30f),
                readerLineSpacing = appearanceJson.optDouble("readerLineSpacing", 1.65).toFloat().coerceIn(1.3f, 2.2f),
                readerTheme = appearanceJson.optString("readerTheme", "paper")
                    .takeIf { it in setOf("paper", "sepia", "night") } ?: "paper"
            )

            val bookArray = manifest.optJSONArray("books") ?: JSONArray()
            require(bookArray.length() <= 500) { "В резервной копии слишком много книг." }
            val books = (0 until bookArray.length()).map { index ->
                val record = bookArray.getJSONObject(index)
                val archiveName = record.getString("file")
                require(Regex("""books/book-[0-9]+\.(pdf|txt)""").matches(archiveName)) {
                    "Недопустимый путь к файлу книги."
                }
                val extractedFile = extracted[archiveName]
                    ?: throw IllegalArgumentException("В архиве отсутствует файл: " + archiveName)
                require(extractedFile.isFile && extractedFile.length() > 0L) { "Файл книги пуст или повреждён." }
                val book = Book.fromJson(record.getJSONObject("book"))
                val storedExtension = if (book.type in setOf("epub", "fb2")) "txt" else book.type
                require(book.type in setOf("pdf", "txt", "epub", "fb2")) { "Формат книги не поддерживается." }
                require(archiveName.endsWith("." + storedExtension)) { "Формат файла не совпадает с данными книги." }
                val coverName = record.optString("coverFile", "")
                val coverFile = if (coverName.isBlank()) null else {
                    require(Regex("""covers/cover-[0-9]+\.(jpg|jpeg|png|webp|gif)""").matches(coverName)) {
                        "Недопустимый путь к обложке."
                    }
                    extracted[coverName] ?: throw IllegalArgumentException("В архиве отсутствует обложка книги.")
                }
                if (coverFile != null) require(coverFile.isFile && coverFile.length() in 1..MAX_COVER_BYTES) {
                    "Файл обложки пуст или превышает допустимый размер."
                }
                StagedBackupBook(book, extractedFile, coverFile)
            }
            require(books.map { it.book.id }.distinct().size == books.size) { "В резервной копии есть повторяющиеся книги." }
            val categoryIds = categories.map { it.id }.toSet()
            require(books.all { it.book.categoryId.isBlank() || it.book.categoryId in categoryIds }) {
                "Некоторые книги ссылаются на отсутствующие категории."
            }

            return StagedLibraryBackup(books, categories, appearance, staging)
        } catch (error: Exception) {
            staging.deleteRecursively()
            throw error
        }
    }

    fun installFiles(context: Context, backup: StagedLibraryBackup): List<Book> {
        val destination = File(context.filesDir, "books").apply { mkdirs() }
        val copiedFiles = mutableListOf<File>()
        try {
            return backup.books.map { staged ->
                val extension = staged.sourceFile.extension
                val target = File(destination, UUID.randomUUID().toString() + "." + extension)
                staged.sourceFile.copyTo(target, overwrite = false)
                copiedFiles.add(target)
                val coverPath = staged.sourceCoverFile?.let { sourceCover ->
                    val coverDirectory = File(context.filesDir, "covers").apply { mkdirs() }
                    val coverTarget = File(coverDirectory, UUID.randomUUID().toString() + "." + sourceCover.extension)
                    sourceCover.copyTo(coverTarget, overwrite = false)
                    copiedFiles.add(coverTarget)
                    coverTarget.absolutePath
                }.orEmpty()
                staged.book.copy(path = target.absolutePath, coverPath = coverPath)
            }
        } catch (error: Exception) {
            copiedFiles.forEach { it.delete() }
            throw error
        }
    }

    fun deleteStaging(backup: StagedLibraryBackup) {
        backup.stagingDirectory.deleteRecursively()
    }

    private fun copyEntry(
        input: InputStream,
        output: OutputStream,
        entryLimit: Long,
        totalBefore: Long
    ): Long {
        val buffer = ByteArray(32 * 1024)
        var copied = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            copied += count
            require(copied <= entryLimit) { "Файл в резервной копии превышает допустимый размер." }
            require(totalBefore + copied <= MAX_TOTAL_BYTES) { "Резервная копия слишком большая." }
            output.write(buffer, 0, count)
        }
        return copied
    }
}
