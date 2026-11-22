package ru.bookapk.reader

import org.json.JSONArray
import org.json.JSONObject

data class BookCategory(val id: String, val name: String) {
    fun toJson() = JSONObject().put("id", id).put("name", name)
    companion object {
        fun fromJson(json: JSONObject) = BookCategory(json.getString("id"), json.getString("name"))
    }
}

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

data class Book(
    val id: String,
    val title: String,
    val path: String,
    val type: String,
    val progress: Int = 0,
    val categoryId: String = "",
    val readingPosition: Int = 0,
    val bookmarkPosition: Int = -1,
    val isFavorite: Boolean = false,
    val lastOpenedAt: Long = 0L,
    val bookmarks: List<ReaderBookmark> = emptyList()
) {
    fun toJson(): JSONObject {
        val bookmarkArray = JSONArray()
        bookmarks.forEach { bookmarkArray.put(it.toJson()) }
        return JSONObject()
            .put("id", id).put("title", title).put("path", path).put("type", type)
            .put("progress", progress).put("categoryId", categoryId)
            .put("readingPosition", readingPosition).put("bookmarkPosition", bookmarkPosition)
            .put("isFavorite", isFavorite).put("lastOpenedAt", lastOpenedAt)
            .put("bookmarks", bookmarkArray)
    }

    companion object {
        fun fromJson(json: JSONObject): Book {
            val id = json.getString("id")
            val legacyPosition = json.optInt("bookmarkPosition", -1)
            val bookmarkArray = json.optJSONArray("bookmarks") ?: JSONArray()
            val savedBookmarks = (0 until bookmarkArray.length()).mapNotNull { index ->
                runCatching { ReaderBookmark.fromJson(bookmarkArray.getJSONObject(index)) }.getOrNull()
            }
            // WHY: migrate the previous single-bookmark field once, without losing existing saved places.
            val bookmarks = if (savedBookmarks.isNotEmpty()) savedBookmarks else if (legacyPosition >= 0) {
                listOf(ReaderBookmark("legacy-$id", legacyPosition, "Старая закладка", 0L))
            } else emptyList()

            return Book(
                id = id,
                title = json.getString("title"),
                path = json.getString("path"),
                type = json.getString("type"),
                progress = json.optInt("progress", 0),
                categoryId = json.optString("categoryId", ""),
                readingPosition = json.optInt("readingPosition", 0),
                bookmarkPosition = legacyPosition,
                isFavorite = json.optBoolean("isFavorite", false),
                lastOpenedAt = json.optLong("lastOpenedAt", 0L),
                bookmarks = bookmarks
            )
        }
    }
}

data class ReaderAppearance(
    val visualStyle: String = "classic",
    val iconStyle: String = "classic",
    val readerFontSize: Float = 18f,
    val readerLineSpacing: Float = 1.65f,
    val readerTheme: String = "paper"
)
