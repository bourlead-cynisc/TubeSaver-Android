package com.example.tubesaver

import kotlin.math.roundToLong

/** Conservative per-format size calculations matching our yt-dlp MP4 format selector. */
internal object SizeEstimator {
    data class Format(
        val height: Int,
        val ext: String,
        val hasVideo: Boolean,
        val hasAudio: Boolean,
        val bitrateKbps: Double?,
        val exactBytes: Long?,
        val approximateBytes: Long?
    )
    data class Size(val bytes: Long, val approximate: Boolean)

    fun measure(format: Format, durationSeconds: Double?): Size? {
        format.exactBytes?.takeIf { it > 0 }?.let { return Size(it, false) }
        format.approximateBytes?.takeIf { it > 0 }?.let { return Size(it, true) }
        val kbps = format.bitrateKbps?.takeIf { it.isFinite() && it > 0 } ?: return null
        val seconds = durationSeconds?.takeIf { it.isFinite() && it > 0 } ?: return null
        val calculated = kbps * 1000.0 * seconds / 8.0
        if (!calculated.isFinite() || calculated < 1 || calculated >= Long.MAX_VALUE.toDouble()) return null
        return Size(calculated.roundToLong(), true)
    }

    fun select(formats: List<Format>, durationSeconds: Double?, capHeight: Int?): Size? {
        val videos = formats.filter {
            it.ext == "mp4" && it.hasVideo && it.height > 0 &&
                (capHeight == null || it.height <= capHeight)
        }
        val byQuality = compareBy<Format> { it.height }.thenBy { it.bitrateKbps ?: 0.0 }
        val onlyVideo = videos.filter { !it.hasAudio }.maxWithOrNull(byQuality)
        val audio = formats.filter { !it.hasVideo && it.hasAudio && it.ext == "m4a" }
            .maxByOrNull { it.bitrateKbps ?: 0.0 }
        if (onlyVideo != null && audio != null) {
            val v = measure(onlyVideo, durationSeconds) ?: return null
            val a = measure(audio, durationSeconds) ?: return null
            if (v.bytes > Long.MAX_VALUE - a.bytes) return null
            return Size(v.bytes + a.bytes, v.approximate || a.approximate)
        }
        return videos.filter { it.hasAudio }.maxWithOrNull(byQuality)
            ?.let { measure(it, durationSeconds) }
    }
}
