package ru.bookapk.reader

import android.content.Intent
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Book
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

private val Teal = Color(0xFF087F78)
private val Warm = Color(0xFFF6F3EC)
private val Ink = Color(0xFF202522)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { BookApkApp() }
    }
}

@Composable
private fun BookApkApp() {
    val context = LocalContext.current
    val store = remember { LibraryStore(context) }
    val scope = rememberCoroutineScope()
    var books by remember { mutableStateOf<List<Book>>(emptyList()) }
    var categories by remember { mutableStateOf<List<BookCategory>>(emptyList()) }
    var activeBook by remember { mutableStateOf<Book?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val loaded = withContext(Dispatchers.IO) { store.load() }
        books = loaded
        categories = withContext(Dispatchers.IO) { store.loadCategories() }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                busy = true
                error = null
                try {
                    val imported = withContext(Dispatchers.IO) {
                        val name = context.contentResolver.query(uri, null, null, null, null).use { cursor ->
                            if (cursor != null && cursor.moveToFirst()) {
                                val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                                if (column >= 0) cursor.getString(column) else null
                            } else null
                        } ?: "Новая книга"
                        val ext = name.substringAfterLast('.', "").lowercase()
                        require(ext == "pdf" || ext == "txt") { "Поддерживаются только PDF и TXT." }
                        val target = File(context.filesDir, "books").apply { mkdirs() }
                        val destination = File(target, "${UUID.randomUUID()}.$ext")
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            destination.outputStream().use { output -> input.copyTo(output) }
                        } ?: throw IllegalStateException("Не удалось прочитать выбранный файл.")
                        Book(UUID.randomUUID().toString(), name.substringBeforeLast('.', name), destination.absolutePath, ext)
                    }
                    books = books + imported
                    withContext(Dispatchers.IO) { store.save(books) }
                    activeBook = imported
                } catch (e: Exception) {
                    error = e.message ?: "Не удалось добавить книгу."
                } finally { busy = false }
            }
        }
    }

    MaterialTheme(colorScheme = lightColorScheme(
        primary = Teal, onPrimary = Color.White, background = Warm,
        surface = Color.White, onSurface = Ink
    )) {
        Surface(Modifier.fillMaxSize(), color = Warm) {
            if (activeBook == null) {
                LibraryDashboard(
                    books = books,
                    categories = categories,
                    onImport = { picker.launch(arrayOf("application/pdf", "text/plain")) },
                    onOpen = { activeBook = it },
                    onCategoryCreate = { category ->
                        categories = categories + category
                        scope.launch(Dispatchers.IO) { store.saveCategories(categories) }
                    },
                    onCategoryDelete = { category ->
                        categories = categories.filterNot { it.id == category.id }
                        books = books.map { if (it.categoryId == category.id) it.copy(categoryId = "") else it }
                        scope.launch(Dispatchers.IO) {
                            store.saveCategories(categories)
                            store.save(books)
                        }
                    },
                    onBookCategoryChange = { book, categoryId ->
                        books = books.map { if (it.id == book.id) it.copy(categoryId = categoryId) else it }
                        scope.launch(Dispatchers.IO) { store.save(books) }
                    },
                    onBookDelete = { book ->
                        books = books.filterNot { it.id == book.id }
                        scope.launch(Dispatchers.IO) {
                            store.save(books)
                            runCatching { File(book.path).delete() }
                        }
                    }
                )
            } else {
                ReaderScreen(
                    book = activeBook!!,
                    onBack = { activeBook = null },
                    onProgress = { value ->
                        val updated = books.map { if (it.id == activeBook!!.id) it.copy(progress = value) else it }
                        books = updated
                        scope.launch(Dispatchers.IO) { store.save(updated) }
                    },
                    onError = { error = it }
                )
            }
        }
    }
    if (busy) AlertDialog(onDismissRequest = {}, title = { Text("Добавляем книгу") }, text = { LinearProgressIndicator(Modifier.fillMaxWidth()) }, confirmButton = {})
    error?.let { message ->
        AlertDialog(onDismissRequest = { error = null }, title = { Text("Не удалось открыть файл") }, text = { Text(message) }, confirmButton = { TextButton(onClick = { error = null }) { Text("Понятно") } })
    }
}

