package com.example.tubesaver

import android.content.Context
import android.os.SystemClock
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** Fallback for VK Video, Rutube and YouTube when a direct stream is not available. */
class YtDlpFallback(private val context: Context) {
    data class FormatSize(val bytes: Long, val approximate: Boolean)
    data class Info(val title: String, val heights: List<Int>,
                    val sizes: Map<Int, FormatSize>, val bestSize: FormatSize?)
    private val processId = "offtube-${System.nanoTime()}"
    @Volatile private var cancelled = false

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
    }

    fun inspect(link: String): Info {
        init()
        val r = YoutubeDLRequest(link)
        if (SourceHosts.detect(link) == SourceHosts.Platform.YOUTUBE) {
            r.addOption("--remote-components", "ejs:github")
        }
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
        fun sizeOf(f: JSONObject): FormatSize? {
            val exact = f.optLong("filesize", -1L)
            if (exact > 0) return FormatSize(exact, false)
            val approx = f.optLong("filesize_approx", -1L)
            return if (approx > 0) FormatSize(approx, true) else null
        }
        val allFormats = (0 until (formats?.length() ?: 0)).mapNotNull { formats?.optJSONObject(it) }
        val audio = allFormats.filter { it.optString("vcodec") == "none" &&
            it.optString("acodec", "none") != "none" && it.optString("ext") == "m4a" }
            .maxByOrNull { it.optDouble("abr", 0.0) }
        val audioSize = audio?.let(::sizeOf)
        val sizes = mutableMapOf<Int, FormatSize>()
        for (height in heights) {
            val available = allFormats.filter { it.optInt("height") == height &&
                it.optString("vcodec", "none") != "none" && it.optString("ext") == "mp4" }
            val separate = available.filter { it.optString("acodec", "none") == "none" }
                .maxByOrNull { it.optDouble("tbr", 0.0) }
            val combined = available.filter { it.optString("acodec", "none") != "none" }
                .maxByOrNull { it.optDouble("tbr", 0.0) }
            val pair = separate?.let(::sizeOf)
            val whole = combined?.let(::sizeOf)
            val totalSize = if (pair != null && audioSize != null)
                FormatSize(pair.bytes + audioSize.bytes, pair.approximate || audioSize.approximate)
            else whole
            if (totalSize != null) sizes[height] = totalSize
        }
        val firstSize = heights.firstOrNull()?.let { sizes[it] }
        return Info(json.optString("title", "Видео"), heights.toList(), sizes, firstSize)
    }

    fun cancel() {
        cancelled = true
        try { YoutubeDL.getInstance().destroyProcessById(processId) } catch (_: Exception) {}
    }

    fun download(link: String, maxHeight: Int?, audioOnly: Boolean, dir: File,
                 progress: (Int, String) -> Unit, expected: FormatSize? = null): File {
        cancelled = false
        init()
        dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() }
        fun request(): YoutubeDLRequest = YoutubeDLRequest(link).apply {
            if (SourceHosts.detect(link) == SourceHosts.Platform.YOUTUBE) {
                // YouTube now requires external JS challenge solvers.
                // QuickJS is provisioned by the Android wrapper; fetch EJS from yt-dlp's official releases.
                addOption("--remote-components", "ejs:github")
            }
            addOption("--no-playlist")
            addOption("--no-warnings")
            addOption("--newline")
            addOption("--progress-template", "download:OFFTUBE_DATA:%(progress.downloaded_bytes)s|%(progress.total_bytes)s|%(progress.total_bytes_estimate)s|%(progress.speed)s|%(progress.eta)s")
            addOption("--socket-timeout", "20")
            addOption("--retries", "2")
            addOption("--fragment-retries", "3")
            addOption("--extractor-retries", "2")
            addOption("--concurrent-fragments", "6")
            addOption("-P", dir.absolutePath)
            addOption("-o", "%(title).90s-%(id)s.%(ext)s")
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
        val meter = TransferProgress(expected?.bytes,
            expected?.approximate == true,
            if (SourceHosts.detect(link) == SourceHosts.Platform.YOUTUBE && !audioOnly) 2 else 1)
        val callback: (Float, Long, String) -> Unit = { pct, _, line ->
            if (line.contains("OFFTUBE_DATA:")) {
                meter.read(line)?.let { snapshot -> progress(snapshot.percent, snapshot.message) }
            } else if (line.contains("Merger", true) || line.contains("ExtractAudio", true)
                    || line.contains("Fixup", true)) {
                progress(-1, "Объединяем видео и звук…")
            } else if (line.contains("[download] Destination", true)) {
                progress(-1, "Начинаем передачу данных…")
            }
        }
        try {
            // yt-dlp's built-in downloader can handle video fragments and YouTube headers
            // without introducing a second aria2c failure/fallback cycle.
            YoutubeDL.getInstance().execute(request(), processId, callback)
            if (cancelled) throw IOException("Загрузка отменена")
            val extension = if (audioOnly) "mp3" else "mp4"
            return dir.listFiles()?.filter { it.isFile && it.extension.equals(extension, true) }
                ?.maxByOrNull { it.length() } ?: throw IOException("Готовый $extension не найден")
        } catch (e: Exception) {
            throw IOException(e.message ?: "Ошибка загрузки", e)
        }
    }
}
