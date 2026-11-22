package ru.bookapk.reader

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkAdded
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.math.roundToInt

private val Teal = Color(0xFF087F78)
private val Warm = Color(0xFFF6F3EC)
private val Ink = Color(0xFF202522)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { BookApkApp() }
    }
}

private fun applyLauncherIcon(context: Context, style: String) {
    val aliases = mapOf(
        "classic" to ".ClassicLauncher",
        "clay" to ".ClayLauncher",
        "glass" to ".GlassLauncher"
    )
    val selected = aliases[style] ?: aliases.getValue("classic")
    aliases.values.forEach { alias ->
        context.packageManager.setComponentEnabledSetting(
            ComponentName(context, context.packageName + alias),
            if (alias == selected) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP
        )
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
    var appearance by remember { mutableStateOf(ReaderAppearance()) }
    var showSettings by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        books = withContext(Dispatchers.IO) { store.load() }
        categories = withContext(Dispatchers.IO) { store.loadCategories() }
        appearance = withContext(Dispatchers.IO) { store.loadAppearance() }
        applyLauncherIcon(context, appearance.iconStyle)
    }

    fun persistBooks(updated: List<Book>) {
        books = updated
        scope.launch(Dispatchers.IO) { store.save(updated) }
    }

    fun updateAppearance(updated: ReaderAppearance) {
        val iconChanged = updated.iconStyle != appearance.iconStyle
        appearance = updated
        scope.launch(Dispatchers.IO) { store.saveAppearance(updated) }
        if (iconChanged) applyLauncherIcon(context, updated.iconStyle)
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
                    persistBooks(books + imported)
                    activeBook = imported
                } catch (e: Exception) {
                    error = e.message ?: "Не удалось добавить книгу."
                } finally { busy = false }
            }
        }
    }

    val targetPalette = when (appearance.visualStyle) {
        "clay" -> lightColorScheme(primary = Color(0xFF426D68), onPrimary = Color.White, background = Color(0xFFE8E5DE), surface = Color(0xFFF0EEE8), onSurface = Color(0xFF303936))
        "glass" -> lightColorScheme(primary = Color(0xFF376F78), onPrimary = Color.White, background = Color(0xFFEAF1F2), surface = Color(0xDDFBFFFF), onSurface = Color(0xFF24383D))
        else -> lightColorScheme(primary = Teal, onPrimary = Color.White, background = Warm, surface = Color.White, onSurface = Ink)
    }
    // WHY: interpolate the shared palette so changing a theme never flashes the whole screen.
    val animatedPrimary by animateColorAsState(targetPalette.primary, tween(420), label = "theme-primary")
    val animatedBackground by animateColorAsState(targetPalette.background, tween(460), label = "theme-background")
    val animatedSurface by animateColorAsState(targetPalette.surface, tween(420), label = "theme-surface")
    val animatedOnSurface by animateColorAsState(targetPalette.onSurface, tween(420), label = "theme-text")
    val palette = targetPalette.copy(
        primary = animatedPrimary,
        background = animatedBackground,
        surface = animatedSurface,
        onSurface = animatedOnSurface
    )

    MaterialTheme(colorScheme = palette) {
        Surface(Modifier.fillMaxSize(), color = palette.background) {
            when {
                showSettings -> AppearanceScreen(
                    appearance = appearance,
                    onBack = { showSettings = false },
                    onChange = ::updateAppearance
                )
                activeBook == null -> LibraryDashboard(
                    books = books,
                    categories = categories,
                    onImport = { picker.launch(arrayOf("application/pdf", "text/plain")) },
                    onSettings = { showSettings = true },
                    visualStyle = appearance.visualStyle,
                    onOpen = { book ->
                        val openedAt = System.currentTimeMillis()
                        val updated = books.map { if (it.id == book.id) it.copy(lastOpenedAt = openedAt) else it }
                        persistBooks(updated)
                        activeBook = updated.firstOrNull { it.id == book.id }
                    },
                    onCategoryCreate = { category ->
                        categories = categories + category
                        scope.launch(Dispatchers.IO) { store.saveCategories(categories) }
                    },
                    onCategoryDelete = { category ->
                        categories = categories.filterNot { it.id == category.id }
                        persistBooks(books.map { if (it.categoryId == category.id) it.copy(categoryId = "") else it })
                        scope.launch(Dispatchers.IO) { store.saveCategories(categories) }
                    },
                    onBookCategoryChange = { book, categoryId ->
                        persistBooks(books.map { if (it.id == book.id) it.copy(categoryId = categoryId) else it })
                    },
                    onBookDelete = { book ->
                        persistBooks(books.filterNot { it.id == book.id })
                        scope.launch(Dispatchers.IO) { runCatching { File(book.path).delete() } }
                    },
                    onBookFavoriteChange = { book ->
                        // WHY: persist the flag with the full book record so favorites survive relaunches.
                        persistBooks(books.map { if (it.id == book.id) it.copy(isFavorite = !it.isFavorite) else it })
                    }
                )
                else -> ReaderScreen(
                    book = activeBook!!,
                    onBack = { activeBook = null },
                    onPosition = { position, progress, offsetPx ->
                        val updated = books.map {
                            if (it.id == activeBook!!.id) it.copy(readingPosition = position, readingOffsetPx = offsetPx, progress = progress, lastOpenedAt = System.currentTimeMillis()) else it
                        }
                        persistBooks(updated)
                        activeBook = updated.firstOrNull { it.id == activeBook!!.id }
                    },
                    visualStyle = appearance.visualStyle,
                    readerFontSize = appearance.readerFontSize,
                    readerLineSpacing = appearance.readerLineSpacing,
                    readerTheme = appearance.readerTheme,
                    onReaderAppearanceChange = { fontSize, lineSpacing, theme ->
                        updateAppearance(appearance.copy(
                            readerFontSize = fontSize,
                            readerLineSpacing = lineSpacing,
                            readerTheme = theme
                        ))
                    },
                    onBookmark = { position, offsetPx, title, note ->
                        val bookmark = ReaderBookmark(
                            id = UUID.randomUUID().toString(),
                            position = position,
                            title = title.trim().ifBlank { "Закладка" },
                            offsetPx = offsetPx,
                            note = note.trim()
                        )
                        val updated = books.map { current ->
                            if (current.id == activeBook!!.id) current.copy(
                                bookmarkPosition = position,
                                bookmarks = current.bookmarks + bookmark
                            ) else current
                        }
                        persistBooks(updated)
                        activeBook = updated.firstOrNull { it.id == activeBook!!.id }
                    },
                    onUpdateBookmark = { edited ->
                        val updated = books.map { current ->
                            if (current.id == activeBook!!.id) current.copy(
                                bookmarks = current.bookmarks.map { if (it.id == edited.id) edited else it }
                            ) else current
                        }
                        persistBooks(updated)
                        activeBook = updated.firstOrNull { it.id == activeBook!!.id }
                    },
                    onRemoveBookmark = { bookmarkId ->
                        val updated = books.map { current ->
                            if (current.id == activeBook!!.id) {
                                val remaining = current.bookmarks.filterNot { it.id == bookmarkId }
                                current.copy(bookmarks = remaining, bookmarkPosition = remaining.lastOrNull()?.position ?: -1)
                            } else current
                        }
                        persistBooks(updated)
                        activeBook = updated.firstOrNull { it.id == activeBook!!.id }
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
private fun AppearanceScreen(
    appearance: ReaderAppearance,
    onBack: () -> Unit,
    onChange: (ReaderAppearance) -> Unit
) {
    val panel = MaterialTheme.colorScheme.surface
    val styles = listOf(
        Triple("classic", "Обычный", "Чистые поверхности и спокойная типографика"),
        Triple("clay", "Клэйморфизм", "Мягкий рельеф и тактильные поверхности"),
        Triple("glass", "Глэсморфизм", "Полупрозрачные панели и лёгкие слои")
    )
    val icons = listOf(
        Triple("classic", "Книжная", "Лаконичная открытая книга"),
        Triple("clay", "Объёмная", "Мягкий рельеф"),
        Triple("glass", "Стеклянная", "Лёгкий полупрозрачный знак")
    )
    Column(
        Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 22.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp, bottom = 24.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, contentDescription = "Назад") }
            Column {
                Text("Оформление", fontSize = 23.sp, fontWeight = FontWeight.Bold)
                Text("Настройте внешний вид читалки", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .58f))
            }
        }

        Text("ПРЕДПРОСМОТР", fontSize = 10.sp, letterSpacing = 1.5.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        ThemeLivePreview(visualStyle = appearance.visualStyle)
        Spacer(Modifier.height(25.dp))

        Text("ВИЗУАЛЬНЫЙ СТИЛЬ", fontSize = 10.sp, letterSpacing = 1.5.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        Text("Выберите оформление всего приложения", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .58f), modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
        styles.forEach { (id, title, description) ->
            val selected = appearance.visualStyle == id
            val container by animateColorAsState(
                targetValue = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = if (id == "glass") .13f else .09f) else panel,
                animationSpec = tween(300), label = "style-card-$id"
            )
            val elevation by animateDpAsState(
                if (selected && id == "clay") 9.dp else if (selected) 3.dp else 0.dp,
                tween(360), label = "style-elevation-$id"
            )
            val radius by animateDpAsState(if (selected) 24.dp else 17.dp, tween(360), label = "style-radius-$id")
            val scale by animateFloatAsState(if (selected) 1f else .985f, tween(360), label = "style-scale-$id")
            val outlineAlpha by animateFloatAsState(if (selected) 1f else 0f, tween(260), label = "style-outline-$id")
            Card(
                onClick = { onChange(appearance.copy(visualStyle = id)) },
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
                    .graphicsLayer { scaleX = scale; scaleY = scale }
                    .then(
                        if (id == "glass") Modifier.border(
                            width = if (selected) 1.5.dp else 1.dp,
                            color = Color.White.copy(alpha = if (selected) .95f else .58f),
                            shape = RoundedCornerShape(radius)
                        ) else Modifier
                    ),
                shape = RoundedCornerShape(radius),
                colors = CardDefaults.cardColors(containerColor = container),
                elevation = CardDefaults.cardElevation(defaultElevation = elevation),
                border = if (selected && id != "glass") androidx.compose.foundation.BorderStroke(
                    1.dp, MaterialTheme.colorScheme.primary.copy(alpha = .28f * outlineAlpha)
                ) else null
            ) {
                Row(Modifier.padding(horizontal = 15.dp, vertical = if (selected) 17.dp else 15.dp), verticalAlignment = Alignment.CenterVertically) {
                    ThemeSwatch(style = id, selected = selected)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(title, fontWeight = FontWeight.SemiBold)
                        Text(description, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .62f))
                    }
                    AnimatedContent(targetState = selected, transitionSpec = {
                        fadeIn(tween(180)) togetherWith fadeOut(tween(120))
                    }, label = "selected-label") { isSelected ->
                        if (isSelected) Text("Выбрано", color = MaterialTheme.colorScheme.primary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        else RadioButton(selected = false, onClick = { onChange(appearance.copy(visualStyle = id)) })
                    }
                }
            }
        }

        Spacer(Modifier.height(18.dp))
        Text("СТИЛЬ ИКОНКИ ПРИЛОЖЕНИЯ", fontSize = 10.sp, letterSpacing = 1.5.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        Text("Меняется значок приложения на главном экране", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .58f), modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
        icons.forEach { (id, title, description) ->
            val selected = appearance.iconStyle == id
            val container by animateColorAsState(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = .09f) else panel,
                tween(260), label = "icon-card-$id"
            )
            Card(
                onClick = { onChange(appearance.copy(iconStyle = id)) },
                modifier = Modifier.fillMaxWidth().padding(bottom = 9.dp),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = container),
                border = if (selected) androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = .28f)) else null
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    LauncherIconPreview(style = id)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(title, fontWeight = FontWeight.SemiBold)
                        Text(description, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .62f))
                    }
                    RadioButton(selected = selected, onClick = { onChange(appearance.copy(iconStyle = id)) })
                }
            }
        }
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun ThemeLivePreview(visualStyle: String) {
    val clay = visualStyle == "clay"
    val glass = visualStyle == "glass"
    val container by animateColorAsState(
        when (visualStyle) {
            "clay" -> Color(0xFFECE9E1)
            "glass" -> Color(0xCFFFFFFF)
            else -> Color.White
        }, tween(420), label = "preview-surface"
    )
    val accent by animateColorAsState(
        when (visualStyle) {
            "clay" -> Color(0xFF426D68)
            "glass" -> Color(0xFF376F78)
            else -> Teal
        }, tween(420), label = "preview-accent"
    )
    val previewShape by animateDpAsState(
        when (visualStyle) { "clay" -> 27.dp; "glass" -> 23.dp; else -> 17.dp },
        tween(420), label = "preview-shape"
    )
    val previewElevation by animateDpAsState(
        when (visualStyle) { "clay" -> 10.dp; "glass" -> 1.dp; else -> 0.dp },
        tween(420), label = "preview-elevation"
    )
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(previewShape),
        colors = CardDefaults.cardColors(containerColor = container),
        elevation = CardDefaults.cardElevation(previewElevation),
        border = when {
            glass -> androidx.compose.foundation.BorderStroke(1.5.dp, Color.White.copy(alpha = .96f))
            clay -> androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = .55f))
            else -> null
        }
    ) {
        Column(Modifier.padding(if (clay) 19.dp else 17.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(38.dp).clip(RoundedCornerShape(if (clay) 14.dp else 11.dp))
                        .background(if (clay) Color(0xFFE1DED5) else accent.copy(alpha = if (glass) .10f else .13f))
                        .then(if (glass) Modifier.border(1.dp, Color.White, RoundedCornerShape(11.dp)) else Modifier),
                    contentAlignment = Alignment.Center
                ) {
                    Text("М", color = accent, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Моя библиотека", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text("Ваша следующая глава", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .55f))
                }
                Text("•••", color = accent, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(15.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(43.dp, 56.dp).clip(RoundedCornerShape(if (clay) 11.dp else 7.dp))
                        .background(
                            when (visualStyle) {
                                "clay" -> Color(0xFFDAD6CC)
                                "glass" -> Color(0xBFFFFFFF)
                                else -> accent.copy(alpha = .14f)
                            }
                        )
                        .then(if (glass) Modifier.border(1.dp, Color.White, RoundedCornerShape(7.dp)) else Modifier),
                    contentAlignment = Alignment.Center
                ) {
                    Text("PDF", color = accent, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Название книги", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Text("Продолжить чтение · 68%", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .58f))
                    Spacer(Modifier.height(7.dp))
                    LinearProgressIndicator(
                        progress = { .68f },
                        modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(8.dp)),
                        color = accent,
                        trackColor = accent.copy(alpha = .13f)
                    )
                }
            }
        }
    }
}

