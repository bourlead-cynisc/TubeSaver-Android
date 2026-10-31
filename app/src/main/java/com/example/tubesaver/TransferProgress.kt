package com.example.tubesaver

import java.util.Locale

/**
 * Parses yt-dlp's machine progress template. Unlike yt-dlp's per-stream percentage,
 * the UI percentage never restarts at zero for the next video/audio stream.
 * With no reliable complete size, each expected stream receives a separate portion
 * of the progress bar, and it stays below 100 until verification and saving.
 */
class TransferProgress(
    private val expectedBytes: Long?,
    private val estimatedSize: Boolean,
    private val expectedStreams: Int
) {
    data class Snapshot(val percent: Int, val message: String)
    private var previous = 0L
    private var phase = 0
    private var completedBytes = 0L
    private var previousTotal = 0L

    fun read(line: String): Snapshot? {
        val payload = line.substringAfter("OFFTUBE_DATA:", "")
        if (payload.isEmpty()) return null
        val parts = payload.trim().split('|')
        if (parts.size < 5) return null
        fun parse(value: String): Long? =
            value.trim().toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
                ?.toLong()
        val downloaded = parse(parts[0]) ?: return null
        val total = parse(parts[1]) ?: parse(parts[2])
        val speed = parse(parts[3])
        val eta = parse(parts[4])
        // A new stream starts at a small byte count after the previous stream
        // was mostly or completely transferred.
        if (previous > 0 && downloaded < previous / 2 && phase < 8) {
            completedBytes += if (previousTotal > previous) previousTotal else previous
            phase += 1
        }
        previous = downloaded
        previousTotal = total ?: previousTotal
        val sent = completedBytes + downloaded
        val percent = if (expectedBytes != null && expectedBytes > 0L) {
            ((sent * 100.0 / expectedBytes).toInt()).coerceIn(0, 95)
        } else if (expectedStreams > 1) {
            (((phase.coerceAtMost(expectedStreams - 1) + 
                (if (total != null && total > 0L) downloaded.toDouble() / total else 0.0))
                / expectedStreams * 95)).toInt().coerceIn(0, 95)
        } else {
            (if (total != null && total > 0L) downloaded * 95.0 / total else 0.0)
                .toInt().coerceIn(0, 95)
        }
        val target = expectedBytes ?: (if (expectedStreams == 1) total else null)
        val size = formatBytes(sent) + (target?.let {
            " из " + (if (estimatedSize) "≈ " else "") + formatBytes(it)
        } ?: " · размер неизвестен")
        val speedLabel = speed?.takeIf { it > 0 }?.let { " · ${formatBytes(it)}/с" } ?: ""
        val remaining = if (speed != null && speed > 0 && target != null && target > sent)
            ((target - sent) / speed) else eta
        val etaLabel = remaining?.takeIf { it >= 0 }?.let { " · ≈ ${formatSeconds(it)}" } ?: ""
        return Snapshot(percent, "Скачано $size$speedLabel$etaLabel")
    }

    companion object {
        private fun formatBytes(bytes: Long): String =
            String.format(Locale.US, "%.1f МБ", bytes / 1048576.0)
        private fun formatSeconds(seconds: Long): String =
            if (seconds >= 60) "${seconds / 60} мин ${seconds % 60} с" else "${seconds} с"
    }
}