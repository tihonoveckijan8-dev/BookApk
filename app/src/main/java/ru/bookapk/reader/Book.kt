package ru.bookapk.reader

import org.json.JSONObject

data class Book(
    val id: String,
    val title: String,
    val path: String,
    val type: String,
    val progress: Int = 0,
    // WHY: favorites belong to the local book record so they survive app restarts and work offline.
    val isFavorite: Boolean = false
) {
    fun toJson() = JSONObject().put("id", id).put("title", title)
        .put("path", path).put("type", type).put("progress", progress)
        .put("isFavorite", isFavorite)

    companion object {
        fun fromJson(json: JSONObject) = Book(
            id = json.getString("id"),
            title = json.getString("title"),
            path = json.getString("path"),
            type = json.getString("type"),
            progress = json.optInt("progress", 0),
            isFavorite = json.optBoolean("isFavorite", false)
        )
    }
}
