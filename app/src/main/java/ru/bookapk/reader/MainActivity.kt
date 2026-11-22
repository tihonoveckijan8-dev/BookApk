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
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
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
    var activeBook by remember { mutableStateOf<Book?>(null) }
    var query by remember { mutableStateOf("") }
    var showFavorites by remember { mutableStateOf(false) }
    var categories by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedCategory by remember { mutableStateOf<String?>(null) }
    var showCategoryDialog by remember { mutableStateOf(false) }
    var categoryDraft by remember { mutableStateOf("") }
    var categoryDraftTargetBookId by remember { mutableStateOf<String?>(null) }
    var categoryTargetBook by remember { mutableStateOf<Book?>(null) }
    var sortOrder by remember { mutableStateOf("recent") }
    var showSortMenu by remember { mutableStateOf(false) }
    var showManageCategories by remember { mutableStateOf(false) }
    var categoryToRename by remember { mutableStateOf<String?>(null) }
    var renameDraft by remember { mutableStateOf("") }
    var categoryToDelete by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val loadedBooks = withContext(Dispatchers.IO) { store.load() }
        val savedCategories = withContext(Dispatchers.IO) { store.loadCategories() }
        books = loadedBooks
        // WHY: categories already attached to books are recovered even if the separate category list is older.
        categories = (savedCategories + loadedBooks.map { it.category }.filter { it.isNotBlank() })
            .distinct().sortedBy { it.lowercase() }
    }

    fun assignCategory(bookId: String, category: String) {
        val updated = books.map { item -> if (item.id == bookId) item.copy(category = category) else item }
        books = updated
        categoryTargetBook = null
        scope.launch(Dispatchers.IO) { store.save(updated) }
    }

    fun renameCategory(oldName: String, newName: String) {
        val cleanName = newName.trim()
        if (cleanName.isBlank()) return
        val updatedCategories = categories.map { if (it == oldName) cleanName else it }
            .distinctBy { it.lowercase() }.sortedBy { it.lowercase() }
        val updatedBooks = books.map { if (it.category == oldName) it.copy(category = cleanName) else it }
        categories = updatedCategories
        books = updatedBooks
        if (selectedCategory == oldName) selectedCategory = cleanName
        // WHY: update the category catalog and every linked book together to avoid orphaned folder labels.
        scope.launch(Dispatchers.IO) {
            store.saveCategories(updatedCategories)
            store.save(updatedBooks)
        }
    }

    fun deleteCategory(category: String) {
        val updatedCategories = categories.filterNot { it == category }
        val updatedBooks = books.map { if (it.category == category) it.copy(category = "") else it }
        categories = updatedCategories
        books = updatedBooks
        if (selectedCategory == category) selectedCategory = null
        // WHY: removing a category returns its books to the main library; no files or reading progress are deleted.
        scope.launch(Dispatchers.IO) {
            store.saveCategories(updatedCategories)
            store.save(updatedBooks)
        }
    }

    fun updateActiveBook(transform: (Book) -> Book) {
        val activeId = activeBook?.id ?: return
        val updatedBooks = books.map { current ->
            if (current.id == activeId) transform(current) else current
        }
        val updatedActive = updatedBooks.firstOrNull { it.id == activeId } ?: return
        // WHY: ignore no-op scroll events so DataStore is not rewritten unnecessarily.
        if (updatedActive == activeBook) return
        books = updatedBooks
        activeBook = updatedActive
        scope.launch(Dispatchers.IO) { store.save(updatedBooks) }
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
                Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 20.dp)) {
                    Spacer(Modifier.height(18.dp))
                    Text("Моя библиотека", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Ink)
                    Text("Ваши книги — всегда под рукой", color = Color(0xFF737A75), fontSize = 14.sp)
                    Spacer(Modifier.height(22.dp))
                    OutlinedTextField(
                        value = query, onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        placeholder = { Text("Найти книгу") },
                        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = "Поиск") },
                        shape = RoundedCornerShape(18.dp)
                    )
                    Spacer(Modifier.height(18.dp))
                    val last = books.filter { it.progress > 0 }.maxByOrNull { it.progress }
                    if (last != null) {
                        Text("Продолжить чтение", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                        Spacer(Modifier.height(10.dp))
                        Card(
                            modifier = Modifier.fillMaxWidth().clickable { activeBook = last },
                            shape = RoundedCornerShape(22.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFE2F0EC))
                        ) {
                            Column(Modifier.padding(18.dp)) {
                                Text(last.title, fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.height(12.dp))
                                LinearProgressIndicator(
                                    progress = { (last.progress / 100f).coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxWidth(), color = Teal
                                )
                                Spacer(Modifier.height(7.dp))
                                Text("Продолжить · ${last.progress}%", color = Teal, fontWeight = FontWeight.Medium)
                            }
                        }
                        Spacer(Modifier.height(22.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (showFavorites) "Избранное" else "Все книги", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        TextButton(onClick = { showManageCategories = true }) { Text("Папки") }
                        TextButton(onClick = { categoryDraftTargetBookId = null; showCategoryDialog = true }) { Text("＋") }
                        TextButton(onClick = { showFavorites = !showFavorites }) {
                            Icon(if (showFavorites) Icons.Rounded.Book else Icons.Rounded.FavoriteBorder, contentDescription = null, modifier = Modifier.size(17.dp))
                            Spacer(Modifier.width(5.dp))
                            Text(if (showFavorites) "Все" else "Избранное")
                        }
                    }
                    if (categories.isNotEmpty()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = selectedCategory == null,
                                onClick = { selectedCategory = null },
                                label = { Text("Все категории") }
                            )
                            categories.forEach { category ->
                                FilterChip(
                                    selected = selectedCategory == category,
                                    onClick = { selectedCategory = if (selectedCategory == category) null else category },
                                    label = { Text("$category · ${books.count { it.category == category }}") }
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    // WHY: combine text, favorites and category filters without changing the reader's saved progress.
                    val filtered = books.filter {
                        it.title.contains(query, ignoreCase = true) &&
                            (!showFavorites || it.isFavorite) &&
                            (selectedCategory == null || it.category == selectedCategory)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("${filtered.size} книг", color = Color(0xFF7A837D), fontSize = 12.sp, modifier = Modifier.weight(1f))
                        Box {
                            TextButton(onClick = { showSortMenu = true }) {
                                Text(
                                    when (sortOrder) {
                                        "title" -> "По названию"
                                        "progress" -> "По прогрессу"
                                        else -> "Сначала новые"
                                    },
                                    fontSize = 12.sp
                                )
                            }
                            DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text("Сначала новые") },
                                    onClick = { sortOrder = "recent"; showSortMenu = false }
                                )
                                DropdownMenuItem(
                                    text = { Text("По названию") },
                                    onClick = { sortOrder = "title"; showSortMenu = false }
                                )
                                DropdownMenuItem(
                                    text = { Text("По прогрессу") },
                                    onClick = { sortOrder = "progress"; showSortMenu = false }
                                )
                            }
                        }
                    }
                    val sortedBooks = when (sortOrder) {
                        "title" -> filtered.sortedBy { it.title.lowercase() }
                        "progress" -> filtered.sortedByDescending { it.progress }
                        else -> filtered.sortedByDescending { it.addedAt }
                    }
                    if (filtered.isEmpty()) {
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Rounded.Book, null, tint = Teal, modifier = Modifier.size(52.dp))
                                Spacer(Modifier.height(12.dp))
                                Text(if (books.isEmpty()) "Добавьте PDF или TXT, чтобы начать читать" else "Ничего не найдено", color = Color(0xFF6F7771))
                                if (books.isEmpty()) {
                                    Spacer(Modifier.height(14.dp))
                                    Button(onClick = { picker.launch(arrayOf("application/pdf", "text/plain")) }) { Text("Добавить книгу") }
                                }
                            }
                        }
                    } else {
                        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 100.dp)) {
                            items(sortedBooks, key = { it.id }) { book ->
                                Card(
                                    modifier = Modifier.fillMaxWidth().clickable { activeBook = book },
                                    shape = RoundedCornerShape(18.dp),
                                    colors = CardDefaults.cardColors(containerColor = Color.White)
                                ) {
                                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Box(Modifier.size(54.dp, 70.dp).background(if (book.type == "pdf") Color(0xFFE3E9E7) else Color(0xFFD9EEE8), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                                            Text(if (book.type == "pdf") "PDF" else "TXT", color = Teal, fontWeight = FontWeight.Bold)
                                        }
                                        Spacer(Modifier.width(14.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(book.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                            Text(if (book.type == "pdf") "Документ PDF" else "Текстовая книга", color = Color.Gray, fontSize = 13.sp)
                                            if (book.category.isNotBlank()) Text(book.category, color = Teal, fontSize = 12.sp)
                                            if (book.progress > 0) {
                                                Text("Прочитано · ${book.progress}%", color = Teal, fontSize = 11.sp)
                                                LinearProgressIndicator(
                                                    progress = { (book.progress / 100f).coerceIn(0f, 1f) },
                                                    modifier = Modifier.fillMaxWidth().padding(top = 5.dp).height(4.dp),
                                                    color = Teal,
                                                    trackColor = Color(0xFFE4ECE8)
                                                )
                                            }
                                        }
                                        IconButton(onClick = {
                                            val updated = books.map { item -> if (item.id == book.id) item.copy(isFavorite = !item.isFavorite) else item }
                                            books = updated
                                            scope.launch(Dispatchers.IO) { store.save(updated) }
                                        }) {
                                            Icon(
                                                if (book.isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                                contentDescription = if (book.isFavorite) "Убрать из избранного" else "В избранное",
                                                tint = if (book.isFavorite) Color(0xFFD65A69) else Color(0xFF8A938D)
                                            )
                                        }
                                        TextButton(onClick = { categoryTargetBook = book }) {
                                            Text("Папка", fontSize = 11.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (books.isNotEmpty()) {
                    ExtendedFloatingActionButton(
                        onClick = { picker.launch(arrayOf("application/pdf", "text/plain")) },
                        modifier = Modifier.padding(20.dp).navigationBarsPadding(),
                        icon = { Icon(Icons.Rounded.Add, contentDescription = "Добавить") },
                        text = { Text("Добавить книгу") },
                        containerColor = Teal, contentColor = Color.White
                    )
                }
            } else {
                ReaderScreen(
                    book = activeBook!!,
                    onBack = { activeBook = null },
                    onUpdateBook = { transform -> updateActiveBook(transform) },
                    onError = { error = it }
                )
            }
        }
    }
    if (busy) AlertDialog(onDismissRequest = {}, title = { Text("Добавляем книгу") }, text = { LinearProgressIndicator(Modifier.fillMaxWidth()) }, confirmButton = {})
    error?.let { message ->
        AlertDialog(onDismissRequest = { error = null }, title = { Text("Не удалось открыть файл") }, text = { Text(message) }, confirmButton = { TextButton(onClick = { error = null }) { Text("Понятно") } })
    }
    if (showCategoryDialog) {
        AlertDialog(
            onDismissRequest = { showCategoryDialog = false; categoryDraft = ""; categoryDraftTargetBookId = null },
            title = { Text("Новая категория") },
            text = {
                OutlinedTextField(
                    value = categoryDraft,
                    onValueChange = { categoryDraft = it.take(32) },
                    singleLine = true,
                    label = { Text("Название") },
                    placeholder = { Text("Например, Учёба") }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = categoryDraft.trim()
                    if (name.isNotBlank()) {
                        // WHY: reuse an existing folder on a case-insensitive match instead of creating duplicates.
                        val resolvedName = categories.firstOrNull { it.equals(name, ignoreCase = true) } ?: name
                        if (categories.none { it.equals(resolvedName, ignoreCase = true) }) {
                            val updated = (categories + resolvedName).sortedBy { it.lowercase() }
                            categories = updated
                            scope.launch(Dispatchers.IO) { store.saveCategories(updated) }
                        }
                        categoryDraftTargetBookId?.let { bookId -> assignCategory(bookId, resolvedName) }
                    }
                    categoryDraft = ""
                    categoryDraftTargetBookId = null
                    showCategoryDialog = false
                }) { Text("Создать") }
            },
            dismissButton = { TextButton(onClick = { showCategoryDialog = false; categoryDraft = ""; categoryDraftTargetBookId = null }) { Text("Отмена") } }
        )
    }
    if (showManageCategories) {
        AlertDialog(
            onDismissRequest = { showManageCategories = false },
            title = { Text("Мои папки") },
            text = {
                if (categories.isEmpty()) {
                    Text("Пока нет категорий. Создайте первую с помощью кнопки ＋.")
                } else {
                    Column(
                        modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        categories.forEach { category ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(category, fontWeight = FontWeight.Medium)
                                    Text("${books.count { it.category == category }} книг", color = Color.Gray, fontSize = 11.sp)
                                }
                                TextButton(onClick = {
                                    categoryToRename = category
                                    renameDraft = category
                                    showManageCategories = false
                                }) { Text("Изменить") }
                                TextButton(onClick = {
                                    categoryToDelete = category
                                    showManageCategories = false
                                }) { Text("Удалить", color = Color(0xFFB94A55)) }
                            }
                            HorizontalDivider(color = Color(0xFFECEEEA))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showManageCategories = false }) { Text("Готово") }
            },
            dismissButton = {
                TextButton(onClick = { showManageCategories = false; categoryDraftTargetBookId = null; showCategoryDialog = true }) { Text("＋ Создать") }
            }
        )
    }
    categoryToRename?.let { oldName ->
        AlertDialog(
            onDismissRequest = { categoryToRename = null; renameDraft = "" },
            title = { Text("Переименовать папку") },
            text = {
                OutlinedTextField(
                    value = renameDraft,
                    onValueChange = { renameDraft = it.take(32) },
                    singleLine = true,
                    label = { Text("Новое название") }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val newName = renameDraft.trim()
                    val duplicate = categories.any { it != oldName && it.equals(newName, ignoreCase = true) }
                    if (newName.isNotBlank() && !duplicate) renameCategory(oldName, newName)
                    categoryToRename = null
                    renameDraft = ""
                    showManageCategories = true
                }) { Text("Сохранить") }
            },
            dismissButton = {
                TextButton(onClick = { categoryToRename = null; renameDraft = ""; showManageCategories = true }) { Text("Отмена") }
            }
        )
    }
    categoryToDelete?.let { category ->
        AlertDialog(
            onDismissRequest = { categoryToDelete = null; showManageCategories = true },
            title = { Text("Удалить папку?") },
            text = { Text("Книги останутся в библиотеке, а категория «$category» будет снята с них.") },
            confirmButton = {
                TextButton(onClick = {
                    deleteCategory(category)
                    categoryToDelete = null
                    showManageCategories = true
                }) { Text("Удалить", color = Color(0xFFB94A55)) }
            },
            dismissButton = {
                TextButton(onClick = { categoryToDelete = null; showManageCategories = true }) { Text("Отмена") }
            }
        )
    }
    categoryTargetBook?.let { target ->
        AlertDialog(
            onDismissRequest = { categoryTargetBook = null },
            title = { Text("Категория книги") },
            text = {
                Column {
                    TextButton(onClick = { assignCategory(target.id, "") }) { Text("Без категории") }
                    categories.forEach { category ->
                        TextButton(onClick = { assignCategory(target.id, category) }) { Text(category) }
                    }
                    TextButton(onClick = {
                        categoryDraftTargetBookId = target.id
                        categoryTargetBook = null
                        showCategoryDialog = true
                    }) { Text("＋ Создать категорию") }
                }
            },
            confirmButton = { TextButton(onClick = { categoryTargetBook = null }) { Text("Готово") } }
        )
    }
}

@Composable
private fun ReaderScreen(
    book: Book,
    onBack: () -> Unit,
    onUpdateBook: ((Book) -> Book) -> Unit,
    onError: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showControls by remember { mutableStateOf(true) }
    var text by remember(book.id) { mutableStateOf<String?>(null) }
    var fontSize by remember { mutableFloatStateOf(18f) }
    var sepia by remember { mutableStateOf(false) }
    var pageCount by remember { mutableIntStateOf(0) }
    var page by remember(book.id) { mutableIntStateOf(book.readingPosition.coerceAtLeast(0)) }
    var currentPosition by remember(book.id) { mutableIntStateOf(book.readingPosition.coerceAtLeast(0)) }
    var loading by remember { mutableStateOf(true) }
    var jump by remember { mutableStateOf("") }
    var showBookmarksDialog by remember { mutableStateOf(false) }
    var showNotesDialog by remember { mutableStateOf(false) }
    var showNoteEditor by remember { mutableStateOf(false) }
    var noteDraft by remember { mutableStateOf("") }

    val txtListState = androidx.compose.foundation.lazy.rememberLazyListState()
    val pdfListState = androidx.compose.foundation.lazy.rememberLazyListState()
    var txtRestored by remember(book.id) { mutableStateOf(false) }
    var pdfRestored by remember(book.id) { mutableStateOf(false) }

    LaunchedEffect(book.id) {
        loading = true
        try {
            if (book.type == "txt") {
                text = withContext(Dispatchers.IO) { File(book.path).readText(Charsets.UTF_8) }
            } else {
                pageCount = withContext(Dispatchers.IO) {
                    val fd = android.os.ParcelFileDescriptor.open(
                        File(book.path),
                        android.os.ParcelFileDescriptor.MODE_READ_ONLY
                    )
                    try {
                        PdfRenderer(fd).use { it.pageCount }
                    } finally {
                        fd.close()
                    }
                }
            }
        } catch (e: Exception) {
            onError(e.message ?: "Файл повреждён или недоступен.")
            onBack()
        } finally {
            loading = false
        }
    }

    fun jumpTo(position: Int) {
        scope.launch {
            if (book.type == "txt") {
                val total = txtListState.layoutInfo.totalItemsCount
                if (total > 0) {
                    txtListState.animateScrollToItem(position.coerceIn(0, total - 1))
                }
            } else if (pageCount > 0) {
                val target = position.coerceIn(0, pageCount - 1)
                page = target
                pdfListState.animateScrollToItem(target)
            }
        }
    }

    val isBookmarkedHere = book.bookmarks.any { it.position == currentPosition }
    val positionLabel = if (book.type == "pdf") {
        "Страница " + (currentPosition + 1)
    } else {
        "Фрагмент " + (currentPosition + 1)
    }

    Column(
        Modifier.fillMaxSize().background(if (sepia) Color(0xFFF1E5CE) else Color(0xFFFAF9F6))
    ) {
        if (showControls) {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Rounded.ArrowBack, contentDescription = "Назад")
                }
                Text(
                    book.title,
                    Modifier.weight(1f),
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (book.type == "pdf" && pageCount > 0) {
                    Text((page + 1).toString() + " из " + pageCount, fontSize = 12.sp, color = Teal)
                }
            }

            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    enabled = !isBookmarkedHere,
                    onClick = {
                        val bookmark = ReaderBookmark(
                            id = UUID.randomUUID().toString(),
                            position = currentPosition,
                            title = positionLabel
                        )
                        onUpdateBook { current ->
                            if (current.bookmarks.any { it.position == bookmark.position }) current
                            else current.copy(bookmarks = (current.bookmarks + bookmark).sortedBy { it.position })
                        }
                    }
                ) {
                    Text(if (isBookmarkedHere) "Закладка ✓" else "＋ Закладка")
                }
                TextButton(onClick = { showBookmarksDialog = true }) {
                    Text("Закладки · " + book.bookmarks.size)
                }
                TextButton(onClick = {
                    noteDraft = ""
                    showNoteEditor = true
                }) {
                    Text("＋ Заметка")
                }
                TextButton(onClick = { showNotesDialog = true }) {
                    Text("Заметки · " + book.notes.size)
                }
            }

            if (book.type == "txt") {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { fontSize = (fontSize - 1).coerceAtLeast(14f) }) { Text("A−") }
                    Text(fontSize.toInt().toString() + " pt")
                    TextButton(onClick = { fontSize = (fontSize + 1).coerceAtMost(30f) }) { Text("A+") }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { sepia = !sepia }) {
                        Text(if (sepia) "Белый фон" else "Сепия")
                    }
                }
            }
        }

        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Teal)
            }
        } else if (book.type == "txt") {
            val paragraphs = remember(text) {
                (text ?: "").split(Regex("\\n\\s*\\n")).filter { it.isNotBlank() }
                    .ifEmpty { listOf(text ?: "") }
            }

            LaunchedEffect(book.id, paragraphs.size) {
                val target = book.readingPosition.coerceIn(0, (paragraphs.size - 1).coerceAtLeast(0))
                if (paragraphs.isNotEmpty()) txtListState.scrollToItem(target)
                txtRestored = true
            }

            LaunchedEffect(txtRestored, txtListState.firstVisibleItemIndex, paragraphs.size) {
                if (txtRestored && paragraphs.isNotEmpty()) {
                    val position = txtListState.firstVisibleItemIndex.coerceIn(0, paragraphs.lastIndex)
                    val progress = if (paragraphs.size <= 1) 0
                    else (position * 100 / (paragraphs.size - 1)).coerceIn(0, 100)
                    currentPosition = position
                    onUpdateBook { current ->
                        if (current.readingPosition == position && current.progress == progress) current
                        else current.copy(readingPosition = position, progress = progress)
                    }
                }
            }

            LazyColumn(
                state = txtListState,
                modifier = Modifier.fillMaxSize().clickable { showControls = !showControls },
                contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 60.dp)
            ) {
                items(paragraphs.size) { index ->
                    Text(
                        paragraphs[index],
                        fontSize = fontSize.sp,
                        lineHeight = (fontSize * 1.65f).sp,
                        color = Ink,
                        modifier = Modifier.padding(bottom = 18.dp)
                    )
                }
            }
        } else {
            LaunchedEffect(book.id, pageCount) {
                if (pageCount > 0) {
                    val target = book.readingPosition.coerceIn(0, pageCount - 1)
                    pdfListState.scrollToItem(target)
                    page = target
                    pdfRestored = true
                }
            }

            LaunchedEffect(pdfRestored, pdfListState.firstVisibleItemIndex, pageCount) {
                if (pdfRestored && pageCount > 0) {
                    val position = pdfListState.firstVisibleItemIndex.coerceIn(0, pageCount - 1)
                    val progress = if (pageCount <= 1) 0
                    else (position * 100 / (pageCount - 1)).coerceIn(0, 100)
                    page = position
                    currentPosition = position
                    onUpdateBook { current ->
                        if (current.readingPosition == position && current.progress == progress) current
                        else current.copy(readingPosition = position, progress = progress)
                    }
                }
            }

            LaunchedEffect(page, pdfRestored, pageCount) {
                if (pdfRestored && pageCount > 0 && pdfListState.firstVisibleItemIndex != page) {
                    pdfListState.animateScrollToItem(page.coerceIn(0, pageCount - 1))
                }
            }

            LazyColumn(
                state = pdfListState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(pageCount) { index ->
                    PdfPage(book.path, index, Modifier.fillMaxWidth().clickable { showControls = !showControls })
                }
            }

            if (showControls && pageCount > 0) {
                Row(
                    Modifier.fillMaxWidth().padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { page = (page - 1).coerceAtLeast(0) }) { Text("Назад") }
                    OutlinedTextField(
                        jump,
                        { jump = it.filter(Char::isDigit) },
                        modifier = Modifier.width(90.dp),
                        singleLine = true,
                        label = { Text("Страница") }
                    )
                    TextButton(onClick = {
                        page = ((jump.toIntOrNull() ?: 1) - 1).coerceIn(0, pageCount - 1)
                        jump = ""
                    }) { Text("Перейти") }
                    TextButton(onClick = { page = (page + 1).coerceAtMost(pageCount - 1) }) {
                        Text("Далее")
                    }
                }
            }
        }
    }

    if (showNoteEditor) {
        AlertDialog(
            onDismissRequest = { showNoteEditor = false; noteDraft = "" },
            title = { Text("Заметка · " + positionLabel) },
            text = {
                OutlinedTextField(
                    value = noteDraft,
                    onValueChange = { noteDraft = it.take(2000) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 110.dp),
                    placeholder = { Text("Запишите мысль или комментарий к этому месту…") }
                )
            },
            confirmButton = {
                TextButton(
                    enabled = noteDraft.isNotBlank(),
                    onClick = {
                        val note = ReaderNote(
                            id = UUID.randomUUID().toString(),
                            position = currentPosition,
                            text = noteDraft.trim()
                        )
                        onUpdateBook { current -> current.copy(notes = current.notes + note) }
                        noteDraft = ""
                        showNoteEditor = false
                    }
                ) { Text("Сохранить") }
            },
            dismissButton = {
                TextButton(onClick = { showNoteEditor = false; noteDraft = "" }) { Text("Отмена") }
            }
        )
    }

    if (showBookmarksDialog) {
        AlertDialog(
            onDismissRequest = { showBookmarksDialog = false },
            title = { Text("Закладки") },
            text = {
                if (book.bookmarks.isEmpty()) {
                    Text("Пока нет закладок. Добавьте закладку во время чтения.")
                } else {
                    Column(
                        Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        book.bookmarks.sortedBy { it.position }.forEach { bookmark ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(bookmark.title, fontWeight = FontWeight.Medium)
                                    Text(
                                        if (book.type == "pdf") "Страница " + (bookmark.position + 1)
                                        else "Фрагмент " + (bookmark.position + 1),
                                        color = Color.Gray,
                                        fontSize = 11.sp
                                    )
                                }
                                TextButton(onClick = {
                                    showBookmarksDialog = false
                                    jumpTo(bookmark.position)
                                }) { Text("Открыть") }
                                TextButton(onClick = {
                                    onUpdateBook { current ->
                                        current.copy(bookmarks = current.bookmarks.filterNot { it.id == bookmark.id })
                                    }
                                }) { Text("Удалить") }
                            }
                            HorizontalDivider(color = Color(0xFFECEEEA))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showBookmarksDialog = false }) { Text("Готово") }
            }
        )
    }

    if (showNotesDialog) {
        AlertDialog(
            onDismissRequest = { showNotesDialog = false },
            title = { Text("Заметки") },
            text = {
                if (book.notes.isEmpty()) {
                    Text("Заметок пока нет. Добавьте первую заметку к текущему месту чтения.")
                } else {
                    Column(
                        Modifier.heightIn(max = 390.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        book.notes.sortedByDescending { it.createdAt }.forEach { note ->
                            Column {
                                Text(
                                    if (book.type == "pdf") "Страница " + (note.position + 1)
                                    else "Фрагмент " + (note.position + 1),
                                    color = Teal,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 12.sp
                                )
                                Text(note.text, maxLines = 4, overflow = TextOverflow.Ellipsis)
                                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                                    TextButton(onClick = {
                                        showNotesDialog = false
                                        jumpTo(note.position)
                                    }) { Text("К месту") }
                                    TextButton(onClick = {
                                        onUpdateBook { current ->
                                            current.copy(notes = current.notes.filterNot { it.id == note.id })
                                        }
                                    }) { Text("Удалить") }
                                }
                            }
                            HorizontalDivider(color = Color(0xFFECEEEA))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showNotesDialog = false }) { Text("Готово") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showNotesDialog = false
                    noteDraft = ""
                    showNoteEditor = true
                }) { Text("＋ Добавить") }
            }
        )
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
