package com.example.tubesaver

import android.app.Activity
import android.os.Bundle
import android.os.Environment
import android.os.Build
import android.content.ContentValues
import android.provider.MediaStore
import android.graphics.Color
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
   setPadding(32, 48, 32, 32)
   setBackgroundColor(Color.rgb(15,20,35))
  }
  setContentView(ScrollView(this).apply { addView(root) })
  fun label(s: String, size: Float = 16f): TextView = TextView(this).apply {
   text = s; textSize = size; setTextColor(Color.WHITE); setPadding(0,12,0,12)
  }
  root.addView(label("▶ TubeSaver", 30f))
  root.addView(label("Сохраняйте только видео, на которые у вас есть права.", 14f))
  url = EditText(this).apply {
   hint = "https://youtube.com/watch?v=..."
   setSingleLine(true); setTextColor(Color.WHITE); setHintTextColor(Color.LTGRAY)
  }
  root.addView(url)
  quality = Spinner(this)
  quality.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
   arrayOf("Лучшее качество", "1080p", "720p", "480p", "360p", "MP3"))
  root.addView(quality)
  button = Button(this).apply { text = "Скачать на телефон" }
  root.addView(button)
  progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
  root.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
  status = label("Готово к загрузке")
  root.addView(status)
  button.setOnClickListener { download() }
 }
 private fun download() {
  val link = url.text.toString().trim()
  val uri = android.net.Uri.parse(link)
  if (uri.scheme != "https" || uri.host?.lowercase() !in setOf("youtube.com","www.youtube.com","m.youtube.com","youtu.be")) {
   status.text = "Введите HTTPS-ссылку YouTube"; return
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
    runOnUiThread { status.text = "Ошибка: ${e.message}" }
   } finally {
    runOnUiThread { button.isEnabled = true }
   }
  }
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
