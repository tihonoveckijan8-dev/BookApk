package ru.bookapk.reader

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import org.json.JSONArray

private val Context.dataStore by preferencesDataStore(name = "book_library")
private val LIBRARY_KEY = stringPreferencesKey("books")
private val CATEGORIES_KEY = stringPreferencesKey("categories")
private val VISUAL_STYLE_KEY = stringPreferencesKey("visual_style")
private val ICON_STYLE_KEY = stringPreferencesKey("icon_style")
private val READER_FONT_SIZE_KEY = floatPreferencesKey("reader_font_size")
private val READER_LINE_SPACING_KEY = floatPreferencesKey("reader_line_spacing")
private val READER_THEME_KEY = stringPreferencesKey("reader_theme")

class LibraryStore(private val context: Context) {
    suspend fun load(): List<Book> {
        val raw = context.dataStore.data.first()[LIBRARY_KEY] ?: "[]"
        val array = JSONArray(raw)
        return (0 until array.length()).mapNotNull { index ->
            runCatching { Book.fromJson(array.getJSONObject(index)) }.getOrNull()
        }.filter { java.io.File(it.path).exists() }
    }

    suspend fun save(books: List<Book>) {
        val array = JSONArray()
        books.forEach { array.put(it.toJson()) }
        context.dataStore.edit { it[LIBRARY_KEY] = array.toString() }
    }

    suspend fun loadCategories(): List<BookCategory> {
        val raw = context.dataStore.data.first()[CATEGORIES_KEY] ?: "[]"
        val array = JSONArray(raw)
        return (0 until array.length()).mapNotNull { index ->
            runCatching { BookCategory.fromJson(array.getJSONObject(index)) }.getOrNull()
        }
    }

    suspend fun saveCategories(categories: List<BookCategory>) {
        val array = JSONArray()
        categories.forEach { array.put(it.toJson()) }
        context.dataStore.edit { it[CATEGORIES_KEY] = array.toString() }
    }

    suspend fun loadAppearance(): ReaderAppearance {
        val prefs = context.dataStore.data.first()
        return ReaderAppearance(
            visualStyle = prefs[VISUAL_STYLE_KEY] ?: "classic",
            iconStyle = prefs[ICON_STYLE_KEY] ?: "classic",
            readerFontSize = prefs[READER_FONT_SIZE_KEY] ?: 18f,
            readerLineSpacing = prefs[READER_LINE_SPACING_KEY] ?: 1.65f,
            readerTheme = prefs[READER_THEME_KEY] ?: "paper"
        )
    }

    suspend fun saveAppearance(appearance: ReaderAppearance) {
        context.dataStore.edit {
            it[VISUAL_STYLE_KEY] = appearance.visualStyle
            it[ICON_STYLE_KEY] = appearance.iconStyle
            it[READER_FONT_SIZE_KEY] = appearance.readerFontSize
            it[READER_LINE_SPACING_KEY] = appearance.readerLineSpacing
            it[READER_THEME_KEY] = appearance.readerTheme
        }
    }
}
