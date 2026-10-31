# offtube for Android

Minimal offline video downloader for YouTube, Rutube, and VK Video.

**Engines**
- YouTube: NewPipeExtractor v0.26.5 for stream lookup; native Android parallel HTTP downloads and MediaMuxer for AAC+MP4. Short-lived stream URLs are refreshed after 30 seconds and on HTTP 403, and media transfers use the same user agent as extraction.
- Rutube / VK Video: yt-dlp's built-in downloader with concurrent fragment downloads and FFmpeg for merging audio/video (no slow aria2c fallback cycle).
- If NewPipe cannot extract or download a YouTube link, yt-dlp is tried as a fallback with official EJS JavaScript challenge scripts enabled via `--remote-components ejs:github`.

**Build:** GitHub Actions downloads the official yt-dlp `2026.10.26` zipapp from the project's GitHub release and validates SHA256 before compiling; it overrides the fallback library's packaged older executable. A manual local Gradle build will not automatically perform that step: download the verified zipapp into `app/src/main/res/raw/ytdlp` before building. Build with JDK 17 / Android SDK 35 using `gradle :app:assembleDebug`. GitHub Actions publishes a prerelease APK to GitHub Releases upon successful build.

**Use:** paste a video URL, optionally tap “Найти видео” to select quality, then tap “Скачать”. Videos are saved to `Download/OffTube` on Android 10+; older Android devices use the app-specific external downloads directory. Android VPN connectivity must already work for the source service. DRM, private videos and authentication-gated streams are not supported.

**Limitations:** Compilation and unit tests via GitHub Actions do not confirm that YouTube streams download through a particular Android VPN. This project has not been verified on physical devices or real streams. Remote services can change their extraction APIs at any time. Audio-only YouTube uses M4A in fast mode; MP3 is available in fallback mode. Download only content you have the right to save.

**Licensing:** The source integrates [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor) (GPL-3.0-or-later) and [youtubedl-android](https://github.com/yausername/youtubedl-android) (GPL-3.0). Distribution must satisfy the applicable GPL conditions, including corresponding source availability and attribution.

## 2.1.0
- Launcher artwork supplied by the user; image also displayed in the header.
- Single non-decreasing transfer progress throughout stream merging and saving.
- Bytes downloaded, current bandwidth and estimated time remaining when known.
- Size per quality from available video metadata; unknown or approximate values are labeled explicitly.

## 2.2.0
- Black/red theme matching the user supplied launcher icon, including quality picker and selected source.
- Size per quality uses exact filesize first, filesize_approx second, then a marked estimate based on bitrate and duration.
- Audio/video track sizes are added only for the actual MP4+M4A format selection; missing information remains unknown.