@Composable
private fun ThemeSwatch(style: String, selected: Boolean) {
    val base = when (style) {
        "clay" -> Color(0xFFD8D4CA)
        "glass" -> Color(0xFFA9C8CD)
        else -> Color(0xFFDDEFE9)
    }
    val accent = when (style) {
        "clay" -> Color(0xFF52615D)
        "glass" -> Color(0xFF315E66)
        else -> Teal
    }
    val shape by animateDpAsState(if (selected) 18.dp else 13.dp, tween(280), label = "swatch-shape-$style")
    Box(
        Modifier.size(50.dp).clip(RoundedCornerShape(shape))
            .background(if (style == "glass") base.copy(alpha = .62f) else base)
            .then(
                when (style) {
                    "glass" -> Modifier.border(1.5.dp, Color.White.copy(alpha = .95f), RoundedCornerShape(shape))
                    "clay" -> Modifier.border(1.dp, Color.White.copy(alpha = .72f), RoundedCornerShape(shape))
                    else -> Modifier
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        Text("Aa", fontWeight = FontWeight.Bold, color = accent, fontSize = 17.sp)
    }
}

@Composable
private fun LauncherIconPreview(style: String) {
    val bg = when (style) { "clay" -> Color(0xFFD8D4CA); "glass" -> Color(0xFFB9D4D8); else -> Teal }
    Box(Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(bg), contentAlignment = Alignment.Center) {
        Text(
            when (style) { "clay" -> "▤"; "glass" -> "▱"; else -> "▥" },
            color = if (style == "classic") Color.White else Color(0xFF315E66),
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderScreen(
    book: Book,
    onBack: () -> Unit,
    onPosition: (Int, Int, Int) -> Unit,
    onBookmark: (Int, Int, String, String) -> Unit,
    onUpdateBookmark: (ReaderBookmark) -> Unit,
    onRemoveBookmark: (String) -> Unit,
    visualStyle: String,
    readerFontSize: Float,
    readerLineSpacing: Float,
    readerTheme: String,
    onReaderAppearanceChange: (Float, Float, String) -> Unit,
    onError: (String) -> Unit
) {
    val context = LocalContext.current
    var txtDocument by remember(book.id) { mutableStateOf(TxtDocument(emptyList(), emptyList())) }
    var fontSize by remember(book.id) { mutableFloatStateOf(readerFontSize) }
    var lineSpacing by remember(book.id) { mutableFloatStateOf(readerLineSpacing) }
    var activeReaderTheme by remember(book.id) { mutableStateOf(readerTheme) }
    var showReaderSettings by remember { mutableStateOf(false) }
    var showBookmarks by remember { mutableStateOf(false) }
    var showContents by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var showBookmarkDialog by remember { mutableStateOf(false) }
    var bookmarkTitle by remember { mutableStateOf("") }
    var bookmarkNote by remember { mutableStateOf("") }
    var editingBookmarkId by remember { mutableStateOf<String?>(null) }
    var pageCount by remember { mutableIntStateOf(0) }
    var page by remember(book.id) { mutableIntStateOf(book.readingPosition.coerceAtLeast(0)) }
    var loading by remember { mutableStateOf(true) }
    var jump by remember { mutableStateOf("") }
    var scale by remember(book.id) { mutableFloatStateOf(1f) }
    var scrubPage by remember(book.id) { mutableFloatStateOf(book.readingPosition.coerceAtLeast(0).toFloat()) }
    var dragTotal by remember { mutableFloatStateOf(0f) }
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = book.readingPosition.coerceAtLeast(0),
        initialFirstVisibleItemScrollOffset = book.readingOffsetPx.coerceAtLeast(0)
    )
    val readerScope = rememberCoroutineScope()

    LaunchedEffect(book.id) {
        loading = true
        try {
            if (book.type == "txt") {
                txtDocument = withContext(Dispatchers.IO) { TxtBookParser.parse(File(book.path).readText(Charsets.UTF_8)) }
            } else {
                pageCount = withContext(Dispatchers.IO) {
                    val fd = android.os.ParcelFileDescriptor.open(File(book.path), android.os.ParcelFileDescriptor.MODE_READ_ONLY)
                    try { PdfRenderer(fd).use { it.pageCount } } finally { fd.close() }
                }
                page = book.readingPosition.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
            }
        } catch (e: Exception) { onError(e.message ?: "Файл повреждён или недоступен."); onBack() }
        finally { loading = false }
    }

    LaunchedEffect(book.id, txtDocument.paragraphs.size) {
        if (book.type == "txt" && txtDocument.paragraphs.isNotEmpty()) {
            listState.scrollToItem(
                book.readingPosition.coerceIn(0, txtDocument.paragraphs.lastIndex),
                book.readingOffsetPx.coerceAtLeast(0)
            )
        }
    }

    val paragraphs = txtDocument.paragraphs
    val chapterStarts = remember(txtDocument) { txtDocument.chapters.associateBy { it.startParagraph } }
    val currentTextPosition by remember { derivedStateOf { listState.firstVisibleItemIndex } }
    val currentChapterIndex = remember(currentTextPosition, txtDocument.chapters) {
        txtDocument.chapters.indexOfLast { it.startParagraph <= currentTextPosition }.coerceAtLeast(0)
    }
    val currentChapter = txtDocument.chapters.getOrNull(currentChapterIndex)
    val savedPosition = if (book.type == "pdf") page else currentTextPosition
    val savedOffsetPx = if (book.type == "pdf") 0 else listState.firstVisibleItemScrollOffset
    val readerBackground = when (activeReaderTheme) {
        "sepia" -> Color(0xFFF1E5CE)
        "night" -> Color(0xFF171B1C)
        else -> Color(0xFFFAFAF7)
    }
    val readerForeground = when (activeReaderTheme) {
        "sepia" -> Color(0xFF493E30)
        "night" -> Color(0xFFE6E9E7)
        else -> Color(0xFF262B29)
    }

    Column(Modifier.fillMaxSize().background(if (book.type == "txt") readerBackground else MaterialTheme.colorScheme.background)) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, contentDescription = "Назад") }
            Column(Modifier.weight(1f)) {
                Text(book.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (book.type == "pdf") "Страница ${page + 1} из $pageCount"
                    else if (paragraphs.isEmpty()) "Загрузка текста…"
                    else "Глава ${currentChapterIndex + 1} из ${txtDocument.chapters.size} · ${currentChapter?.title ?: "Начало"}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = .58f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (book.type == "txt") TextButton(onClick = { showContents = true }) { Text("Оглавление", fontSize = 12.sp) }
            IconButton(onClick = { showBookmarks = true }) {
                Icon(Icons.Rounded.Bookmarks, contentDescription = "Все закладки", tint = MaterialTheme.colorScheme.primary)
            }
            IconButton(onClick = {
                editingBookmarkId = null
                bookmarkTitle = if (book.type == "pdf") "Страница ${savedPosition + 1}" else "Фрагмент ${savedPosition + 1}"
                bookmarkNote = ""
                showBookmarkDialog = true
            }) {
                Icon(Icons.Rounded.BookmarkBorder, contentDescription = "Добавить закладку", tint = MaterialTheme.colorScheme.primary)
            }
            IconButton(onClick = { showReaderSettings = true }) {
                Icon(Icons.Rounded.Settings, contentDescription = "Настройки чтения")
            }
        }

        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else if (book.type == "txt") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Чтение", fontSize = 12.sp, color = readerForeground.copy(alpha = .68f))
                Text("${fontSize.toInt()} pt", fontSize = 12.sp, color = readerForeground)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { showContents = true }) { Text("Главы") }
                TextButton(onClick = { searchQuery = ""; showSearch = true }) { Text("Поиск") }
                TextButton(onClick = { showReaderSettings = true }) { Text("Настроить") }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(start = 26.dp, end = 26.dp, top = 24.dp, bottom = 70.dp)
            ) {
                items(paragraphs.size) { index ->
                    val chapter = chapterStarts[index]
                    Text(
                        paragraphs[index],
                        fontSize = if (chapter != null) (fontSize + 4).sp else fontSize.sp,
                        lineHeight = (fontSize * lineSpacing).sp,
                        fontWeight = if (chapter != null) FontWeight.Bold else FontWeight.Normal,
                        color = readerForeground,
                        modifier = Modifier.padding(top = if (chapter != null) 22.dp else 0.dp, bottom = 19.dp)
                    )
                }
            }
            LaunchedEffect(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset, paragraphs.size) {
                if (paragraphs.isNotEmpty()) {
                    // WHY: debounce persistence so a drag writes one stable reading position, not every scroll frame.
                    delay(350)
                    val position = listState.firstVisibleItemIndex
                    onPosition(
                        position,
                        if (paragraphs.size <= 1) 0 else position * 100 / (paragraphs.size - 1),
                        listState.firstVisibleItemScrollOffset
                    )
                }
            }
        } else {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "PDF",
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    letterSpacing = 1.sp
                )
                Spacer(Modifier.weight(1f))
                Text(
                    if (pageCount > 0) "${page + 1} / $pageCount" else "Загрузка страниц",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = .62f)
                )
                Spacer(Modifier.width(10.dp))
                TextButton(
                    onClick = { scale = 1f },
                    enabled = scale > 1.01f
                ) { Text("Сбросить масштаб", fontSize = 11.sp) }
            }
            Box(
                Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(readerBackground)
                    .pointerInput(page, pageCount) {
                        detectHorizontalDragGestures(
                            onHorizontalDrag = { _, amount -> dragTotal += amount },
                            onDragEnd = {
                                if (dragTotal < -45f) {
                                    page = (page + 1).coerceAtMost((pageCount - 1).coerceAtLeast(0))
                                    scale = 1f
                                }
                                if (dragTotal > 45f) {
                                    page = (page - 1).coerceAtLeast(0)
                                    scale = 1f
                                }
                                dragTotal = 0f
                            },
                            onDragCancel = { dragTotal = 0f }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                PdfPage(
                    book.path,
                    page,
                    Modifier.fillMaxSize().graphicsLayer(scaleX = scale, scaleY = scale).transformable(
                        rememberTransformableState { zoomChange, _, _ ->
                            scale = (scale * zoomChange).coerceIn(1f, 4f)
                        }
                    )
                )
                if (pageCount > 1) {
                    Row(
                        Modifier.align(Alignment.Center).fillMaxWidth().padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilledTonalIconButton(
                            onClick = { page = (page - 1).coerceAtLeast(0); scale = 1f },
                            enabled = page > 0
                        ) { Icon(Icons.Rounded.ChevronLeft, contentDescription = "Предыдущая страница") }
                        FilledTonalIconButton(
                            onClick = { page = (page + 1).coerceAtMost(pageCount - 1); scale = 1f },
                            enabled = page < pageCount - 1
                        ) { Icon(Icons.Rounded.ChevronRight, contentDescription = "Следующая страница") }
                    }
                }
            }
            LaunchedEffect(page, pageCount) {
                if (pageCount > 0) {
                    scrubPage = page.toFloat()
                    onPosition(page, if (pageCount <= 1) 0 else page * 100 / (pageCount - 1), 0)
                }
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = { page = 0; scale = 1f },
                        enabled = page > 0
                    ) { Text("В начало", fontSize = 11.sp) }
                    Spacer(Modifier.weight(1f))
                    Text(
                        "Страница ${page + 1} из $pageCount",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        onClick = { page = (pageCount - 1).coerceAtLeast(0); scale = 1f },
                        enabled = page < pageCount - 1
                    ) { Text("В конец", fontSize = 11.sp) }
                }
                Slider(
                    value = scrubPage.coerceIn(0f, (pageCount - 1).coerceAtLeast(0).toFloat()),
                    onValueChange = { scrubPage = it },
                    onValueChangeFinished = {
                        page = scrubPage.roundToInt().coerceIn(0, (pageCount - 1).coerceAtLeast(0))
                        scale = 1f
                    },
                    valueRange = 0f..(pageCount - 1).coerceAtLeast(1).toFloat(),
                    enabled = pageCount > 1
                )
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        "Свайп для перелистывания · щипок для увеличения",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = .55f)
                    )
                }
            }
        }
    }

    if (showBookmarkDialog) {
        AlertDialog(
            onDismissRequest = {
                editingBookmarkId = null
                showBookmarkDialog = false
            },
            title = { Text(if (editingBookmarkId == null) "Новая закладка" else "Редактировать закладку") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = bookmarkTitle,
                        onValueChange = { bookmarkTitle = it },
                        label = { Text("Название") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = bookmarkNote,
                        onValueChange = { bookmarkNote = it },
                        label = { Text("Заметка (необязательно)") },
                        minLines = 2,
                        maxLines = 4
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = bookmarkTitle.isNotBlank(),
                    onClick = {
                        val editId = editingBookmarkId
                        if (editId == null) {
                            onBookmark(savedPosition, savedOffsetPx, bookmarkTitle, bookmarkNote)
                        } else {
                            val original = book.bookmarks.firstOrNull { it.id == editId }
                            if (original != null) onUpdateBookmark(original.copy(
                                title = bookmarkTitle.trim(),
                                note = bookmarkNote.trim()
                            ))
                        }
                        editingBookmarkId = null
                        showBookmarkDialog = false
                    }
                ) { Text("Сохранить") }
            },
            dismissButton = {
                TextButton(onClick = {
                    editingBookmarkId = null
                    showBookmarkDialog = false
                }) { Text("Отмена") }
            }
        )
    }

    if (showSearch) {
        val normalizedQuery = searchQuery.trim()
        val searchResults = remember(normalizedQuery, paragraphs) {
            if (normalizedQuery.length < 2) emptyList()
            else paragraphs.mapIndexedNotNull { index, text ->
                if (text.contains(normalizedQuery, ignoreCase = true)) index to text else null
            }.take(200)
        }
        AlertDialog(
            onDismissRequest = { showSearch = false },
            title = { Text("Поиск по книге") },
            text = {
                Column {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Слово или фраза") },
                        singleLine = true
                    )
                    Spacer(Modifier.height(10.dp))
                    when {
                        normalizedQuery.isEmpty() -> Text("Введите текст для поиска.", color = MaterialTheme.colorScheme.onSurface.copy(alpha = .62f))
                        normalizedQuery.length < 2 -> Text("Введите хотя бы два символа.", color = MaterialTheme.colorScheme.onSurface.copy(alpha = .62f))
                        searchResults.isEmpty() -> Text("Совпадений не найдено.", color = MaterialTheme.colorScheme.onSurface.copy(alpha = .62f))
                        else -> {
                            Text("Найдено: ${searchResults.size}${if (searchResults.size == 200) "+" else ""}", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                            LazyColumn(Modifier.heightIn(max = 340.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                items(searchResults) { (index, paragraph) ->
                                    TextButton(
                                        onClick = {
                                            readerScope.launch { listState.animateScrollToItem(index) }
                                            showSearch = false
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(Modifier.fillMaxWidth()) {
                                            Text("Фрагмент ${index + 1}", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                                            Text(paragraph.replace('\n', ' '), maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showSearch = false }) { Text("Закрыть") } }
        )
    }

    if (showContents) {
        AlertDialog(
            onDismissRequest = { showContents = false },
            title = { Text("Оглавление") },
            text = {
                LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(txtDocument.chapters) { chapter ->
                        TextButton(
                            onClick = {
                                readerScope.launch { listState.animateScrollToItem(chapter.startParagraph.coerceIn(0, (paragraphs.size - 1).coerceAtLeast(0))) }
                                showContents = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(chapter.title, modifier = Modifier.fillMaxWidth(), maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showContents = false }) { Text("Закрыть") } }
        )
    }

    if (showBookmarks) {
        AlertDialog(
            onDismissRequest = { showBookmarks = false },
            title = { Text("Закладки · ${book.bookmarks.size}") },
            text = {
                if (book.bookmarks.isEmpty()) {
                    Text("Здесь появятся сохранённые места. Добавьте закладку кнопкой с иконкой книги.")
                } else {
                    LazyColumn(Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(book.bookmarks.sortedByDescending { it.createdAt }, key = { it.id }) { bookmark ->
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(
                                    Modifier.weight(1f).clickable {
                                        if (book.type == "pdf") {
                                            page = bookmark.position.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
                                            scale = 1f
                                        } else {
                                            readerScope.launch {
                                                listState.animateScrollToItem(
                                                    bookmark.position.coerceIn(0, (paragraphs.size - 1).coerceAtLeast(0)),
                                                    bookmark.offsetPx.coerceAtLeast(0)
                                                )
                                            }
                                        }
                                        showBookmarks = false
                                    }.padding(vertical = 9.dp)
                                ) {
                                    Text(bookmark.title, fontWeight = FontWeight.SemiBold)
                                    if (bookmark.note.isNotBlank()) {
                                        Text(
                                            bookmark.note,
                                            fontSize = 12.sp,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = .72f)
                                        )
                                    }
                                    Text(
                                        if (book.type == "pdf") "Страница ${bookmark.position + 1}" else "Фрагмент ${bookmark.position + 1}",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = .58f)
                                    )
                                }
                                IconButton(onClick = {
                                    editingBookmarkId = bookmark.id
                                    bookmarkTitle = bookmark.title
                                    bookmarkNote = bookmark.note
                                    showBookmarkDialog = true
                                }) {
                                    Icon(Icons.Rounded.Edit, contentDescription = "Редактировать закладку")
                                }
                                IconButton(onClick = { onRemoveBookmark(bookmark.id) }) {
                                    Icon(Icons.Rounded.DeleteOutline, contentDescription = "Удалить закладку", tint = MaterialTheme.colorScheme.error)
                                }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showBookmarks = false }) { Text("Готово") }
            }
        )
    }

    if (showReaderSettings) {
        ModalBottomSheet(onDismissRequest = { showReaderSettings = false }) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 28.dp)) {
                Text("Настройки чтения", fontSize = 21.sp, fontWeight = FontWeight.Bold)
                Text("Комфортный размер и ритм текста", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .62f), modifier = Modifier.padding(top = 4.dp))
                Spacer(Modifier.height(22.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Размер шрифта", modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    Text("${fontSize.toInt()} pt", color = MaterialTheme.colorScheme.primary)
                }
                Slider(
                    value = fontSize,
                    onValueChange = { fontSize = it },
                    onValueChangeFinished = { onReaderAppearanceChange(fontSize, lineSpacing, activeReaderTheme) },
                    valueRange = 14f..30f,
                    steps = 15
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Межстрочный интервал", modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    Text(String.format("%.2f", lineSpacing), color = MaterialTheme.colorScheme.primary)
                }
                Slider(
                    value = lineSpacing,
                    onValueChange = { lineSpacing = it },
                    onValueChangeFinished = { onReaderAppearanceChange(fontSize, lineSpacing, activeReaderTheme) },
                    valueRange = 1.3f..2.2f,
                    steps = 8
                )
                Spacer(Modifier.height(12.dp))
                Text("Цвет страницы", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
                    listOf("paper" to "Бумага", "sepia" to "Сепия", "night" to "Ночной").forEach { (id, label) ->
                        FilterChip(
                            selected = activeReaderTheme == id,
                            onClick = {
                                activeReaderTheme = id
                                onReaderAppearanceChange(fontSize, lineSpacing, id)
                            },
                            label = { Text(label) }
                        )
                    }
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
                try {
                    PdfRenderer(fd).use { renderer ->
                        renderer.openPage(index).use { page ->
                            val width = 1300
                            val height = (width.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
                            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { image ->
                                page.render(image, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            }
                        }
                    }
                } finally { fd.close() }
            }.getOrNull()
        }
    }
    if (bitmap != null) {
        Image(bitmap!!.asImageBitmap(), contentDescription = "Страница ${index + 1}", modifier = modifier.fillMaxWidth(), alignment = Alignment.Center)
    } else {
        Box(modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    }
}
