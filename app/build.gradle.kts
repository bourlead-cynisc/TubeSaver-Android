plugins {
 id("com.android.application")
 id("org.jetbrains.kotlin.android")
}
android {
 namespace = "com.example.tubesaver"
 compileSdk = 35
 defaultConfig {
  applicationId = "com.example.tubesaver"
  minSdk = 26
  targetSdk = 35
  versionCode = 1
  versionName = "1.0"
 }
}
dependencies {
 implementation("com.github.yausername.youtubedl-android:library:0.17.1")
 implementation("com.github.yausername.youtubedl-android:ffmpeg:0.17.1")
}
