package com.example.tubesaver

import android.app.Activity
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
import kotlin.concurrent.thread

class MainActivity : Activity() {
 private lateinit var url: EditText
 private lateinit var quality: Spinner
 private lateinit var status: TextView
 private lateinit var button: Button
 private lateinit var progress: ProgressBar
 private val handler = Handler(Looper.getMainLooper())
 private var pending: Runnable? = null
 private var generation = 0
 private var heights = listOf<Int>()
 private var readyLink = ""
 private val choices = mutableListOf<Pair<String, Int?>>()
 override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState)
  val root = LinearLayout(this).apply {
   orientation = LinearLayout.VERTICAL
   setPadding(36, 110, 36, 40)
   setBackgroundColor(Color.rgb(12,18,32))
  }
  setContentView(ScrollView(this).apply { addView(root); isFillViewport = true; setBackgroundColor(Color.rgb(12,18,32)) })
  window.statusBarColor = Color.rgb(12,18,32)
  window.navigationBarColor = Color.rgb(12,18,32)
  fun label(s: String, size: Float = 16f): TextView = TextView(this).apply {
   text = s; textSize = size; setTextColor(Color.WHITE); setPadding(0,12,0,12)
  }
  root.addView(label("↓  TubeSaver", 29f))
  root.addView(label("ВИДЕО И АУДИО НА ТЕЛЕФОНЕ", 12f))
  root.addView(label("Скачать видео", 24f))
  root.addView(label("YouTube  •  VK Видео  •  RUTUBE", 15f))
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
  quality.setBackgroundColor(Color.rgb(230,235,250))
  root.addView(quality, LinearLayout.LayoutParams(-1, 110))
  button = Button(this).apply { text = "↓  СКАЧАТЬ НА ТЕЛЕФОН"; setTextColor(Color.WHITE); backgroundTintList = ColorStateList.valueOf(Color.rgb(94,112,245)) }
  root.addView(button, LinearLayout.LayoutParams(-1, 120))
  root.addView(label("СТАТУС ЗАГРУЗКИ", 12f))
  progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
  root.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
  status = label("Готово к загрузке", 15f)
  root.addView(status)
  button.setOnClickListener { download() }
  val update = Button(this).apply { text = "Обновить движок yt-dlp"; setTextColor(Color.WHITE); backgroundTintList = ColorStateList.valueOf(Color.rgb(47,60,86)) }
  root.addView(update)
  update.setOnClickListener {
   update.isEnabled = false
   status.text = "Проверка обновления..."
   thread {
    try { YoutubeDL.getInstance().init(applicationContext); YoutubeDL.getInstance().updateYoutubeDL(applicationContext, UpdateChannel.STABLE); runOnUiThread { status.text = "Обновление проверено" } }
    catch (e: Exception) { runOnUiThread { status.text = "Не удалось обновить. Проверьте интернет." } }
    finally { runOnUiThread { update.isEnabled = true } }
   }
  }
  root.addView(label("Сохраняйте только материалы, на которые у вас есть права.", 12f))
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
  status.text = "Ожидание проверки ссылки..."
  val task = Runnable {
   status.text = "Проверяем доступные форматы..."
   thread {
    try {
     YoutubeDL.getInstance().init(applicationContext)
     val req = YoutubeDLRequest(link)
     req.addOption("--dump-single-json")
     req.addOption("--skip-download")
     req.addOption("--no-playlist")
     req.addOption("--no-warnings")
     req.addOption("--socket-timeout", "15")
     req.addOption("--retries", "1")
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
      setChoices(available.toList())
      status.text = "Найдено видео: ${data.optString("title", "Без названия")}. Доступно: ${available.joinToString { "${it}p" }}"
     }
    } catch (e: Exception) {
     runOnUiThread { if (current == generation) status.text = friendlyError(e.message.orEmpty()) }
    }
   }
  }
  pending = task
  handler.postDelayed(task, 850)
 }
 private fun download() {
  val link = url.text.toString().trim()
  val uri = android.net.Uri.parse(link)
  if (uri.scheme != "https" || !isSupportedHost(uri.host)) {
   status.text = "Введите HTTPS-ссылку YouTube, VK Видео или RUTUBE"; return
  }
  if (readyLink != link || choices.isEmpty()) { status.text = "Сначала дождитесь проверки доступного качества"; return }
  val selected = choices.getOrNull(quality.selectedItemPosition)?.second
  button.isEnabled = false
  status.text = "Подготовка движка..."
  progress.progress = 0
  thread {
   try {
    YoutubeDL.getInstance().init(applicationContext)
    val dir = File(cacheDir, "downloads").apply { mkdirs() }
    dir.listFiles()?.forEach { it.delete() }
    val req = YoutubeDLRequest(link)
    req.addOption("--no-playlist")
    req.addOption("--no-warnings")
    req.addOption("--socket-timeout", "20")
    req.addOption("--retries", "2")
    req.addOption("-P", dir.absolutePath)
    req.addOption("-o", "%(title).100s-%(id)s.%(ext)s")
    if (selected == -1) {
     req.addOption("-x"); req.addOption("--audio-format", "mp3")
    } else {
     val h = selected
     req.addOption("-f", if (h == null) "bv*+ba/b" else "bv*[height<=${h}]+ba/b[height<=${h}]")
     req.addOption("--merge-output-format", "mp4")
    }
    YoutubeDL.getInstance().execute(req, "tube-${System.nanoTime()}") { p, _, _ ->
     runOnUiThread { progress.progress = p.toInt().coerceIn(0,100); status.text = "Загрузка: ${p.toInt().coerceIn(0,100)}%" }
    }
    val result = dir.listFiles()?.filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }?.maxByOrNull { it.lastModified() }
     ?: error("Файл не найден")
    save(result)
    result.delete()
    runOnUiThread { progress.progress = 100; status.text = "Сохранено: ${result.name}" }
   } catch (e: Exception) {
    runOnUiThread { status.text = friendlyError(e.message.orEmpty()) }
   } finally {
    runOnUiThread { button.isEnabled = true }
   }
  }
 }
 private fun isSupportedHost(raw: String?): Boolean {
  val host = raw?.lowercase() ?: return false
  return listOf("youtube.com","youtu.be","vk.com","vkvideo.ru","vk.ru","rutube.ru").any { host == it || host.endsWith("."+it) }
 }
 private fun friendlyError(message: String): String = when {
  message.contains("No address associated with hostname", true) || message.contains("name resolution", true) -> "Ошибка DNS: сервер недоступен. Проверьте интернет, VPN и DNS. Попробуйте открыть ссылку в браузере."
  message.contains("403") -> "Нет доступа к видео (403). Возможно, требуется авторизация."
  message.contains("Unsupported URL", true) -> "Движок пока не поддерживает эту ссылку."
  message.contains("Sign in", true) -> "Для этого видео нужна авторизация."
  else -> "Не удалось скачать видео. Проверьте ссылку и попробуйте обновить движок."
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
