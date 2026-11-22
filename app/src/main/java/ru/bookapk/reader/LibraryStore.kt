package ru.bookapk.reader

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import org.json.JSONArray

private val Context.dataStore by preferencesDataStore(name = "book_library")
private val LIBRARY_KEY = stringPreferencesKey("books")
private val CATEGORIES_KEY = stringPreferencesKey("categories")

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
}
