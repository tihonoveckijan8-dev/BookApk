package ru.bookapk.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LibraryStatsScreen(
    books: List<Book>,
    categories: List<BookCategory>,
    onBack: () -> Unit,
    onOpen: (Book) -> Unit
) {
    val total = books.size
    val completed = books.count { it.progress >= 100 }
    val inProgress = books.count { it.progress in 1..99 }
    val notStarted = books.count { it.progress <= 0 }
    val favorites = books.count { it.isFavorite }
    val bookmarks = books.sumOf { it.bookmarks.size }
    val notes = books.sumOf { it.annotations.size }
    val reviewNow = System.currentTimeMillis()
    val dueCards = books.sumOf { book ->
        book.annotations.count { it.lastReviewedAt == 0L || it.nextReviewAt <= reviewNow }
    }
    val averageProgress = if (books.isEmpty()) 0 else
        books.map { it.progress.coerceIn(0, 100) }.average().toInt()
    val recentBooks = books.filter { it.lastOpenedAt > 0L }
        .sortedByDescending { it.lastOpenedAt }
        .take(5)
    val dateFormat = remember {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.getDefault())
    }
    val cardColor = if (MaterialTheme.colorScheme.background == Color(0xFFE8E5DE)) {
        Color(0xFFF0EEE8)
    } else MaterialTheme.colorScheme.surface
    val ink = MaterialTheme.colorScheme.onSurface
    val muted = ink.copy(alpha = .62f)
    val accent = MaterialTheme.colorScheme.primary
    val categoryStats = categories.map { category ->
        category.name to books.count { it.categoryId == category.id }
    }.filter { it.second > 0 }.sortedByDescending { it.second }
    val unassignedCount = books.count { book ->
        book.categoryId.isBlank() || categories.none { it.id == book.categoryId }
    }
    val allCategoryStats = categoryStats + if (unassignedCount > 0) listOf("Без категории" to unassignedCount) else emptyList()
    val maxCategoryCount = allCategoryStats.maxOfOrNull { it.second } ?: 1

    Column(
        Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 22.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Rounded.ArrowBack, contentDescription = "Назад")
            }
            Column(Modifier.weight(1f)) {
                Text("Статистика", fontSize = 25.sp, fontWeight = FontWeight.Bold, color = ink)
                Text("Обзор вашей библиотеки", fontSize = 12.sp, color = muted)
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(25.dp),
            colors = CardDefaults.cardColors(containerColor = accent.copy(alpha = .10f)),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Column(Modifier.padding(20.dp)) {
                Text("СРЕДНИЙ ПРОГРЕСС", fontSize = 10.sp, letterSpacing = 1.4.sp, fontWeight = FontWeight.Bold, color = accent)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("$averageProgress", fontSize = 42.sp, lineHeight = 46.sp, fontWeight = FontWeight.Bold, color = ink)
                    Text("%", modifier = Modifier.padding(start = 3.dp, bottom = 6.dp), color = muted, fontSize = 18.sp)
                    Spacer(Modifier.weight(1f))
                    Text("$total книг", modifier = Modifier.padding(bottom = 8.dp), color = muted, fontSize = 13.sp)
                }
                LinearProgressIndicator(
                    progress = { averageProgress / 100f },
                    modifier = Modifier.fillMaxWidth().height(7.dp),
                    color = accent,
                    trackColor = accent.copy(alpha = .13f)
                )
                Text(
                    "Среднее значение прогресса по всем книгам, без учёта их объёма.",
                    modifier = Modifier.padding(top = 9.dp),
                    color = muted,
                    fontSize = 11.sp
                )
            }
        }

        Spacer(Modifier.height(18.dp))
        Text("КНИГИ", fontSize = 10.sp, letterSpacing = 1.3.sp, fontWeight = FontWeight.Bold, color = accent)
        Spacer(Modifier.height(9.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatsMetricCard("Читаю", inProgress.toString(), "Начаты, но ещё не закончены", cardColor, accent, Modifier.weight(1f))
            StatsMetricCard("Прочитано", completed.toString(), "Прогресс 100%", cardColor, Color(0xFF32856C), Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatsMetricCard("Не начаты", notStarted.toString(), "Можно выбрать следующую", cardColor, muted, Modifier.weight(1f))
            StatsMetricCard("Избранное", favorites.toString(), "Отмечены сердечком", cardColor, Color(0xFFC65D62), Modifier.weight(1f))
        }

        Spacer(Modifier.height(22.dp))
        Text("ЗАКЛАДКИ И ЗАМЕТКИ", fontSize = 10.sp, letterSpacing = 1.3.sp, fontWeight = FontWeight.Bold, color = accent)
        Spacer(Modifier.height(9.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatsMetricCard("Закладки", bookmarks.toString(), "Сохранённые места", cardColor, accent, Modifier.weight(1f))
            StatsMetricCard("Заметки", notes.toString(), "Выделения и комментарии", cardColor, accent, Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = cardColor)
        ) {
            Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Schedule, contentDescription = null, tint = accent)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Карточки к повторению", fontWeight = FontWeight.SemiBold, color = ink)
                    Text("Новые и уже ожидающие повторения", color = muted, fontSize = 11.sp)
                }
                Text(dueCards.toString(), color = accent, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(Modifier.height(22.dp))
        Text("КАТЕГОРИИ", fontSize = 10.sp, letterSpacing = 1.3.sp, fontWeight = FontWeight.Bold, color = accent)
        Spacer(Modifier.height(9.dp))
        if (allCategoryStats.isEmpty()) {
            Text("Категории появятся здесь, когда вы добавите книги.", color = muted, fontSize = 13.sp)
        } else {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = cardColor)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    allCategoryStats.forEach { (name, count) ->
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(name, modifier = Modifier.weight(1f), color = ink, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("$count", color = muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                            Spacer(Modifier.height(6.dp))
                            LinearProgressIndicator(
                                progress = { count.toFloat() / maxCategoryCount.coerceAtLeast(1) },
                                modifier = Modifier.fillMaxWidth().height(5.dp),
                                color = accent,
                                trackColor = accent.copy(alpha = .12f)
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(22.dp))
        Text("НЕДАВНО ОТКРЫВАЛИ", fontSize = 10.sp, letterSpacing = 1.3.sp, fontWeight = FontWeight.Bold, color = accent)
        Spacer(Modifier.height(9.dp))
        if (recentBooks.isEmpty()) {
            Text("Здесь появятся книги после первого открытия.", color = muted, fontSize = 13.sp)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                recentBooks.forEach { book ->
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { onOpen(book) },
                        shape = RoundedCornerShape(17.dp),
                        colors = CardDefaults.cardColors(containerColor = cardColor)
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Rounded.AutoStories, contentDescription = null, tint = accent)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(book.title, color = ink, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(dateFormat.format(Date(book.lastOpenedAt)), color = muted, fontSize = 11.sp)
                            }
                            Text(${book.progress.coerceIn(0, 100)}%, color = accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(30.dp))
    }
}

@Composable
private fun StatsMetricCard(
    title: String,
    value: String,
    detail: String,
    container: Color,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = container),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Text(title, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .65f), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(5.dp))
            Text(value, color = accent, fontSize = 27.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(3.dp))
            Text(detail, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .55f), fontSize = 10.sp, lineHeight = 13.sp)
        }
    }
}
