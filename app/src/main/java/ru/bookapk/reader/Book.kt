package ru.bookapk.reader

import org.json.JSONObject

data class BookCategory(val id: String, val name: String) {
    fun toJson() = JSONObject().put("id", id).put("name", name)
    companion object {
        fun fromJson(json: JSONObject) = BookCategory(json.getString("id"), json.getString("name"))
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
    val lastOpenedAt: Long = 0L
) {
    fun toJson() = JSONObject()
        .put("id", id).put("title", title).put("path", path).put("type", type)
        .put("progress", progress).put("categoryId", categoryId)
        .put("readingPosition", readingPosition).put("bookmarkPosition", bookmarkPosition)
        .put("isFavorite", isFavorite).put("lastOpenedAt", lastOpenedAt)

    companion object {
        fun fromJson(json: JSONObject) = Book(
            id = json.getString("id"),
            title = json.getString("title"),
            path = json.getString("path"),
            type = json.getString("type"),
            progress = json.optInt("progress", 0),
            categoryId = json.optString("categoryId", ""),
            readingPosition = json.optInt("readingPosition", 0),
            bookmarkPosition = json.optInt("bookmarkPosition", -1),
            isFavorite = json.optBoolean("isFavorite", false),
            lastOpenedAt = json.optLong("lastOpenedAt", 0L)
        )
    }
}

data class ReaderAppearance(
    val visualStyle: String = "classic",
    val iconStyle: String = "classic",
    val readerFontSize: Float = 18f,
    val readerLineSpacing: Float = 1.65f,
    val readerTheme: String = "paper"
)
