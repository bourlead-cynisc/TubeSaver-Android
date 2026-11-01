package com.example.tubesaver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import java.io.File
import kotlin.concurrent.thread

/** Foreground dataSync transfer survives closing/swiping the Activity. */
class DownloadService : Service() {
    companion object {
        const val ACTION_DOWNLOAD = "com.example.tubesaver.DOWNLOAD"
        const val ACTION_CANCEL = "com.example.tubesaver.CANCEL"
        const val STATE_PREFS = "offtube_download_state"
        private const val CHANNEL_ID = "offtube_downloads"
        private const val NOTIFICATION_ID = 41
        @Volatile var running = false
        @Volatile var engine: FastYoutubeEngine? = null
        @Volatile var sessionLink: String = ""
        @Volatile var sessionChoices: List<FastYoutubeEngine.Choice> = emptyList()

        fun start(context: Context, data: Intent) {
            data.setClass(context, DownloadService::class.java)
            data.action = ACTION_DOWNLOAD
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(data)
            else context.startService(data)
        }
        fun cancel(context: Context) {
            context.startService(Intent(context, DownloadService::class.java).setAction(ACTION_CANCEL))
        }
    }

    @Volatile private var cancelled = false
    @Volatile private var currentFast: FastYoutubeEngine? = null
    @Volatile private var currentFallback: YtDlpFallback? = null
    private lateinit var nm: NotificationManager
    private var lastNotifyAt = 0L
    private var lastPersistAt = 0L
    private var currentTitle = "Видео"
    private var lastProgress = -1
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Загрузки offtube", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(message: String, progress: Int, active: Boolean): Notification {
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val b = if (Build.VERSION.SDK_INT >= 26)
            Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        b.setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(if (active) "offtube · $currentTitle" else "offtube")
            .setContentText(message.take(120))
            .setContentIntent(open)
            .setOngoing(active)
            .setOnlyAlertOnce(true)
        if (active) {
            b.setProgress(100, progress.coerceAtLeast(0), progress < 0)
            val cancel = PendingIntent.getService(this, 1,
                Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            b.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Отменить", cancel)
        } else b.setAutoCancel(true)
        return b.build()
    }

    private fun foreground() {
        val n = notification("Подготовка запущена · приложение можно закрыть", -1, true)
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(NOTIFICATION_ID, n)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                cancelled = true
                currentFast?.cancel()
                currentFallback?.cancel()
                if (!running) stopSelf()
            }
            ACTION_DOWNLOAD -> if (!running) {
                running = true
                cancelled = false
                currentTitle = intent.getStringExtra("title")?.ifBlank { "Видео" } ?: "Видео"
                lastProgress = -1
                foreground()
                wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "offtube:active-download")
                    .apply { acquire(2 * 60 * 60 * 1000L) }
                saveStatus(true, -1, "Подготовка запущена · можно закрыть приложение", currentTitle)
                val copy = Intent(intent)
                thread(name = "offtube-foreground-download") { download(copy) }
            }
        }
        return START_NOT_STICKY
    }

    private fun saveStatus(active: Boolean, progress: Int, message: String, title: String,
                           uri: Uri? = null, audioOnly: Boolean = false) {
        val edit = getSharedPreferences(STATE_PREFS, MODE_PRIVATE).edit()
            .putBoolean("active", active)
            .putInt("progress", progress)
            .putString("message", message)
            .putString("title", title)
            .putLong("updated", System.currentTimeMillis())
        if (uri != null) edit.putString("uri", uri.toString())
            .putBoolean("audioOnly", audioOnly).putString("recent", "↗  $title")
        edit.apply()
    }

    private fun progress(percent: Int, message: String) {
        if (cancelled) return
        if (percent >= 0) lastProgress = maxOf(lastProgress, percent.coerceIn(0, 99))
        val now = SystemClock.elapsedRealtime()
        if (now - lastPersistAt >= 350 || message.contains("Объединяем") ||
            message.contains("Сохраняем") || message.contains("Проверяем")) {
            lastPersistAt = now
            saveStatus(true, lastProgress, message, currentTitle)
        }
        if (now - lastNotifyAt >= 900) {
            lastNotifyAt = now
            nm.notify(NOTIFICATION_ID, notification(message, lastProgress, true))
        }
    }

    private fun download(request: Intent) {
        val folder = File(cacheDir, "offtube-" + System.nanoTime()).apply { mkdirs() }
        var doneMessage: String
        try {
            val link = request.getStringExtra("link") ?: throw IllegalArgumentException("Нет ссылки")
            val platform = SourceHosts.detect(link) ?: throw IllegalArgumentException("Неверная ссылка")
            var audioOnly = request.getBooleanExtra("audio", false)
            val height = request.getIntExtra("height", 0).takeIf { it > 0 }
            val idx = request.getIntExtra("choiceIndex", 0)
            val useFast = request.getBooleanExtra("fast", false)
            val choice = if (platform == SourceHosts.Platform.YOUTUBE && useFast && sessionLink == link)
                sessionChoices.getOrNull(idx) else null
            val expected = request.getLongExtra("estimatedBytes", -1).takeIf { it > 0 }?.let {
                YtDlpFallback.FormatSize(it, request.getBooleanExtra("approximate", true))
            }
            // The foreground service is already running; do not impose a
            // timer on remote preparation or unnecessarily restart extraction.
            val downloaded = if (choice != null) {
                audioOnly = choice.audioOnly
                val fast = engine ?: FastYoutubeEngine()
                currentFast = fast
                try {
                    fast.download(link, choice, folder, ::progress)
                } catch (nativeError: Exception) {
                    if (cancelled || nativeError.message.orEmpty().contains("отмен", true)) throw nativeError
                    folder.listFiles()?.forEach { it.delete() }
                    progress(-1, "Переключаемся на резервный загрузчик…")
                    val slow = YtDlpFallback(applicationContext)
                    currentFallback = slow
                    slow.download(link, choice.height.takeIf { it > 0 }, audioOnly, folder, ::progress, expected)
                }
            } else {
                // No duplicate format preview: yt-dlp resolves formats while starting download.
                val slow = YtDlpFallback(applicationContext)
                currentFallback = slow
                slow.download(link, height, audioOnly, folder, ::progress, expected)
            }
            if (cancelled) throw java.io.IOException("Загрузка отменена")
            progress(-1, "Проверяем файл и звук…")
            SaveMedia.verify(downloaded, audioOnly)
            progress(-1, "Сохраняем в Download/OffTube…")
            val dest = SaveMedia.save(applicationContext, downloaded, currentTitle, audioOnly)
            lastProgress = 100
            doneMessage = "Файл сохранён в Download/OffTube"
            saveStatus(false, 100, doneMessage, currentTitle, dest, audioOnly)
            nm.notify(NOTIFICATION_ID, notification(doneMessage, 100, false))
        } catch (e: Exception) {
            doneMessage = if (cancelled) "Загрузка отменена" else
                "Ошибка: " + (e.cause?.message ?: e.message ?: "Неизвестная ошибка").take(160)
            saveStatus(false, -1, doneMessage, currentTitle)
            nm.notify(NOTIFICATION_ID, notification(doneMessage, -1, false))
        } finally {
            folder.deleteRecursively()
            try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
            wakeLock = null
            currentFast = null
            currentFallback = null
            running = false
            if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_DETACH)
            else stopForeground(false)
            stopSelf()
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // Deliberately do not stop: the foreground notification owns the transfer.
    }
}
