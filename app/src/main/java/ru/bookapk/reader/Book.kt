package ru.bookapk.reader

import org.json.JSONArray
import org.json.JSONObject

data class ReaderBookmark(
    val id: String,
    val position: Int,
    val title: String,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun toJson() = JSONObject()
        .put("id", id)
        .put("position", position)
        .put("title", title)
        .put("createdAt", createdAt)

    companion object {
        fun fromJson(json: JSONObject) = ReaderBookmark(
            id = json.optString("id", java.util.UUID.randomUUID().toString()),
            position = json.optInt("position", 0).coerceAtLeast(0),
            title = json.optString("title", "Закладка"),
            createdAt = json.optLong("createdAt", 0L)
        )
    }
}

data class ReaderNote(
    val id: String,
    val position: Int,
    val text: String,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun toJson() = JSONObject()
        .put("id", id)
        .put("position", position)
        .put("text", text)
        .put("createdAt", createdAt)

    companion object {
        fun fromJson(json: JSONObject) = ReaderNote(
            id = json.optString("id", java.util.UUID.randomUUID().toString()),
            position = json.optInt("position", 0).coerceAtLeast(0),
            text = json.optString("text", ""),
            createdAt = json.optLong("createdAt", 0L)
        )
    }
}

data class Book(
    val id: String,
    val title: String,
    val path: String,
    val type: String,
    val progress: Int = 0,
    // WHY: favorites belong to the local book record so they survive app restarts and work offline.
    val isFavorite: Boolean = false,
    // WHY: category is stored with each book so assigning a folder works fully offline.
    val category: String = "",
    // WHY: a stable import timestamp enables "recently added" sorting after every restart.
    val addedAt: Long = System.currentTimeMillis(),
    // WHY: progress is a percentage; a separate position is needed to reopen the exact paragraph/page.
    val readingPosition: Int = 0,
    val bookmarks: List<ReaderBookmark> = emptyList(),
    val notes: List<ReaderNote> = emptyList()
) {
    fun toJson(): JSONObject {
        val bookmarkArray = JSONArray()
        bookmarks.forEach { bookmarkArray.put(bookmark.toJson()) }
        val noteArray = JSONArray()
        notes.forEach { noteArray.put(it.toJson()) }
        return JSONObject()
            .put("id", id)
            .put("title", title)
            .put("path", path)
            .put("type", type)
            .put("progress", progress)
            .put("isFavorite", isFavorite)
            .put("category", category)
            .put("addedAt", addedAt)
            .put("readingPosition", readingPosition)
            .put("bookmarks", bookmarkArray)
            .put("notes", noteArray)
    }

    companion object {
        fun fromJson(json: JSONObject): Book {
            val bookmarkArray = json.optJSONArray("bookmarks") ?: JSONArray()
            val bookmarks = (0 until bookmarkArray.length()).mapNotNull { index ->
                runCatching { ReaderBookmark.fromJson(bookmarkArray.getJSONObject(index)) }.getOrNull()
            }
            val noteArray = json.optJSONArray("notes") ?: JSONArray()
            val notes = (0 until noteArray.length()).mapNotNull { index ->
                runCatching { ReaderNote.fromJson(noteArray.getJSONObject(index)) }.getOrNull()
            }
            // WHY: old versions stored only a rough percent-derived position; keep that approximation on upgrade.
            val legacyPosition = json.optInt("progress", 0)
            return Book(
                id = json.getString("id"),
                title = json.getString("title"),
                path = json.getString("path"),
                type = json.getString("type"),
                progress = json.optInt("progress", 0).coerceIn(0, 100),
                isFavorite = json.optBoolean("isFavorite", false),
                category = json.optString("category", ""),
                addedAt = json.optLong("addedAt", System.currentTimeMillis()),
                readingPosition = json.optInt("readingPosition", legacyPosition).coerceAtLeast(0),
                bookmarks = bookmarks,
                notes = notes
            )
        }
    }
}
