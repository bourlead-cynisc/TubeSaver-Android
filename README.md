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

## 2.3.0
- Background downloads run in an Android foreground dataSync service with persistent notification, progress and cancellation. Closing or swiping away the UI does not stop the active service.
- Force-stop, OS service timeouts and some device battery restrictions can interrupt transfers; this is not an unconditional background guarantee.
- Format inspection gives user feedback within five seconds. When remote extraction is slower, automatic-quality download remains possible. The full response from YouTube/VK/Rutube cannot be guaranteed inside five seconds.
- Download starts a foreground notification immediately, reuses fast-path cached formats, and avoids a redundant preview request; actual network transfer startup still depends on server and VPN.

## 2.4.0 · More responsive format search and preparation
- The old five-second **cancellation** has been removed entirely: metadata extraction runs until completion, a real error, or a user change of URL.
- Prewarm NewPipe, the yt-dlp runtime, and FFmpeg once off the main thread while the user opens the app and enters a URL. UI stays interactive.
- Cache yt-dlp resolved `--dump-single-json` information per URL for up to 90 seconds, and pass it to `--load-info-json` on download. This skips repeating extraction for a recently inspected VK/Rutube/YouTube link.
- Cache direct NewPipe YouTube format URLs for 90 seconds instead of 30, with existing refresh-on-403 fallback.
- Keep foreground service startup asynchronous, remove the unrelated five-second watchdog, and display actual elapsed format search time.
- **Performance target, not a guarantee:** Remote DNS, VPN, JavaScript challenges, and source-server latency can exceed five seconds. No artificial timeout is imposed.
- Old debug release signatures are generated independently by GitHub Actions runners; uninstall the previous build before installing if Android reports a signature conflict.

## 2.5.0
- On YouTube, NewPipe and yt-dlp look up formats concurrently. The first successful extractor wins instead of the previous sequential approach that could add their delays.
- YouTube yt-dlp skips unneeded HLS and DASH manifest requests for the current MP4 mode.
- Bundles the version-matched, SHA256-verified yt-dlp-ejs 0.8.0 wheel inside the yt-dlp zipapp during GitHub Actions. This prevents a per-video GitHub release asset request for challenge solver scripts when local EJS works.
- Network read/connect timeouts in NewPipe lowered to fail over sooner when that endpoint stalls; yt-dlp still runs concurrently.
- The UI reports measured search duration and selected extractor; no result is discarded at five seconds.
- Five seconds remains a target, not a network guarantee. Verify on a device with the same VPN and URL.

## 2.5.1
- Refreshes the embedded yt-dlp executable in app-private noBackup storage when this APK is first opened. The upstream Android wrapper normally copies raw/ytdlp only when the file does not yet exist, leaving an older binary on upgrades.
