package com.example.tubesaver

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.view.View
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextWatcher
import android.text.Editable
import org.json.JSONObject
import android.os.Environment
import android.os.Build
import android.content.ContentValues
import android.provider.MediaStore
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.content.res.ColorStateList
import android.content.Context
import android.view.Gravity
import com.yausername.youtubedl_android.YoutubeDL.UpdateChannel
import android.view.ViewGroup
import android.widget.*
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : Activity() {
 private lateinit var url: EditText
 private lateinit var quality: Spinner
 private lateinit var status: TextView
 private lateinit var button: Button
 private lateinit var progress: ProgressBar
 private lateinit var qualityPicker: TextView
 private val handler = Handler(Looper.getMainLooper())
 @Volatile private var engineReady = false
 @Volatile private var engineError: String? = null
 private val engineLock = Any()
 private var lastTransfer = ""
 private var downloading = false
 private var checkedAt = 0L
 private var checkStartedAt = 0L
 private var pending: Runnable? = null
 private var generation = 0
 private var heights = listOf<Int>()
 private var readyLink = ""
 private val choices = mutableListOf<Pair<String, Int?>>()
 override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState)
  val root = LinearLayout(this).apply {
   orientation = LinearLayout.VERTICAL
   setPadding(42, 74, 42, 64)
   setBackgroundColor(Color.rgb(8,13,23))
  }
  setContentView(ScrollView(this).apply { addView(root); isFillViewport = true; setBackgroundColor(Color.rgb(8,13,23)) })
  window.statusBarColor = Color.rgb(8,13,23)
  window.navigationBarColor = Color.rgb(8,13,23)
  fun label(s: String, size: Float = 16f): TextView = TextView(this).apply {
   text = s; textSize = size; setTextColor(Color.WHITE); setPadding(0,12,0,12)
  }
  val hero = label("◉  OFFTUBE", 32f).apply { setTypeface(null, Typeface.BOLD); setTextColor(Color.rgb(241,244,255)) }
  root.addView(hero)
  root.addView(label("YOUR MEDIA. OFFLINE.", 12f).apply { setTextColor(Color.rgb(89,215,189)) })
  val intro = label("Сохраняй. Смотри. Повторяй.", 23f).apply { setTypeface(null, Typeface.BOLD) }
  root.addView(intro)
  root.addView(label("YouTube   /   VK Видео   /   RUTUBE", 14f).apply { setTextColor(Color.rgb(160,177,218)) })
  url = EditText(this).apply {
   hint = "Вставьте ссылку на видео"
   setSingleLine(true); setTextColor(Color.WHITE); setHintTextColor(Color.LTGRAY)
   background = GradientDrawable().apply { setColor(Color.rgb(29,39,61)); cornerRadius = 24f; setStroke(2, Color.rgb(75,92,128)) }
   setPadding(24,20,24,20)
  }
  root.addView(url, LinearLayout.LayoutParams(-1, 120))
  url.addTextChangedListener(object : TextWatcher {
   override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
   override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { scheduleCheck() }
   override fun afterTextChanged(s: Editable?) {}
  })
  val paste = label("Вставить из буфера обмена", 14f)
  paste.setTextColor(Color.rgb(155,177,255))
  paste.setPadding(0,20,0,25)
  paste.setOnClickListener {
   val clip = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
   clip.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.let { url.setText(it) }
  }
  root.addView(paste)
  root.addView(label("КАЧЕСТВО", 12f))
  quality = Spinner(this)
  setChoices(emptyList())
  quality.visibility = View.GONE
  root.addView(quality)
  qualityPicker = label("⌄  Вставьте ссылку, чтобы выбрать качество", 16f).apply {
   setTextColor(Color.WHITE)
   background = popupShape(Color.rgb(31,43,70), 18, Color.rgb(82,103,158))
   setPadding(30,26,30,26)
   setOnClickListener { showQualityPopup() }
  }
  root.addView(qualityPicker, LinearLayout.LayoutParams(-1, -2))
  button = Button(this).apply { text = "↓  СОХРАНИТЬ ОФЛАЙН"; setTextColor(Color.WHITE); backgroundTintList = ColorStateList.valueOf(Color.rgb(27,180,155)) }
  root.addView(button, LinearLayout.LayoutParams(-1, 120))
  button.backgroundTintList = ColorStateList.valueOf(Color.rgb(27,180,155))
  root.addView(label("●  ПРОЦЕСС", 12f).apply { setTextColor(Color.rgb(169,184,222)) })
  progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
  root.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
  status = label("Готово к загрузке", 15f)
  root.addView(status)
  button.setOnClickListener { download() }
  val update = Button(this).apply { text = "Проверить обновление загрузчика"; setTextColor(Color.WHITE); backgroundTintList = ColorStateList.valueOf(Color.rgb(47,60,86)) }
  root.addView(update)
  update.setOnClickListener {
   update.isEnabled = false
   status.text = "Проверка обновления..."
   thread {
    try { ensureEngine(); YoutubeDL.getInstance().updateYoutubeDL(applicationContext, UpdateChannel.STABLE); runOnUiThread { status.text = "Обновление проверено" } }
    catch (e: Exception) { runOnUiThread { status.text = "Не удалось обновить. Проверьте интернет." } }
    finally { runOnUiThread { update.isEnabled = true } }
   }
  }
  root.addView(label("Сохраняйте только материалы, на которые у вас есть права.", 12f).apply { setTextColor(Color.rgb(150,162,190)) })
  progress.visibility = View.GONE
  thread { try { ensureEngine() } catch (_: Exception) {} }
 }
 private fun ensureEngine() {
  if (engineReady) return
  synchronized(engineLock) {
   if (!engineReady) {
    try { YoutubeDL.getInstance().init(applicationContext); engineReady = true; engineError = null }
    catch (e: Exception) { engineError = e.message; throw e }
   }
  }
 }
 private fun popupShape(color: Int, radius: Int, stroke: Int): GradientDrawable =
  GradientDrawable().apply {
   setColor(color)
   cornerRadius = (resources.displayMetrics.density * radius)
   setStroke((resources.displayMetrics.density * 1.5f).toInt(), stroke)
  }
 private fun showQualityPopup() {
  if (readyLink != url.text.toString().trim() || heights.isEmpty()) {
   AlertDialog.Builder(this).setTitle("Качество пока недоступно")
    .setMessage("Вставьте ссылку и дождитесь проверки форматов видео.")
    .setPositiveButton("Понятно", null).show()
   return
  }
  val options = choices.map { it.first }.toTypedArray()
  AlertDialog.Builder(this)
   .setTitle("Выберите качество")
   .setSingleChoiceItems(options, quality.selectedItemPosition.coerceAtLeast(0)) { dialog, index ->
    quality.setSelection(index)
    qualityPicker.text = "⌄  " + options[index]
    dialog.dismiss()
   }
   .setNegativeButton("Отмена", null)
   .show()
 }
 private fun setChoices(values: List<Int>) {
  heights = values
  choices.clear()
  if (values.isNotEmpty()) {
   choices.add("Лучшее доступное качество" to null)
   values.forEach { choices.add("${it}p" to it) }
   choices.add("Аудио MP3" to -1)
  } else {
   choices.add("Сначала вставьте ссылку" to null)
  }
  quality.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, choices.map { it.first })
  if (::qualityPicker.isInitialized) qualityPicker.text = if (values.isEmpty()) "⌄  Ожидание проверки видео" else "⌄  Лучшее доступное качество"
 }
 private fun scheduleCheck() {
  generation++
  val current = generation
  pending?.let { handler.removeCallbacks(it) }
  readyLink = ""
  setChoices(emptyList())
  val link = url.text.toString().trim()
  if (link.isEmpty()) { status.text = "Вставьте ссылку для проверки качества"; return }
  val uri = android.net.Uri.parse(link)
  if (uri.scheme != "https" || !isSupportedHost(uri.host)) { status.text = "Нужна HTTPS-ссылка YouTube, VK Видео или RUTUBE"; return }
  status.text = "Ссылку можно скачивать сразу. Проверяем качество в фоне..."
  val task = Runnable {
   checkStartedAt = android.os.SystemClock.elapsedRealtime()
   status.text = "Проверяем доступные форматы..."
   thread {
    try {
     ensureEngine()
     val req = YoutubeDLRequest(link)
     req.addOption("--dump-single-json")
     req.addOption("--skip-download")
     req.addOption("--no-playlist")
     req.addOption("--no-warnings")
     req.addOption("--socket-timeout", "20")
     req.addOption("--retries", "2")
     req.addOption("--extractor-retries", "2")
    req.addOption("--extractor-args", "youtube:player_client=android")
     val output = YoutubeDL.getInstance().execute(req).out
     val data = JSONObject(output.substring(output.indexOf('{')))
     val formats = data.optJSONArray("formats")
     val available = sortedSetOf<Int>(compareByDescending { it })
     if (formats != null) for (i in 0 until formats.length()) {
      val fmt = formats.optJSONObject(i) ?: continue
      val height = fmt.optInt("height", 0)
      if (height > 0 && fmt.optString("vcodec", "none") != "none") available.add(height)
     }
     if (available.isEmpty()) data.optInt("height", 0).takeIf { it > 0 }?.let { available.add(it) }
     runOnUiThread {
      if (current != generation) return@runOnUiThread
      if (available.isEmpty()) { status.text = "Не удалось определить качество видео"; return@runOnUiThread }
      readyLink = link
      checkedAt = android.os.SystemClock.elapsedRealtime()
      setChoices(available.toList())
      status.text = "Найдено: ${data.optString("title", "Без названия")}. Доступно: ${available.joinToString { "${it}p" }}"
      qualityPicker.animate().scaleX(1.03f).scaleY(1.03f).setDuration(140).withEndAction { qualityPicker.animate().scaleX(1f).scaleY(1f).setDuration(140).start() }.start()
     }
    } catch (e: Exception) {
     runOnUiThread { if (current == generation) status.text = friendlyError(e.message.orEmpty()) }
    }
   }
  }
  pending = task
  handler.postDelayed(task, 450)
 }
 private fun download() {
  val link = url.text.toString().trim()
  val uri = android.net.Uri.parse(link)
  if (uri.scheme != "https" || !isSupportedHost(uri.host)) {
   status.text = "Введите HTTPS-ссылку YouTube, VK Видео или RUTUBE"; return
  }
  // Metadata is optional: start downloading immediately using the best available format.
  if (downloading) return
  downloading = true
  val selected = if (readyLink == link) choices.getOrNull(quality.selectedItemPosition)?.second else null
  button.isEnabled = false
  status.text = "Получаем потоки видео и звука..."
  val startTime = android.os.SystemClock.elapsedRealtime()
  progress.visibility = View.VISIBLE
  progress.isIndeterminate = true
  progress.progress = 0
  lastTransfer = ""
  thread {
   try {
    ensureEngine()
    val dir = File(cacheDir, "downloads").apply { mkdirs() }
    dir.listFiles()?.forEach { it.delete() }
    val req = YoutubeDLRequest(link)
    req.addOption("--no-playlist")
    req.addOption("--no-warnings")
    req.addOption("--continue")
    req.addOption("--socket-timeout", "25")
    req.addOption("--retries", "2")
    req.addOption("--fragment-retries", "3")
    req.addOption("--extractor-retries", "2")
    req.addOption("-P", dir.absolutePath)
    req.addOption("-o", "%(title).100s-%(id)s.%(ext)s")
    req.addOption("--newline")
    req.addOption("--progress-template", "download:TSBYTES:%(progress.downloaded_bytes)s|%(progress.total_bytes)s|%(progress.total_bytes_estimate)s|%(progress.speed)s|%(progress.eta)s")
    if (selected == -1) {
     req.addOption("-x"); req.addOption("--audio-format", "mp3")
    } else {
     val h = selected
     val cap = if (h == null) "" else "[height<=${h}]"
     req.addOption("-f", "bv[ext=mp4][vcodec^=avc1]${cap}+ba[ext=m4a]/b[ext=mp4][acodec!=none]${cap}/bv${cap}+ba/b[acodec!=none]${cap}")
     req.addOption("--merge-output-format", "mp4")
     req.addOption("--remux-video", "mp4")
    }
    val onProgress: (Float, Long, String) -> Unit = { p, _, line ->
     val info = transferInfo(line)
     runOnUiThread {
      if (info != null) {
       lastTransfer = info
       progress.isIndeterminate = false
       progress.progress = p.toInt().coerceIn(0,100)
       status.text = "Скачивание: ${p.toInt().coerceIn(0,100)}%\n$info"
      } else if (line.contains("Merger", true) || line.contains("VideoConvertor", true) || line.contains("Fixup", true)) {
       progress.isIndeterminate = true
       status.text = "Обработка и сохранение MP4..."
      } else if (line.contains("Destination:", true) || line.contains("[download]", true)) {
       status.text = if (lastTransfer.isEmpty()) "Соединение установлено, ожидаем данные..." else "Скачивание...\n$lastTransfer"
      } else if (lastTransfer.isEmpty()) {
       val elapsed = (android.os.SystemClock.elapsedRealtime() - startTime) / 1000
       status.text = "Ожидаем ответ сервера и аудиопоток... ${elapsed} с"
      }
     }
    }
    try {
     YoutubeDL.getInstance().execute(req, "tube-${System.nanoTime()}", onProgress)
    } catch (first: Exception) {
     val msg = first.message.orEmpty()
     val host = android.net.Uri.parse(link).host.orEmpty().lowercase()
     val youtube = host == "youtu.be" || host == "youtube.com" || host.endsWith(".youtube.com")
     if (!youtube || !(msg.contains("403") || msg.contains("Sign in", true) || msg.contains("Requested format", true))) throw first
     runOnUiThread { progress.isIndeterminate = true; status.text = "YouTube отклонил запрос. Пробуем другой клиент..." }
     dir.listFiles()?.forEach { it.delete() }
     val fallback = YoutubeDLRequest(link)
     fallback.addOption("--no-playlist")
     fallback.addOption("--socket-timeout", "25")
     fallback.addOption("--retries", "2")
     fallback.addOption("--extractor-args", "youtube:player_client=web_safari")
     fallback.addOption("-P", dir.absolutePath)
     fallback.addOption("-o", "%(title).100s-%(id)s.%(ext)s")
     fallback.addOption("--newline")
     fallback.addOption("--progress-template", "download:TSBYTES:%(progress.downloaded_bytes)s|%(progress.total_bytes)s|%(progress.total_bytes_estimate)s|%(progress.speed)s|%(progress.eta)s")
     if (selected == -1) {
      fallback.addOption("-x")
      fallback.addOption("--audio-format", "mp3")
     } else {
      val cap = if (selected == null) "" else "[height<=${selected}]"
      fallback.addOption("-f", "b[ext=mp4][acodec!=none]${cap}/b[acodec!=none]${cap}")
      fallback.addOption("--remux-video", "mp4")
     }
     try {
      YoutubeDL.getInstance().execute(fallback, "tube-fallback-${System.nanoTime()}", onProgress)
     } catch (second: Exception) {
      throw IllegalStateException("Основной: ${msg.take(140)}; резервный: ${second.message.orEmpty().take(180)}", second)
     }
    }
    runOnUiThread { status.text = "Проверяем результат и сохраняем файл..." }
    val result = dir.listFiles()?.filter { it.isFile && (if (selected == -1) it.extension.equals("mp3", true) else it.extension.equals("mp4", true)) }?.maxByOrNull { it.lastModified() }
     ?: error("Файл не найден")
    val clean = if (result.name.endsWith(".webm.mp4", true)) File(result.parentFile, result.name.removeSuffix(".webm.mp4") + ".mp4").also { if (!result.renameTo(it)) error("Не удалось переименовать файл") } else result
    if (clean.length() == 0L) error("Загруженный файл пуст")
    if (selected != -1) verifyAudioTrack(clean)
    save(clean)
    clean.delete()
    runOnUiThread { progress.isIndeterminate = false; progress.progress = 100; status.text = "Сохранено: ${clean.name}" }
   } catch (e: Exception) {
    runOnUiThread { progress.isIndeterminate = false; status.text = friendlyError(e.message.orEmpty()) }
   } finally {
    runOnUiThread { downloading = false; button.isEnabled = true }
   }
  }
 }
 private fun verifyAudioTrack(file: File) {
  val retriever = android.media.MediaMetadataRetriever()
  try {
   retriever.setDataSource(file.absolutePath)
   val audio = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)
   val video = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)
   if (audio != "yes" || video != "yes") error("Файл без видео или звуковой дорожки. Сохранение отменено.")
  } finally { retriever.release() }
 }
 private fun transferInfo(line: String): String? {
  val raw = line.substringAfter("TSBYTES:", "")
  if (raw.isEmpty()) return null
  val parts = raw.trim().split("|")
  if (parts.size < 5) return null
  fun num(s: String) = s.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
  val done = num(parts[0]) ?: return null
  val total = num(parts[1]) ?: num(parts[2])
  val speed = num(parts[3])
  val eta = num(parts[4])
  fun mb(v: Double) = String.format(Locale.US, "%.1f МБ", v / 1048576.0)
  return mb(done) + (total?.let { " из " + mb(it) } ?: " (размер неизвестен)") +
   (speed?.let { " • " + mb(it) + "/с" } ?: "") +
   (eta?.let { " • осталось ~" + it.toLong() / 60 + "м " + it.toLong() % 60 + "с" } ?: "")
 }
 private fun isSupportedHost(raw: String?): Boolean {
  val host = raw?.lowercase() ?: return false
  return listOf("youtube.com","youtu.be","vk.com","vkvideo.ru","vk.ru","rutube.ru").any { host == it || host.endsWith("."+it) }
 }
 private fun friendlyError(message: String): String = when {
  message.contains("No address associated with hostname", true) || message.contains("name resolution", true) -> "Ошибка DNS: сервер недоступен. Проверьте интернет, VPN и DNS. Попробуйте открыть ссылку в браузере."
  message.contains("403") -> "Ошибка доступа 403. " + message.takeLast(210)
  message.contains("Unsupported URL", true) -> "Движок пока не поддерживает эту ссылку."
  message.contains("Sign in", true) -> "YouTube запросил авторизацию или проверку клиента. " + message.takeLast(180)
  message.contains("Requested format", true) -> "Нет подходящего видео со звуком. Попробуйте другое качество."
  message.contains("timed out", true) || message.contains("timeout", true) -> "Сервер не ответил вовремя. Повторите попытку."
  message.contains("429") -> "Сервер ограничил запросы (429). Повторите позже."
  message.contains("Файл без видео") -> message
  else -> "Не удалось скачать видео: " + message.take(180)
 }
 private fun save(file: File) {
  if (Build.VERSION.SDK_INT < 29) {
   val dest = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: error("Нет папки")
   file.copyTo(File(dest,file.name),overwrite=true); return
  }
  val values = ContentValues().apply {
   put(MediaStore.MediaColumns.DISPLAY_NAME,file.name)
   put(MediaStore.MediaColumns.MIME_TYPE,if(file.extension=="mp3") "audio/mpeg" else "video/mp4")
   put(MediaStore.MediaColumns.RELATIVE_PATH,"Download/TubeSaver")
   put(MediaStore.MediaColumns.IS_PENDING,1)
  }
  val dest = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values) ?: error("Не удалось создать файл")
  try {
   contentResolver.openOutputStream(dest)?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: error("Нет доступа к файлу")
   contentResolver.update(dest,ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING,0) },null,null)
  } catch(e: Exception) { contentResolver.delete(dest,null,null); throw e }
 }
}
