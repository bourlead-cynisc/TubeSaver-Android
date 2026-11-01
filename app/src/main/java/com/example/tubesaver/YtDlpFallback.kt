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
        @Volatile private var ffmpegReady = false
        private val ffmpegLock = Any()
    }

    private fun init() {
        if (ready) return
        synchronized(lock) {
            if (!ready) {
                YoutubeDL.getInstance().init(context.applicationContext)
                ready = true
            }
        }
    }

    /** Pre-initialize native/Python binaries while the user is choosing a format. */
    fun warmUp() {
        init()
        initFfmpeg()
    }

    private fun initFfmpeg() {
        if (ffmpegReady) return
        synchronized(ffmpegLock) {
            if (!ffmpegReady) {
                FFmpeg.getInstance().init(context.applicationContext)
                ffmpegReady = true
            }
        }
    }

    fun inspect(link: String): Info {
        MetadataCache.fresh(context, link)?.let { cached ->
            try {
                return parseInfo(JSONObject(cached.readText(Charsets.UTF_8)))
            } catch (_: Exception) {
                MetadataCache.invalidate(context, link)
            }
        }
        init()
        val r = YoutubeDLRequest(link)
        if (SourceHosts.detect(link) == SourceHosts.Platform.YOUTUBE) {
            r.addOption("--remote-components", "ejs:github")
        }
        r.addOption("--dump-single-json")
        r.addOption("--skip-download")
        r.addOption("--no-playlist")
        r.addOption("--no-warnings")
        // Network timeouts are failure recovery, not a five-second UI cutoff.
        r.addOption("--socket-timeout", "12")
        r.addOption("--extractor-retries", "1")
        val output = YoutubeDL.getInstance().execute(r, processId, null).out
        val jsonIndex = output.indexOf('{')
        if (jsonIndex < 0) throw IOException("Не удалось получить информацию о видео")
        val json = JSONObject(output.substring(jsonIndex))
        // Reuse this very same resolved info-json in the download command.
        MetadataCache.store(context, link, json.toString())
        return parseInfo(json)
    }

    private fun parseInfo(json: JSONObject): Info {
        val formats = json.optJSONArray("formats")
        val entries = (0 until (formats?.length() ?: 0)).mapNotNull { index ->
            val format = formats?.optJSONObject(index) ?: return@mapNotNull null
            val video = format.optString("vcodec", "none") != "none"
            val audio = format.optString("acodec", "none") != "none"
            val kbps = if (audio && !video) format.optDouble("abr", 0.0) else
                format.optDouble("tbr", format.optDouble("vbr", 0.0))
            SizeEstimator.Format(
                height = format.optInt("height", 0),
                ext = format.optString("ext", ""),
                hasVideo = video, hasAudio = audio,
                bitrateKbps = kbps.takeIf { it.isFinite() && it > 0 },
                exactBytes = format.optLong("filesize", -1L).takeIf { it > 0 },
                approximateBytes = format.optLong("filesize_approx", -1L).takeIf { it > 0 }
            )
        }
        val duration = json.optDouble("duration", 0.0).takeIf { it.isFinite() && it > 0 }
        val heights = entries.filter { it.hasVideo && it.ext == "mp4" && it.height > 0 }
            .map { it.height }.distinct().sortedDescending()
        val best = SizeEstimator.select(entries, duration, null)
        val sizes = heights.mapNotNull { height ->
            SizeEstimator.select(entries, duration, height)?.let { size ->
                height to FormatSize(size.bytes, size.approximate)
            }
        }.toMap()
        return Info(json.optString("title", "Видео"), heights, sizes,
            best?.let { FormatSize(it.bytes, it.approximate) })
    }

    fun cancel() {
        cancelled = true
        try { YoutubeDL.getInstance().destroyProcessById(processId) } catch (_: Exception) {}
    }

    fun download(link: String, maxHeight: Int?, audioOnly: Boolean, dir: File,
                 progress: (Int, String) -> Unit, expected: FormatSize? = null): File {
        cancelled = false
        init()
        initFfmpeg()
        dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() }
        fun request(cached: File?): YoutubeDLRequest =
            YoutubeDLRequest(if (cached != null) emptyList<String>() else listOf(link)).apply {
            if (cached != null) {
                addOption("--load-info-json", cached.absolutePath)
            } else if (SourceHosts.detect(link) == SourceHosts.Platform.YOUTUBE) {
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
            val fresh = MetadataCache.fresh(context, link)
            try {
                YoutubeDL.getInstance().execute(request(fresh), processId, callback)
            } catch (cacheError: Exception) {
                if (fresh == null || cancelled) throw cacheError
                // A signed URL may have expired despite a fresh info-json.
                // Retry extraction only on a genuine cache failure.
                MetadataCache.invalidate(context, link)
                dir.listFiles()?.forEach { it.delete() }
                progress(-1, "Адрес видеопотока устарел · обновляем…")
                YoutubeDL.getInstance().execute(request(null), processId, callback)
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
