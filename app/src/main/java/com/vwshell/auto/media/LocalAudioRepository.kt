package com.vwshell.auto.media

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.MediaStore

/** Реальная композиция из MediaStore. Никаких фиктивных треков: только файлы устройства. */
data class AudioItem(
    val id: Long,
    val uri: Uri,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long
) {
    val displayTitle: String get() = title.ifBlank { "Без названия" }
    val displayArtist: String get() = artist.ifBlank { "Неизвестный исполнитель" }
}

/**
 * Чтение аудиомедиатеки через MediaStore.Audio (Android 10+ scoped storage).
 * На Android 13 (API 33) требуется READ_MEDIA_AUDIO; на Android 10–11 — READ_EXTERNAL_STORAGE.
 * Разрешение запрашивает UI перед вызовом load(); без разрешения load() возвращает пустой список.
 */
object LocalAudioRepository {

    private const val SORT_ORDER = "${MediaStore.Audio.Media.ARTIST} ASC, ${MediaStore.Audio.Media.TRACK} ASC"

    fun load(context: Context): List<AudioItem> {
        val items = ArrayList<AudioItem>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC != 0}"
        var cursor: Cursor? = null
        try {
            cursor = context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection, selection, null, SORT_ORDER)
            if (cursor == null) return emptyList()
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val durCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                items.add(
                    AudioItem(
                        id = id,
                        uri = Uri.withAppendedPath(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id.toString()),
                        title = cursor.getString(titleCol) ?: "",
                        artist = normalize(cursor.getString(artistCol)),
                        album = normalize(cursor.getString(albumCol)),
                        durationMs = cursor.getLong(durCol)
                    )
                )
            }
        } catch (_: SecurityException) {
            // Разрешение не выдано / отозвано — честно возвращаем пустую медиатеку.
        } catch (_: Throwable) {
            return emptyList()
        } finally {
            cursor?.close()
        }
        return items
    }

    /** MediaStore иногда возвращает "<unknown>" для отсутствующих тегов. */
    private fun normalize(value: String?): String =
        value?.takeIf { it.isNotBlank() && !it.equals("<unknown>", true) } ?: ""
}
