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

    suspend fun loadCategories(): List<String> {
        val raw = context.dataStore.data.first()[CATEGORIES_KEY] ?: "[]"
        // WHY: tolerate an interrupted/older category payload without making the library unavailable.
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                array.optString(index).trim().takeIf { it.isNotBlank() }
            }.distinct()
        }.getOrDefault(emptyList())
    }

    suspend fun saveCategories(categories: List<String>) {
        val array = JSONArray()
        categories.map { it.trim() }.filter { it.isNotBlank() }.distinct().forEach { array.put(it) }
        context.dataStore.edit { it[CATEGORIES_KEY] = array.toString() }
    }
}
