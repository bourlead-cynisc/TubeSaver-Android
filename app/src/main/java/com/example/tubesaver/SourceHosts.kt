package com.example.tubesaver

import java.net.URI

object SourceHosts {
    enum class Platform { YOUTUBE, RUTUBE, VK }
    fun detect(link: String): Platform? {
        val uri = try { URI(link.trim()) } catch (_: Exception) { return null }
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        val host = uri.host?.lowercase() ?: return null
        fun matches(domain: String) = host == domain || host.endsWith(".$domain")
        return when {
            matches("youtube.com") || matches("youtu.be") || matches("youtube-nocookie.com") -> Platform.YOUTUBE
            matches("rutube.ru") -> Platform.RUTUBE
            matches("vk.com") || matches("vk.ru") || matches("vkvideo.ru") -> Platform.VK
            else -> null
        }
    }
}
