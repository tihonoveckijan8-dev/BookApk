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
    val createdAt: Long = System.currentTimeMillis(),
    val offsetPx: Int = 0,
    val note: String = ""
) {
    fun toJson() = JSONObject()
        .put("id", id)
        .put("position", position)
        .put("title", title)
        .put("createdAt", createdAt)
        .put("offsetPx", offsetPx)
        .put("note", note)

    companion object {
        fun fromJson(json: JSONObject) = ReaderBookmark(
            id = json.optString("id", java.util.UUID.randomUUID().toString()),
            position = json.optInt("position", 0).coerceAtLeast(0),
            title = json.optString("title", "Закладка"),
            createdAt = json.optLong("createdAt", 0L),
            offsetPx = json.optInt("offsetPx", 0).coerceAtLeast(0),
            note = json.optString("note", "")
        )
    }
}

data class ReaderAnnotation(
    val id: String,
    val position: Int,
    val offsetPx: Int = 0,
    val text: String,
    val note: String = "",
    val color: String = "yellow",
    val createdAt: Long = System.currentTimeMillis(),
    val reviewStage: Int = 0,
    val nextReviewAt: Long = 0L,
    val lastReviewedAt: Long = 0L
) {
    fun toJson() = JSONObject()
        .put("id", id)
        .put("position", position)
        .put("offsetPx", offsetPx)
        .put("text", text)
        .put("note", note)
        .put("color", color)
        .put("createdAt", createdAt)
        .put("reviewStage", reviewStage)
        .put("nextReviewAt", nextReviewAt)
        .put("lastReviewedAt", lastReviewedAt)

    companion object {
        fun fromJson(json: JSONObject) = ReaderAnnotation(
            id = json.optString("id", java.util.UUID.randomUUID().toString()),
            position = json.optInt("position", 0).coerceAtLeast(0),
            offsetPx = json.optInt("offsetPx", 0).coerceAtLeast(0),
            text = json.optString("text", ""),
            note = json.optString("note", ""),
            color = json.optString("color", "yellow").takeIf { it in setOf("yellow", "green", "blue", "pink") } ?: "yellow",
            createdAt = json.optLong("createdAt", 0L),
            reviewStage = json.optInt("reviewStage", 0).coerceIn(0, 4),
            nextReviewAt = json.optLong("nextReviewAt", 0L).coerceAtLeast(0L),
            lastReviewedAt = json.optLong("lastReviewedAt", 0L).coerceAtLeast(0L)
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
    val readingOffsetPx: Int = 0,
    val bookmarkPosition: Int = -1,
    val isFavorite: Boolean = false,
    val lastOpenedAt: Long = 0L,
    val bookmarks: List<ReaderBookmark> = emptyList(),
    val annotations: List<ReaderAnnotation> = emptyList(),
    val author: String = "",
    val coverPath: String = "",
    val imageDirectoryPath: String = ""
) {
    fun toJson(): JSONObject {
        val bookmarkArray = JSONArray()
        bookmarks.forEach { bookmarkArray.put(it.toJson()) }
        val annotationArray = JSONArray()
        annotations.forEach { annotationArray.put(it.toJson()) }
        return JSONObject()
            .put("id", id).put("title", title).put("path", path).put("type", type)
            .put("progress", progress).put("categoryId", categoryId)
            .put("readingPosition", readingPosition).put("readingOffsetPx", readingOffsetPx).put("bookmarkPosition", bookmarkPosition)
            .put("isFavorite", isFavorite).put("lastOpenedAt", lastOpenedAt)
            .put("bookmarks", bookmarkArray)
            .put("annotations", annotationArray)
            .put("author", author)
            .put("coverPath", coverPath)
            .put("imageDirectoryPath", imageDirectoryPath)
    }

    companion object {
        fun fromJson(json: JSONObject): Book {
            val id = json.getString("id")
            val legacyPosition = json.optInt("bookmarkPosition", -1)
            val bookmarkArray = json.optJSONArray("bookmarks") ?: JSONArray()
            val savedBookmarks = (0 until bookmarkArray.length()).mapNotNull { index ->
                runCatching { ReaderBookmark.fromJson(bookmarkArray.getJSONObject(index)) }.getOrNull()
            }
            val annotationArray = json.optJSONArray("annotations") ?: JSONArray()
            val annotations = (0 until annotationArray.length()).mapNotNull { index ->
                runCatching { ReaderAnnotation.fromJson(annotationArray.getJSONObject(index)) }.getOrNull()
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
                readingOffsetPx = json.optInt("readingOffsetPx", 0).coerceAtLeast(0),
                bookmarkPosition = legacyPosition,
                isFavorite = json.optBoolean("isFavorite", false),
                lastOpenedAt = json.optLong("lastOpenedAt", 0L),
                bookmarks = bookmarks,
                annotations = annotations,
                author = json.optString("author", ""),
                coverPath = json.optString("coverPath", ""),
                imageDirectoryPath = json.optString("imageDirectoryPath", "")
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
