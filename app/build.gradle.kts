plugins {
 id("com.android.application")
 id("org.jetbrains.kotlin.android")
}
android {
 namespace = "com.example.tubesaver"
 compileSdk = 35
 compileOptions {
  sourceCompatibility = JavaVersion.VERSION_17
  targetCompatibility = JavaVersion.VERSION_17
 }
 kotlinOptions { jvmTarget = "17" }
 defaultConfig {
  applicationId = "com.example.tubesaver"
  minSdk = 26
  targetSdk = 35
  versionCode = 1
  versionName = "1.0"
 }
}
dependencies {
 implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
 implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")
}
