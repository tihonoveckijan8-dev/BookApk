package ru.bookapk.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun LibraryNotesScreen(
    books: List<Book>,
    onBack: () -> Unit,
    onOpen: (Book) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var selectedColor by remember { mutableStateOf("all") }
    val allNotes = remember(books) {
        books.flatMap { book -> book.annotations.map { annotation -> book to annotation } }
            .sortedByDescending { it.second.createdAt }
    }
    val filteredNotes = allNotes.filter { (book, annotation) ->
        val matchesQuery = query.isBlank() ||
            book.title.contains(query, ignoreCase = true) ||
            book.author.contains(query, ignoreCase = true) ||
            annotation.text.contains(query, ignoreCase = true) ||
            annotation.note.contains(query, ignoreCase = true)
        matchesQuery && (selectedColor == "all" || annotation.color == selectedColor)
    }
    val ink = MaterialTheme.colorScheme.onSurface
    val muted = ink.copy(alpha = .60f)
    val accent = MaterialTheme.colorScheme.primary
    val surface = MaterialTheme.colorScheme.surface

    Column(
        Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .padding(horizontal = 20.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Rounded.ArrowBack, contentDescription = "Назад")
            }
            Column(Modifier.weight(1f)) {
                Text("Мои заметки", fontSize = 25.sp, fontWeight = FontWeight.Bold, color = ink)
                Text("${allNotes.size} фрагментов из ${books.size} книг", fontSize = 12.sp, color = muted)
            }
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("Поиск по книгам и заметкам") },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = "Поиск") },
            shape = RoundedCornerShape(18.dp)
        )
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            listOf("all" to "Все", "yellow" to "Жёлтые", "green" to "Зелёные", "blue" to "Синие", "pink" to "Розовые").forEach { (key, label) ->
                FilterChip(
                    selected = selectedColor == key,
                    onClick = { selectedColor = key },
                    label = { Text(label, fontSize = 11.sp) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            if (query.isBlank()) "ПОСЛЕДНИЕ ФРАГМЕНТЫ" else "РЕЗУЛЬТАТЫ ПОИСКА",
            color = accent,
            fontSize = 10.sp,
            letterSpacing = 1.2.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(8.dp))

        if (filteredNotes.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.AutoStories, contentDescription = null, tint = accent.copy(alpha = .55f), modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(12.dp))
                    Text(
                        if (allNotes.isEmpty()) "Здесь пока нет заметок" else "Ничего не найдено",
                        color = ink,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        if (allNotes.isEmpty()) "Выделяйте важные места во время чтения — они соберутся здесь." else "Измените запрос или выберите другой цвет.",
                        color = muted,
                        fontSize = 12.sp
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(9.dp),
                contentPadding = PaddingValues(bottom = 28.dp)
            ) {
                items(filteredNotes, key = { (book, annotation) -> "${book.id}-${annotation.id}" }) { (book, annotation) ->
                    val marker = when (annotation.color) {
                        "green" -> Color(0xFF36A779)
                        "blue" -> Color(0xFF4A91E8)
                        "pink" -> Color(0xFFE879A8)
                        else -> Color(0xFFE5B83F)
                    }
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { onOpen(book) },
                        shape = RoundedCornerShape(19.dp),
                        colors = CardDefaults.cardColors(containerColor = surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                    ) {
                        Column(Modifier.fillMaxWidth().padding(15.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(8.dp).clip(CircleShape).background(marker))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    book.title,
                                    modifier = Modifier.weight(1f),
                                    color = accent,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    if (book.type == "pdf") "Стр. ${annotation.position + 1}" else "Фрагмент ${annotation.position + 1}",
                                    color = muted,
                                    fontSize = 10.sp
                                )
                            }
                            Spacer(Modifier.height(9.dp))
                            Text(
                                annotation.text.ifBlank { "Выделенный фрагмент" },
                                color = ink,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis,
                                lineHeight = 20.sp
                            )
                            if (annotation.note.isNotBlank()) {
                                Spacer(Modifier.height(7.dp))
                                Text(annotation.note, color = muted, fontSize = 12.sp, lineHeight = 17.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}
