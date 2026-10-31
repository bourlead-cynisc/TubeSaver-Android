# offtube for Android

Minimal offline video downloader for YouTube, Rutube, and VK Video.

**Engines**
- YouTube: NewPipeExtractor v0.26.5 for stream lookup; native Android HTTP downloads, video/audio in parallel, MediaMuxer to combine AAC and MP4 without re-encoding.
- Rutube / VK Video: yt-dlp with optional aria2c for faster HTTP downloads and FFmpeg for merging audio/video. If aria2c fails, the built-in downloader is retried.
- If NewPipe cannot extract a YouTube link, yt-dlp is tried as a fallback.

**Build:** Open in Android Studio with JDK 17 / Android SDK 35, or run `gradle :app:assembleDebug`. APK appears in `app/build/outputs/apk/debug/`. GitHub Actions uploads the APK as an artifact if the build succeeds.

**Use:** paste a video URL, optionally tap “Найти видео” to select quality, then tap “Скачать”. Videos are saved to `Download/OffTube` on Android 10+; older Android devices use the app-specific external downloads directory. Android VPN connectivity must already work for the source service. DRM, private videos and authentication-gated streams are not supported.

**Limitations:** This project has not been verified on physical devices or real streams. Remote services can change their extraction APIs at any time. Audio-only YouTube uses M4A in fast mode; MP3 is available in fallback mode. Download only content you have the right to save.

**Licensing:** The source integrates [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor) (GPL-3.0-or-later) and [youtubedl-android](https://github.com/yausername/youtubedl-android) (GPL-3.0). Distribution must satisfy the applicable GPL conditions, including corresponding source availability and attribution.
