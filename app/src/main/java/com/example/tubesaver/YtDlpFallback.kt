package com.example.tubesaver

import android.content.Context
import android.os.SystemClock
import com.yausername.aria2c.Aria2c
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** Fallback for VK Video, Rutube and YouTube when a direct stream is not available. */
class YtDlpFallback(private val context: Context) {
    data class Info(val title: String, val heights: List<Int>)
    private val processId = "offtube-${System.nanoTime()}"
    @Volatile private var cancelled = false
    @Volatile private var ariaReady = false

    companion object {
        private val lock = Any()
        @Volatile private var ready = false
    }

    private fun init() {
        if (ready) return
        synchronized(lock) {
            if (!ready) {
                YoutubeDL.getInstance().init(context.applicationContext)
                FFmpeg.getInstance().init(context.applicationContext)
                ready = true
            }
        }
        if (!ariaReady) {
            try {
                Aria2c.getInstance().init(context.applicationContext)
                ariaReady = true
            } catch (_: Exception) { ariaReady = false }
        }
    }

    fun inspect(link: String): Info {
        init()
        val r = YoutubeDLRequest(link)
        r.addOption("--dump-single-json")
        r.addOption("--skip-download")
        r.addOption("--no-playlist")
        r.addOption("--no-warnings")
        r.addOption("--socket-timeout", "15")
        r.addOption("--extractor-retries", "1")
        val output = YoutubeDL.getInstance().execute(r).out
        val jsonIndex = output.indexOf('{')
        if (jsonIndex < 0) throw IOException("Не удалось получить информацию о видео")
        val json = JSONObject(output.substring(jsonIndex))
        val heights = sortedSetOf<Int>(compareByDescending { it })
        val formats = json.optJSONArray("formats")
        if (formats != null) for (i in 0 until formats.length()) {
            val f = formats.optJSONObject(i) ?: continue
            if (f.optInt("height") > 0 && f.optString("vcodec", "none") != "none") heights.add(f.optInt("height"))
        }
        if (heights.isEmpty() && json.optInt("height") > 0) heights.add(json.optInt("height"))
        return Info(json.optString("title", "Видео"), heights.toList())
    }

    fun cancel() {
        cancelled = true
        try { YoutubeDL.getInstance().destroyProcessById(processId) } catch (_: Exception) {}
    }

    fun download(link: String, maxHeight: Int?, audioOnly: Boolean, dir: File, progress: (Int, String) -> Unit): File {
        cancelled = false
        init()
        dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() }
        fun request(useAria: Boolean): YoutubeDLRequest = YoutubeDLRequest(link).apply {
            addOption("--no-playlist")
            addOption("--no-warnings")
            addOption("--newline")
            addOption("--socket-timeout", "20")
            addOption("--retries", "2")
            addOption("--fragment-retries", "3")
            addOption("--extractor-retries", "2")
            addOption("--concurrent-fragments", "6")
            addOption("-P", dir.absolutePath)
            addOption("-o", "%(title).90s-%(id)s.%(ext)s")
            if (useAria) addOption("--downloader", "libaria2c.so")
            if (audioOnly) {
                addOption("-f", "ba/b")
                addOption("-x")
                addOption("--audio-format", "mp3")
            } else {
                val cap = if (maxHeight == null) "" else "[height<=${maxHeight}]"
                addOption("-f", "bv[ext=mp4]${cap}+ba[ext=m4a]/b[ext=mp4][acodec!=none]${cap}")
                addOption("--merge-output-format", "mp4")
            }
        }
        var updated = 0L
        val callback: (Float, Long, String) -> Unit = { pct, _, line ->
            val now = SystemClock.elapsedRealtime()
            if (now - updated > 180) {
                updated = now
                val n = pct.toInt().coerceIn(0, 99)
                progress(if (pct > 0f) n else -1, if (line.contains("Merger", true) || line.contains("ExtractAudio", true)) "Обрабатываем файл…" else "Загрузка $n%")
            }
        }
        try {
            try {
                YoutubeDL.getInstance().execute(request(ariaReady), processId, callback)
            } catch (first: Exception) {
                if (cancelled || !ariaReady) throw first
                progress(-1, "Переключаемся на встроенный загрузчик…")
                dir.listFiles()?.forEach { it.delete() }
                YoutubeDL.getInstance().execute(request(false), processId, callback)
            }
            if (cancelled) throw IOException("Загрузка отменена")
            val extension = if (audioOnly) "mp3" else "mp4"
            return dir.listFiles()?.filter { it.isFile && it.extension.equals(extension, true) }
                ?.maxByOrNull { it.length() } ?: throw IOException("Готовый $extension не найден")
        } catch (e: Exception) {
            throw IOException(e.message ?: "Ошибка загрузки", e)
        }
    }
}
