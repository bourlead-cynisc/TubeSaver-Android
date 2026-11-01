package com.example.tubesaver

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * Private, short-lived yt-dlp info-json cache.
 *
 * A full yt-dlp inspection already contains the format URLs and selected
 * headers; using --load-info-json lets a subsequent download skip the
 * otherwise duplicated extractor/page/network work. Signed URLs expire, so
 * this optimization is limited to a short fresh window with live fallback.
 */
internal object MetadataCache {
    const val MAX_AGE_MS = 90_000L

    private fun file(context: Context, link: String): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(link.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(context.cacheDir, "offtube-info-$digest.json")
    }

    fun fresh(context: Context, link: String): File? {
        val file = file(context, link)
        val age = System.currentTimeMillis() - file.lastModified()
        return file.takeIf { it.isFile && it.length() > 0 && age in 0..MAX_AGE_MS }
    }

    fun store(context: Context, link: String, infoJson: String) {
        val target = file(context, link)
        val temporary = File(target.parentFile, target.name + ".tmp")
        temporary.writeText(infoJson, Charsets.UTF_8)
        if (!temporary.renameTo(target)) {
            temporary.copyTo(target, overwrite = true)
            temporary.delete()
        }
    }

    fun invalidate(context: Context, link: String) {
        file(context, link).delete()
    }
}