@Composable
private fun ReaderScreen(book: Book, onBack: () -> Unit, onProgress: (Int) -> Unit, onError: (String) -> Unit) {
    val context = LocalContext.current
    var showControls by remember { mutableStateOf(true) }
    var text by remember(book.id) { mutableStateOf<String?>(null) }
    var fontSize by remember { mutableFloatStateOf(18f) }
    var sepia by remember { mutableStateOf(false) }
    var pageCount by remember { mutableIntStateOf(0) }
    var page by remember(book.id) { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var jump by remember { mutableStateOf("") }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState(initialFirstVisibleItemIndex = book.progress.coerceAtLeast(0))
    val scrollState = rememberScrollState()

    LaunchedEffect(book.id) {
        loading = true
        try {
            if (book.type == "txt") {
                text = withContext(Dispatchers.IO) { File(book.path).readText(Charsets.UTF_8) }
            } else {
                pageCount = withContext(Dispatchers.IO) {
                    val fd = android.os.ParcelFileDescriptor.open(File(book.path), android.os.ParcelFileDescriptor.MODE_READ_ONLY)
                    PdfRenderer(fd).use { it.pageCount }.also { fd.close() }
                }
                page = book.progress.coerceAtMost((pageCount - 1).coerceAtLeast(0))
            }
        } catch (e: Exception) { onError(e.message ?: "Файл повреждён или недоступен."); onBack() }
        finally { loading = false }
    }

    Column(Modifier.fillMaxSize().background(if (sepia) Color(0xFFF1E5CE) else Color(0xFFFAF9F6))) {
        if (showControls) {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, contentDescription = "Назад") }
                Text(book.title, Modifier.weight(1f), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (book.type == "pdf" && pageCount > 0) Text("${page + 1} из $pageCount", fontSize = 12.sp, color = Teal)
            }
            if (book.type == "txt") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { fontSize = (fontSize - 1).coerceAtLeast(14f) }) { Text("A−") }
                    Text("${fontSize.toInt()} pt")
                    TextButton(onClick = { fontSize = (fontSize + 1).coerceAtMost(30f) }) { Text("A+") }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { sepia = !sepia }) { Text(if (sepia) "Белый фон" else "Сепия") }
                }
            }
        }
        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Teal) }
        } else if (book.type == "txt") {
            val paragraphs = remember(text) { (text ?: "").split(Regex("\\n\\s*\\n")).filter { it.isNotBlank() }.ifEmpty { listOf(text ?: "") } }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().clickable { showControls = !showControls },
                contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 60.dp)
            ) {
                items(paragraphs.size) { index ->
                    Text(paragraphs[index], fontSize = fontSize.sp, lineHeight = (fontSize * 1.65f).sp, color = Ink, modifier = Modifier.padding(bottom = 18.dp))
                }
            }
            LaunchedEffect(listState.firstVisibleItemIndex) {
                onProgress(if (paragraphs.size <= 1) 0 else (listState.firstVisibleItemIndex * 100 / paragraphs.size).coerceIn(0, 100))
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(pageCount) { index ->
                    PdfPage(book.path, index, Modifier.fillMaxWidth().clickable { showControls = !showControls })
                }
            }
            LaunchedEffect(page) { onProgress(if (pageCount <= 1) 0 else page * 100 / (pageCount - 1)) }
            if (showControls && pageCount > 0) {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { page = (page - 1).coerceAtLeast(0) }) { Text("Назад") }
                    OutlinedTextField(jump, { jump = it.filter(Char::isDigit) }, modifier = Modifier.width(90.dp), singleLine = true, label = { Text("Страница") })
                    TextButton(onClick = { page = ((jump.toIntOrNull() ?: 1) - 1).coerceIn(0, pageCount - 1); jump = "" }) { Text("Перейти") }
                    TextButton(onClick = { page = (page + 1).coerceAtMost(pageCount - 1) }) { Text("Далее") }
                }
            }
        }
    }
}

@Composable
private fun PdfPage(path: String, index: Int, modifier: Modifier = Modifier) {
    var bitmap by remember(path, index) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(path, index) {
        bitmap = withContext(Dispatchers.IO) {
            runCatching {
                val fd = android.os.ParcelFileDescriptor.open(File(path), android.os.ParcelFileDescriptor.MODE_READ_ONLY)
                PdfRenderer(fd).use { renderer ->
                    renderer.openPage(index).use { page ->
                        val width = 1100
                        val height = (width.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
                        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { image ->
                            page.render(image, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        }
                    }
                }.also { fd.close() }
            }.getOrNull()
        }
    }
    Card(modifier, shape = RoundedCornerShape(6.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        if (bitmap != null) Image(bitmap!!.asImageBitmap(), contentDescription = "Страница ${index + 1}", modifier = Modifier.fillMaxWidth())
        else Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Teal) }
    }
}
