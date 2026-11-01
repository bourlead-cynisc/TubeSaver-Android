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
        versionCode = 9
        versionName = "2.5.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // Fast YouTube resolver; distributed under GPLv3+. Keep attribution.
    implementation("com.github.teamnewpipe:NewPipeExtractor:v0.26.5")
    // Compatibility fallback for Rutube, VK Video and certain YouTube streams.
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")
    testImplementation("junit:junit:4.13.2")
}
