package com.example.tubesaver

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.text.TextWatcher
import android.text.Editable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private val dark = Color.rgb(8, 9, 13)
    private val surface = Color.rgb(23, 25, 33)
    private val outline = Color.rgb(47, 49, 61)
    private val muted = Color.rgb(169, 174, 187)
    private val accent = Color.rgb(255, 43, 79)
    private lateinit var linkInput: EditText
    private lateinit var status: TextView
    private lateinit var title: TextView
    private lateinit var quality: TextView
    private lateinit var analyzeBtn: Button
    private lateinit var downloadBtn: Button
    private lateinit var cancelBtn: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var recent: TextView
    private var busy = false
    private var isDownloading = false
    private var selectedIndex = 0
    private var analyzedLink = ""
    private var selectedTitle = "Видео"
    private var quickChoices = emptyList<FastYoutubeEngine.Choice>()
    private var slowHeights = emptyList<Int>()
    private var slowSizes = emptyMap<Int, YtDlpFallback.FormatSize>()
    private var slowBestSize: YtDlpFallback.FormatSize? = null
    private var displayedPercent = -1
    private var quick = false
    private var lastUri: Uri? = null
    private var lastAudio = false
    private var fastEngine: FastYoutubeEngine? = null
    private var fallback: YtDlpFallback? = null
    private val sourceChips = mutableListOf<Pair<String, TextView>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = dark
        window.navigationBarColor = dark
        window.decorView.systemUiVisibility = 0
        render()
        receiveSharedLink(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        receiveSharedLink(intent)
    }

    private fun receiveSharedLink(intent: Intent?) {
        val raw = if (intent?.action == Intent.ACTION_SEND) intent.getStringExtra(Intent.EXTRA_TEXT)
                  else if (intent?.action == Intent.ACTION_VIEW) intent.dataString else null
        if (raw != null) {
            val found = Regex("https://[^\\s]+", RegexOption.IGNORE_CASE).find(raw)?.value?.trimEnd('.', ')', ',') ?: raw
            linkInput.setText(found)
            linkInput.setSelection(linkInput.text.length)
            invalidateSelection()
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun bg(color: Int, radius: Int = 20, stroke: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            if (stroke != null) setStroke(dp(1), stroke)
        }
    private fun text(value: String, size: Float, color: Int = Color.WHITE, bold: Boolean = false): TextView =
        TextView(this).apply {
            this.text = value
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            includeFontPadding = false
        }
    private fun gap(height: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(height)) }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun button(label: String, filled: Boolean = true): Button = Button(this).apply {
        text = label
        textSize = 14f
        isAllCaps = false
        setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        setTextColor(Color.WHITE)
        background = bg(if (filled) accent else surface, 18, if (filled) null else outline)
        minHeight = dp(56)
    }

    private fun render() {
        val screen = ScrollView(this).apply {
            setBackgroundColor(dark)
            isFillViewport = true
            clipToPadding = false
        }
        val main = column().apply { setPadding(dp(24), dp(32), dp(24), dp(36)) }
        screen.addView(main)
        setContentView(screen)

        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val logo = ImageView(this).apply { setImageResource(R.drawable.offtube_launcher) }
        head.addView(logo, LinearLayout.LayoutParams(dp(44), dp(44)))
        val brand = text("offtube", 29f, Color.WHITE, true).apply { setPadding(dp(10), 0, 0, 0) }
        head.addView(brand)
        main.addView(head)
        main.addView(gap(36))
        main.addView(text("Видео всегда\nпод рукой.", 34f, Color.WHITE, true))
        main.addView(gap(12))
        main.addView(text("Скачивайте видео и аудио. Без лишних экранов.", 14f, muted))
        main.addView(gap(26))

        val sourceRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (source in listOf("YouTube", "RUTUBE", "VK Видео")) {
            val chip = text(source, 12f, muted, true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(9), dp(10), dp(9))
                background = bg(surface, 12, outline)
            }
            sourceChips.add(source to chip)
            val params = LinearLayout.LayoutParams(0, dp(35), 1f).apply { rightMargin = dp(if (source == "VK Видео") 0 else 7) }
            sourceRow.addView(chip, params)
        }
        main.addView(sourceRow)
        main.addView(gap(22))

        val card = column().apply {
            background = bg(surface, 23, outline)
            setPadding(dp(18), dp(20), dp(18), dp(20))
        }
        card.addView(text("ССЫЛКА НА ВИДЕО", 11f, muted, true))
        card.addView(gap(12))
        linkInput = EditText(this).apply {
            hint = "https://..."
            setSingleLine(true)
            textSize = 15f
            setTextColor(Color.WHITE)
            setHintTextColor(muted)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = bg(dark, 13, outline)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { invalidateSelection() }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        card.addView(linkInput, LinearLayout.LayoutParams(-1, dp(56)))
        card.addView(gap(12))
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val paste = text("▣  Вставить", 14f, accent, true).apply {
            gravity = Gravity.CENTER_VERTICAL
            setOnClickListener {
                val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val item = manager.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
                val value = item?.coerceToText(this@MainActivity)?.toString()
                if (!value.isNullOrBlank()) { linkInput.setText(value); invalidateSelection() }
            }
        }
        actions.addView(paste, LinearLayout.LayoutParams(0, dp(40), 1f))
        analyzeBtn = button("Найти видео", false)
        actions.addView(analyzeBtn, LinearLayout.LayoutParams(dp(143), dp(44)))
        analyzeBtn.setOnClickListener { analyze() }
        card.addView(actions)
        card.addView(gap(23))
        card.addView(text("ФОРМАТ И КАЧЕСТВО", 11f, muted, true))
        card.addView(gap(10))
        quality = text("⌄  Лучшее доступное · MP4 со звуком", 15f, Color.WHITE).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(6), dp(10), dp(6))
            background = bg(dark, 13, outline)
            setOnClickListener { showChoices() }
        }
        card.addView(quality, LinearLayout.LayoutParams(-1, dp(56)))
        main.addView(card)
        main.addView(gap(16))

        downloadBtn = button("↓   Скачать", true)
        main.addView(downloadBtn, LinearLayout.LayoutParams(-1, dp(60)))
        downloadBtn.setOnClickListener { startDownload() }
        main.addView(gap(18))
        val progressCard = column().apply {
            background = bg(surface, 20, outline)
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }
        val statusHeading = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        statusHeading.addView(text("СТАТУС", 11f, muted, true), LinearLayout.LayoutParams(0, -2, 1f))
        cancelBtn = text("Отменить", 12f, accent, true).apply {
            visibility = View.GONE
            setOnClickListener {
                fastEngine?.cancel()
                fallback?.cancel()
                status.text = "Отмена загрузки…"
            }
        }
        statusHeading.addView(cancelBtn)
        progressCard.addView(statusHeading)
        progressCard.addView(gap(11))
        title = text("Ссылка ещё не выбрана", 15f, Color.WHITE, true)
        progressCard.addView(title)
        progressCard.addView(gap(8))
        status = text("Вставьте ссылку и нажмите «Найти видео»", 13f, muted)
        progressCard.addView(status)
        progressCard.addView(gap(16))
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(accent)
            progressBackgroundTintList = ColorStateList.valueOf(outline)
            visibility = View.GONE
        }
        progressCard.addView(progressBar, LinearLayout.LayoutParams(-1, dp(5)))
        main.addView(progressCard)
        main.addView(gap(25))
        main.addView(text("ПОСЛЕДНИЕ ЗАГРУЗКИ", 11f, muted, true))
        main.addView(gap(9))
        recent = text(getPreferences(0).getString("recent", "Пока нет скачанных файлов") ?: "", 13f, muted)
        recent.setPadding(0, dp(4), 0, dp(10))
        recent.setOnClickListener { lastUri?.let { openFile(it, lastAudio) } }
        main.addView(recent)
        main.addView(gap(18))
        main.addView(text("Сохраняйте материалы, на загрузку которых у вас есть права. Защищённые видео не поддерживаются.", 11f, muted))
    }

    private fun invalidateSelection() {
        if (!::linkInput.isInitialized) return
        val selectedSource = when (SourceHosts.detect(linkInput.text.toString().trim())) {
            SourceHosts.Platform.YOUTUBE -> "YouTube"
            SourceHosts.Platform.RUTUBE -> "RUTUBE"
            SourceHosts.Platform.VK -> "VK Видео"
            else -> null
        }
        for ((name, chip) in sourceChips) {
            val active = name == selectedSource
            chip.setTextColor(if (active) accent else muted)
            chip.background = bg(if (active) Color.rgb(47, 22, 34) else surface, 12,
                if (active) accent else outline)
        }
        if (linkInput.text.toString().trim() == analyzedLink) return
        analyzedLink = ""
        selectedIndex = 0
        quick = false
        selectedTitle = "Видео"
        quickChoices = emptyList()
        slowHeights = emptyList()
        slowSizes = emptyMap()
        slowBestSize = null
        if (::quality.isInitialized) quality.text = "⌄  Лучшее доступное · MP4 со звуком"
    }

    private fun sizeLabel(size: YtDlpFallback.FormatSize?): String =
        if (size == null) " · размер неизвестен"
        else " · " + (if (size.approximate) "≈ " else "") +
            String.format(java.util.Locale.US, "%.1f МБ", size.bytes / 1048576.0)

    private fun showChoices() {
        val labels = when {
            quick -> quickChoices.map { it.label }
            slowHeights.isNotEmpty() -> listOf("Лучшее доступное · MP4 + звук" + sizeLabel(slowBestSize)) +
                slowHeights.map { "${it}p · MP4 + звук" + sizeLabel(slowSizes[it]) } +
                "Только аудио · MP3 · размер неизвестен"
            else -> listOf("Лучшее доступное · MP4 + звук", "Только аудио · MP3")
        }
        AlertDialog.Builder(this)
            .setTitle("Формат загрузки")
            .setSingleChoiceItems(labels.toTypedArray(), selectedIndex.coerceIn(0, labels.size - 1)) { d, index ->
                selectedIndex = index
                quality.text = "⌄  ${labels[index]}"
                d.dismiss()
            }.setNegativeButton("Отмена", null)
            .create().also { dialog ->
                dialog.setOnShowListener {
                    dialog.window?.setBackgroundDrawable(bg(surface, 18, outline))
                }
                dialog.show()
            }
    }

    private fun validLink(): Pair<String, SourceHosts.Platform>? {
        val url = linkInput.text.toString().trim()
        val platform = SourceHosts.detect(url)
        if (platform == null) {
            status.text = "Нужна HTTPS-ссылка YouTube, Rutube или VK Видео"
            return null
        }
        return url to platform
    }

    private fun setBusy(value: Boolean, downloading: Boolean = false) {
        busy = value
        isDownloading = downloading
        analyzeBtn.isEnabled = !value
        downloadBtn.isEnabled = !value
        linkInput.isEnabled = !value
        cancelBtn.visibility = if (downloading) View.VISIBLE else View.GONE
        if (!value) progressBar.visibility = View.GONE
    }

    private fun updateProgress(percent: Int, message: String) {
        runOnUiThread {
            progressBar.visibility = View.VISIBLE
            if (percent >= 0) {
                displayedPercent = maxOf(displayedPercent, percent.coerceIn(0, 99))
                progressBar.isIndeterminate = false
                progressBar.progress = displayedPercent
            } else if (displayedPercent < 0) {
                progressBar.isIndeterminate = true
            }
            // During merging or saving, keep the existing progress instead of starting again.
            status.text = message
        }
    }

    private fun analyze() {
        if (busy) return
        val (link, platform) = validLink() ?: return
        setBusy(true)
        title.text = "Получаем информацию"
        updateProgress(-1, "Проверяем доступные форматы…")
        thread(name = "offtube-inspect") {
            try {
                val fast = platform == SourceHosts.Platform.YOUTUBE
                var fastResult: FastYoutubeEngine.Video? = null
                var slowResult: YtDlpFallback.Info? = null
                if (fast) {
                    try {
                        val engine = FastYoutubeEngine()
                        fastResult = engine.inspect(link)
                        fastEngine = engine
                    } catch (_: Exception) {
                        slowResult = YtDlpFallback(this).also { fallback = it }.inspect(link)
                    }
                } else {
                    slowResult = YtDlpFallback(this).also { fallback = it }.inspect(link)
                }
                runOnUiThread {
                    analyzedLink = link
                    selectedIndex = 0
                    quick = fastResult != null
                    quickChoices = fastResult?.choices ?: emptyList()
                    slowHeights = slowResult?.heights ?: emptyList()
                    slowSizes = slowResult?.sizes ?: emptyMap()
                    slowBestSize = slowResult?.bestSize
                    selectedTitle = fastResult?.title ?: slowResult?.title ?: "Видео"
                    title.text = selectedTitle
                    quality.text = if (quickChoices.isNotEmpty()) "⌄  ${quickChoices[0].label}" else "⌄  Лучшее доступное · MP4 со звуком" + sizeLabel(slowBestSize)
                    status.text = if (quick) "Быстрый движок готов · ${quickChoices.size} форматов" else "Форматы получены · резервный движок"
                    setBusy(false)
                }
            } catch (e: Exception) {
                runOnUiThread { title.text = "Ошибка проверки"; status.text = explain(e); setBusy(false) }
            }
        }
    }

    private fun startDownload() {
        if (busy) return
        val (link, platform) = validLink() ?: return
        displayedPercent = -1
        setBusy(true, downloading = true)
        title.text = if (analyzedLink == link) selectedTitle else "Подключаемся к сервису…"
        updateProgress(-1, "Готовим загрузку…")
        thread(name = "offtube-download") {
            val directory = File(cacheDir, "offtube-${System.nanoTime()}").apply { mkdirs() }
            try {
                var option: FastYoutubeEngine.Choice? = null
                var audioOnly = false
                var fast = false
                var height: Int? = null
                if (platform == SourceHosts.Platform.YOUTUBE) {
                    if (analyzedLink == link && quickChoices.isNotEmpty()) {
                        fast = true
                        option = quickChoices.getOrNull(selectedIndex) ?: quickChoices[0]
                    } else {
                        try {
                            val engine = FastYoutubeEngine()
                            fastEngine = engine
                            val data = engine.inspect(link)
                            selectedTitle = data.title
                            option = data.choices.firstOrNull()
                            fast = option != null
                        } catch (_: Exception) { fast = false }
                    }
                }
                if (!fast) {
                    if (analyzedLink == link && !quick) {
                        audioOnly = selectedIndex == slowHeights.size + 1
                        height = slowHeights.getOrNull(selectedIndex - 1)
                    } else if (selectedIndex == 1) {
                        // Before inspection, the second option in the basic picker is MP3.
                        audioOnly = true
                    }
                }
                val result: File = if (fast && option != null) {
                    val eng = fastEngine ?: FastYoutubeEngine().also { fastEngine = it }
                    audioOnly = option.audioOnly
                    try {
                        eng.download(link, option, directory, ::updateProgress)
                    } catch (nativeError: Exception) {
                        if (nativeError.message.orEmpty().contains("отмен", true)) throw nativeError
                        updateProgress(-1, "Переключаемся на резервный способ…")
                        directory.listFiles()?.forEach { it.delete() }
                        val slow = fallback ?: YtDlpFallback(this).also { fallback = it }
                        try {
                            slow.download(link, option.height.takeIf { it > 0 }, audioOnly, directory, ::updateProgress)
                        } catch (fallbackError: Exception) {
                            val first = nativeError.message.orEmpty().take(120)
                            val second = fallbackError.message.orEmpty().take(160)
                            throw java.io.IOException("Быстрый способ: $first. Резервный: $second", fallbackError)
                        }
                    }
                } else {
                    val eng = fallback ?: YtDlpFallback(this).also { fallback = it }
                    val estimated = if (audioOnly || analyzedLink != link) null
                        else if (selectedIndex == 0) slowBestSize else height?.let { slowSizes[it] }
                    eng.download(link, height, audioOnly, directory, ::updateProgress, estimated)
                }
                updateProgress(-1, "Проверяем видео и звук…")
                SaveMedia.verify(result, audioOnly)
                updateProgress(-1, "Сохраняем в Download/OffTube…")
                val finalTitle = if (analyzedLink == link || fast) selectedTitle else result.nameWithoutExtension.substringBeforeLast('-').ifBlank { "Видео" }
                val uri = SaveMedia.save(this, result, finalTitle, audioOnly)
                runOnUiThread {
                    lastUri = uri
                    lastAudio = audioOnly
                    title.text = "Готово ✓"
                    status.text = "Файл сохранён · нажмите ниже, чтобы открыть"
                    progressBar.isIndeterminate = false
                    progressBar.progress = 100
                    recent.text = "↗  $finalTitle"
                    getPreferences(0).edit().putString("recent", "↗  $finalTitle").apply()
                    setBusy(false)
                    progressBar.visibility = View.VISIBLE
                }
            } catch (e: Exception) {
                runOnUiThread { title.text = "Не удалось скачать"; status.text = explain(e); setBusy(false) }
            } finally {
                directory.deleteRecursively()
            }
        }
    }

    private fun openFile(uri: Uri, audioOnly: Boolean) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, if (audioOnly) "audio/*" else "video/mp4")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (_: Exception) { status.text = "Откройте файл в приложении «Файлы»" }
    }

    private fun explain(e: Exception): String {
        val message = (e.cause?.message ?: e.message ?: "Неизвестная ошибка").take(200)
        return when {
            message.contains("403") -> "HTTP 403: сервер отклонил поток. Ссылку повторно проверили; возможны ограничения YouTube, VPN или отсутствие токена доступа. Попробуйте другое видео либо качество."
            message.contains("timed out", true) -> "Сервер не ответил вовремя. Проверьте VPN и интернет."
            message.contains("Unable to resolve host", true) || message.contains("name resolution", true) -> "Ошибка DNS. Проверьте VPN и подключение."
            message.contains("отмен", true) -> "Загрузка отменена"
            else -> message
        }
    }
}
