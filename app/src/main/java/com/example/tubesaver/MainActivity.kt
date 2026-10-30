package com.example.tubesaver

import android.app.Activity
import android.os.Bundle
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
 override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState)
  val root = LinearLayout(this).apply {
   orientation = LinearLayout.VERTICAL
   setPadding(36, 64, 36, 40)
   setBackgroundColor(Color.rgb(12,18,32))
  }
  setContentView(ScrollView(this).apply { addView(root); fillViewport = true; setBackgroundColor(Color.rgb(12,18,32)) })
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
  quality.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
   arrayOf("Лучшее качество", "1080p", "720p", "480p", "360p", "MP3"))
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
 private fun download() {
  val link = url.text.toString().trim()
  val uri = android.net.Uri.parse(link)
  if (uri.scheme != "https" || !isSupportedHost(uri.host)) {
   status.text = "Введите HTTPS-ссылку YouTube, VK Видео или RUTUBE"; return
  }
  val selected = quality.selectedItemPosition
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
    if (selected == 5) {
     req.addOption("-x"); req.addOption("--audio-format", "mp3")
    } else {
     val h = when(selected) { 1->1080; 2->720; 3->480; 4->360; else->null }
     req.addOption("-f", if (h == null) "bv*+ba/b" else "bv*[height<=${h}]+ba/b[height<=${h}]")
     req.addOption("--merge-output-format", "mp4")
    }
    YoutubeDL.getInstance().execute(req, "tube-${System.nanoTime()}") { p, _, _ ->
     runOnUiThread { progress.progress = p.toInt().coerceIn(0,100); status.text = "Загрузка: ${p.toInt()}%" }
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
