package ru.bookapk.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.UUID

private val LibraryTeal = Color(0xFF087F78)
private val LibraryInk = Color(0xFF202522)
private val LibraryMuted = Color(0xFF7C8781)
private val LibraryCanvas = Color(0xFFF6F5F0)

@Composable
fun LibraryDashboard(
    books: List<Book>,
    categories: List<BookCategory>,
    onImport: () -> Unit,
    onSettings: () -> Unit,
    visualStyle: String,
    onOpen: (Book) -> Unit,
    onCategoryCreate: (BookCategory) -> Unit,
    onCategoryDelete: (BookCategory) -> Unit,
    onBookCategoryChange: (Book, String) -> Unit,
    onBookDelete: (Book) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf<String?>(null) }
    var showCategoryDialog by remember { mutableStateOf(false) }
    var categoryName by remember { mutableStateOf("") }

    val filtered = books.filter { book ->
        book.title.contains(query, ignoreCase = true) &&
            (selectedCategory == null || book.categoryId == selectedCategory)
    }
    val lastRead = books.filter { it.progress > 0 }.maxByOrNull { it.progress }
    val categoryLabel = { id: String -> categories.firstOrNull { it.id == id }?.name ?: "Без категории" }
    val canvas = when (visualStyle) { "clay" -> Color(0xFFE8E5DE); "glass" -> Color(0xFFEAF1F2); else -> LibraryCanvas }
    val cardSurface = when (visualStyle) { "clay" -> Color(0xFFECE9E1); "glass" -> Color(0xDDFBFFFF); else -> Color.White }

    Column(
        Modifier.fillMaxSize().background(canvas).statusBarsPadding()
            .padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Моя читалка", fontSize = 29.sp, fontWeight = FontWeight.Bold, color = LibraryInk)
                Text("Личное пространство для чтения", color = LibraryMuted, fontSize = 13.sp)
            }
            IconButton(onClick = onSettings) { Icon(Icons.Rounded.Settings, contentDescription = "Настройки оформления", tint = LibraryMuted) }
            FilledIconButton(
                onClick = onImport,
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = LibraryTeal)
            ) { Icon(Icons.Rounded.Add, contentDescription = "Добавить книгу", tint = Color.White) }
        }
        Spacer(Modifier.height(18.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("Название книги или файла") },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = "Поиск") },
            shape = RoundedCornerShape(18.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = LibraryTeal,
                unfocusedBorderColor = if (visualStyle == "glass") Color.White.copy(alpha = .8f) else Color(0xFFE5E6E0),
                focusedContainerColor = cardSurface,
                unfocusedContainerColor = cardSurface
            )
        )
        Spacer(Modifier.height(14.dp))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(
                selected = selectedCategory == null,
                onClick = { selectedCategory = null },
                label = { Text("Все книги · ${books.size}") }
            )
            categories.forEach { category ->
                FilterChip(
                    selected = selectedCategory == category.id,
                    onClick = { selectedCategory = category.id },
                    label = { Text(category.name) }
                )
            }
            FilledTonalIconButton(
                onClick = { showCategoryDialog = true },
                modifier = Modifier.size(38.dp),
                colors = IconButtonDefaults.filledTonalIconButtonColors(contentColor = LibraryTeal)
            ) { Icon(Icons.Rounded.FolderOpen, contentDescription = "Управление категориями") }
        }

        if (lastRead != null && query.isBlank() && selectedCategory == null) {
            Spacer(Modifier.height(16.dp))
            Text("ВОЗВРАЩАЙТЕСЬ К ЧТЕНИЮ", fontSize = 10.sp, letterSpacing = 1.3.sp, fontWeight = FontWeight.Bold, color = LibraryTeal)
            Spacer(Modifier.height(8.dp))
            Card(
                onClick = { onOpen(lastRead) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = if (visualStyle == "classic") Color(0xFFE2F0EC) else cardSurface)
            ) {
                Row(Modifier.padding(17.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(54.dp, 68.dp).background(if (visualStyle == "clay") Color(0xFFD7D3CA) else Color(0xFFC7E2D9), RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center
                    ) { Icon(Icons.Rounded.AutoStories, null, tint = LibraryTeal, modifier = Modifier.size(27.dp)) }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(lastRead.title, fontWeight = FontWeight.Bold, color = LibraryInk, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${categoryLabel(lastRead.categoryId)} · ${lastRead.progress}% прочитано", color = LibraryMuted, fontSize = 12.sp)
                        Spacer(Modifier.height(9.dp))
                        LinearProgressIndicator(
                            progress = { (lastRead.progress / 100f).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(5.dp),
                            color = LibraryTeal,
                            trackColor = Color(0xFFC9DDD5)
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(19.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (selectedCategory == null) "Библиотека" else categoryLabel(selectedCategory!!), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = LibraryInk, modifier = Modifier.weight(1f))
            Text("${filtered.size} книг", color = LibraryMuted, fontSize = 12.sp)
        }
        Spacer(Modifier.height(10.dp))

        if (filtered.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.AutoStories, null, tint = Color(0xFF9ABCB1), modifier = Modifier.size(54.dp))
                    Spacer(Modifier.height(12.dp))
                    Text(if (books.isEmpty()) "Ваша библиотека пока пуста" else "В этой категории пока нет книг", color = LibraryInk, fontWeight = FontWeight.SemiBold)
                    Text("Добавьте PDF или TXT с устройства", color = LibraryMuted, fontSize = 13.sp)
                    Spacer(Modifier.height(15.dp))
                    Button(onClick = onImport, colors = ButtonDefaults.buttonColors(containerColor = LibraryTeal)) { Text("Добавить книгу") }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 28.dp)
            ) {
                items(filtered, key = { it.id }) { book ->
                    var menuExpanded by remember(book.id) { mutableStateOf(false) }
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(19.dp),
                        colors = CardDefaults.cardColors(containerColor = cardSurface),
                        elevation = CardDefaults.cardElevation(defaultElevation = if (visualStyle == "clay") 5.dp else 1.dp),
                        border = if (visualStyle == "glass") androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = .85f)) else null
                    ) {
                        Row(
                            Modifier.padding(start = 12.dp, end = 7.dp, top = 12.dp, bottom = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                Modifier.size(54.dp, 70.dp).background(
                                    if (book.type == "pdf") Color(0xFFECE8DE) else Color(0xFFDDEFE9),
                                    RoundedCornerShape(9.dp)
                                ),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(book.type.uppercase(), color = LibraryTeal, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
                            }
                            Spacer(Modifier.width(13.dp))
                            Column(
                                Modifier.weight(1f).clickable { onOpen(book) }
                            ) {
                                Text(book.title, color = LibraryInk, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.height(4.dp))
                                Text(categoryLabel(book.categoryId), color = LibraryMuted, fontSize = 12.sp)
                                if (book.progress > 0) {
                                    Spacer(Modifier.height(7.dp))
                                    LinearProgressIndicator(
                                        progress = { (book.progress / 100f).coerceIn(0f, 1f) },
                                        modifier = Modifier.fillMaxWidth().height(4.dp),
                                        color = LibraryTeal
                                    )
                                }
                            }
                            Box {
                                IconButton(onClick = { menuExpanded = true }) {
                                    Icon(Icons.Rounded.MoreVert, contentDescription = "Действия с книгой", tint = LibraryMuted)
                                }
                                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                                    categories.forEach { category ->
                                        DropdownMenuItem(
                                            text = { Text("В категорию: ${category.name}") },
                                            onClick = {
                                                onBookCategoryChange(book, category.id)
                                                menuExpanded = false
                                            }
                                        )
                                    }
                                    DropdownMenuItem(
                                        text = { Text("Без категории") },
                                        onClick = { onBookCategoryChange(book, ""); menuExpanded = false }
                                    )
                                    HorizontalDivider()
                                    DropdownMenuItem(
                                        text = { Text("Удалить из библиотеки", color = MaterialTheme.colorScheme.error) },
                                        leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
                                        onClick = { onBookDelete(book); menuExpanded = false }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCategoryDialog) {
        AlertDialog(
            onDismissRequest = { showCategoryDialog = false; categoryName = "" },
            title = { Text("Категории библиотеки") },
            text = {
                Column {
                    OutlinedTextField(
                        value = categoryName,
                        onValueChange = { categoryName = it },
                        label = { Text("Новая категория") },
                        placeholder = { Text("Например, Фантастика") },
                        singleLine = true
                    )
                    Spacer(Modifier.height(8.dp))
                    categories.forEach { category ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(category.name, Modifier.weight(1f), color = LibraryInk)
                            IconButton(onClick = { if (selectedCategory == category.id) selectedCategory = null; onCategoryDelete(category) }) {
                                Icon(Icons.Rounded.DeleteOutline, contentDescription = "Удалить категорию", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = categoryName.isNotBlank() && categories.none { it.name.equals(categoryName.trim(), true) },
                    onClick = {
                        onCategoryCreate(BookCategory(UUID.randomUUID().toString(), categoryName.trim()))
                        categoryName = ""
                    }
                ) { Text("Добавить") }
            },
            dismissButton = { TextButton(onClick = { showCategoryDialog = false; categoryName = "" }) { Text("Готово") } }
        )
    }
}
