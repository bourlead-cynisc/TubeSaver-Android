package com.example.tubesaver

import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.IOException

object SaveMedia {
    fun verify(file: File, audioOnly: Boolean) {
        if (!file.isFile || file.length() == 0L) throw IOException("Файл пуст")
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            if (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) != "yes") {
                throw IOException("Звуковая дорожка отсутствует — файл не сохранён")
            }
            if (!audioOnly && retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) != "yes") {
                throw IOException("Видеодорожка отсутствует — файл не сохранён")
            }
        } finally { retriever.release() }
    }

    fun save(context: Context, file: File, title: String, audioOnly: Boolean): Uri {
        val extension = file.extension.lowercase()
        val cleanName = title.replace(Regex("[\\/:*?\"<>|\\p{Cntrl}]"), "_").trim(' ', '.').take(84).ifBlank { "Видео" }
        val fileName = "$cleanName.$extension"
        if (Build.VERSION.SDK_INT < 29) {
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: throw IOException("Нет папки загрузки")
            dir.mkdirs()
            val dst = File(dir, fileName)
            file.copyTo(dst, overwrite = true)
            return Uri.fromFile(dst)
        }
        val mime = when (extension) {
            "m4a" -> "audio/mp4"
            "mp3" -> "audio/mpeg"
            else -> "video/mp4"
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/OffTube")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Не удалось создать файл в Download/OffTube")
        try {
            val stream = context.contentResolver.openOutputStream(uri) ?: throw IOException("Не удалось открыть файл")
            stream.use { output -> file.inputStream().use { input -> input.copyTo(output) } }
            context.contentResolver.update(uri, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }, null, null)
            return uri
        } catch (e: Exception) {
            context.contentResolver.delete(uri, null, null)
            throw e
        }
    }
}
