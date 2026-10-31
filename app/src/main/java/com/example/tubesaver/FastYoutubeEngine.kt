package com.example.tubesaver

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat as AndroidMediaFormat
import android.media.MediaMuxer
import android.os.SystemClock
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.VideoStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** YouTube: NewPipe extraction, native HTTP download, lossless Android MP4 muxing. */
class FastYoutubeEngine {
    data class Choice(val label: String, val height: Int, val videoUrl: String?,
                      val audioUrl: String?, val mux: Boolean, val audioOnly: Boolean = false)
    data class Video(val title: String, val choices: List<Choice>)
    @Volatile private var cancelled = false

    companion object {
        private val initLock = Any()
        @Volatile private var initialized = false
        private fun initialize() {
            if (initialized) return
            synchronized(initLock) {
                if (!initialized) {
                    NewPipe.init(object : Downloader() {
                        override fun execute(request: Request): Response {
                            val conn = URL(request.url()).openConnection() as HttpURLConnection
                            conn.connectTimeout = 12000
                            conn.readTimeout = 18000
                            conn.instanceFollowRedirects = true
                            conn.requestMethod = request.httpMethod()
                            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android 15; Mobile; rv:128.0) Gecko/128.0 Firefox/128.0")
                            for ((name, values) in request.headers()) {
                                if (values.isNotEmpty()) conn.setRequestProperty(name, values.joinToString(", "))
                            }
                            val body = request.dataToSend()
                            if (body != null) {
                                conn.doOutput = true
                                conn.outputStream.use { it.write(body) }
                            }
                            try {
                                val code = conn.responseCode
                                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                                val text = stream?.use { it.bufferedReader().readText() } ?: ""
                                val headers = conn.headerFields.filterKeys { it != null }.mapKeys { it.key!! }
                                return Response(code, conn.responseMessage ?: "", headers, text, conn.url.toString())
                            } finally {
                                conn.disconnect()
                            }
                        }
                    })
                    initialized = true
                }
            }
        }
        private fun height(resolution: String?): Int =
            Regex("""(\d{3,4})p""").find(resolution.orEmpty())?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    fun inspect(url: String): Video {
        initialize()
        val info = StreamInfo.getInfo(url)
        val audio = info.audioStreams
            .filter { it.isUrl && it.format == MediaFormat.M4A && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
            .filter { it.content.startsWith("https://", ignoreCase = true) }
            .maxByOrNull { it.averageBitrate }
        val allVideos: List<VideoStream> = info.videoStreams + info.videoOnlyStreams
        val groups = allVideos
            .filter { it.isUrl && it.format == MediaFormat.MPEG_4 && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
            .filter { it.content.startsWith("https://", ignoreCase = true) && height(it.resolution) > 0 }
            .groupBy { height(it.resolution) }
            .toSortedMap(compareByDescending { it })
        val choices = mutableListOf<Choice>()
        for ((size, available) in groups) {
            val progressive = available.firstOrNull { !it.isVideoOnly }
            val separate = available.firstOrNull { it.isVideoOnly }
            if (separate != null && audio != null) {
                choices.add(Choice("${size}p · MP4 + звук", size, separate.content, audio.content, true))
            } else if (progressive != null) {
                choices.add(Choice("${size}p · MP4 + звук", size, progressive.content, null, false))
            }
        }
        if (audio != null) choices.add(Choice("Только аудио · M4A", 0, null, audio.content, false, true))
        if (choices.isEmpty()) throw IOException("Нет совместимых MP4/M4A потоков. Попробуйте резервный способ.")
        return Video(info.name.ifBlank { "Видео" }, choices)
    }

    fun cancel() { cancelled = true }

    fun download(choice: Choice, folder: File, progress: (Int, String) -> Unit): File {
        cancelled = false
        folder.mkdirs()
        val videoFile = File(folder, "stream-video.mp4")
        val audioFile = File(folder, "stream-audio.m4a")
        val out = File(folder, if (choice.audioOnly) "offtube.m4a" else "offtube.mp4")
        listOf(videoFile, audioFile, out).forEach { it.delete() }
        val videoBytes = AtomicLong(0)
        val audioBytes = AtomicLong(0)
        val videoTotal = AtomicLong(-1)
        val audioTotal = AtomicLong(-1)
        val updatedAt = AtomicLong(0)
        val report: () -> Unit = {
            val now = SystemClock.elapsedRealtime()
            val last = updatedAt.get()
            if (now - last >= 180 && updatedAt.compareAndSet(last, now)) {
                val completed = videoBytes.get() + audioBytes.get()
                val vt = if (choice.videoUrl == null) 0L else videoTotal.get()
                val at = if (choice.audioUrl == null) 0L else audioTotal.get()
                val total = if (vt >= 0 && at >= 0) vt + at else 0
                val percent = if (total > 0) (completed * 100 / total).toInt().coerceIn(0, 99) else -1
                progress(percent, "Загружено ${completed / 1048576} МБ")
            }
        }
        val pool = Executors.newFixedThreadPool(2)
        try {
            val vf = choice.videoUrl?.let { pool.submit<Unit> { transfer(it, videoFile, videoBytes, videoTotal, report) } }
            val af = choice.audioUrl?.let { pool.submit<Unit> { transfer(it, audioFile, audioBytes, audioTotal, report) } }
            vf?.get()
            af?.get()
            if (cancelled) throw IOException("Загрузка отменена")
            progress(-1, "Объединяем дорожки…")
            if (choice.audioOnly) {
                if (!audioFile.renameTo(out)) audioFile.copyTo(out, overwrite = true)
            } else if (choice.mux) {
                muxMp4(videoFile, audioFile, out)
            } else {
                if (!videoFile.renameTo(out)) videoFile.copyTo(out, overwrite = true)
            }
            if (!out.isFile || out.length() == 0L) throw IOException("Сохранён пустой файл")
            return out
        } catch (e: Exception) {
            out.delete()
            throw (e.cause ?: e)
        } finally {
            pool.shutdownNow()
            videoFile.delete()
            audioFile.delete()
        }
    }

    private fun transfer(url: String, destination: File, done: AtomicLong, total: AtomicLong, report: () -> Unit) {
        var attempt = 0
        while (attempt < 3) {
            if (cancelled) throw IOException("Загрузка отменена")
            val existing = destination.takeIf { it.exists() }?.length() ?: 0L
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 25000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 Chrome/130.0 Mobile Safari/537.36")
            if (existing > 0) conn.setRequestProperty("Range", "bytes=$existing-")
            try {
                val code = conn.responseCode
                if (code != 200 && code != 206) throw IOException("Сервер вернул HTTP $code. Проверьте VPN.")
                val append = existing > 0 && code == 206
                done.set(if (append) existing else 0)
                total.set(if (conn.contentLengthLong > 0) conn.contentLengthLong + if (append) existing else 0 else -1)
                conn.inputStream.use { input ->
                    FileOutputStream(destination, append).buffered(128 * 1024).use { output ->
                        val buf = ByteArray(128 * 1024)
                        while (true) {
                            if (cancelled || Thread.currentThread().isInterrupted) throw IOException("Загрузка отменена")
                            val n = input.read(buf)
                            if (n == -1) break
                            output.write(buf, 0, n)
                            done.addAndGet(n.toLong())
                            report()
                        }
                    }
                }
                if (total.get() > 0 && destination.length() < total.get()) throw IOException("Сервер прервал передачу")
                return
            } catch (e: IOException) {
                if (cancelled || attempt >= 2 || e.message.orEmpty().contains("HTTP 403")) throw e
                attempt++
            } finally {
                conn.disconnect()
            }
        }
    }

    private fun muxMp4(video: File, audio: File, output: File) {
        val ve = MediaExtractor()
        val ae = MediaExtractor()
        var muxer: MediaMuxer? = null
        var started = false
        try {
            ve.setDataSource(video.absolutePath)
            ae.setDataSource(audio.absolutePath)
            val vi = (0 until ve.trackCount).firstOrNull {
                ve.getTrackFormat(it).getString(AndroidMediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: throw IOException("Видеодорожка не найдена")
            val ai = (0 until ae.trackCount).firstOrNull {
                ae.getTrackFormat(it).getString(AndroidMediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IOException("Аудиодорожка не найдена")
            ve.selectTrack(vi)
            ae.selectTrack(ai)
            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val vtrack = muxer.addTrack(ve.getTrackFormat(vi))
            val atrack = muxer.addTrack(ae.getTrackFormat(ai))
            muxer.start()
            started = true
            val buffer = ByteBuffer.allocateDirect(4 * 1024 * 1024)
            val sampleInfo = MediaCodec.BufferInfo()
            var activeVideo = true
            var activeAudio = true
            while (activeVideo || activeAudio) {
                if (cancelled) throw IOException("Загрузка отменена")
                val extractor = if (!activeAudio || (activeVideo && ve.sampleTime <= ae.sampleTime)) ve else ae
                val track = if (extractor === ve) vtrack else atrack
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) {
                    if (extractor === ve) activeVideo = false else activeAudio = false
                    continue
                }
                sampleInfo.set(0, size, extractor.sampleTime.coerceAtLeast(0), extractor.sampleFlags)
                muxer.writeSampleData(track, buffer, sampleInfo)
                extractor.advance()
            }
        } finally {
            try { if (started) muxer?.stop() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
            ve.release()
            ae.release()
        }
    }
}
