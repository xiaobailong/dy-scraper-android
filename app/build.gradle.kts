import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

val versionProps = Properties()
val versionFile = rootProject.file("version.properties")
if (versionFile.exists()) {
    versionProps.load(versionFile.inputStream())
}
val vCode = (versionProps.getProperty("versionCode") ?: "1").toInt()
val vName = versionProps.getProperty("versionName") ?: "1.0.0"

val buildTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
val gitCommit = try {
    val process = ProcessBuilder("git", "rev-parse", "--short", "HEAD")
        .directory(rootProject.projectDir)
        .redirectErrorStream(true)
        .start()
    val out = process.inputStream.bufferedReader().use { it.readText() }.trim()
    process.waitFor()
    out.ifBlank { "unknown" }
} catch (e: Exception) {
    "unknown"
}

android {
    namespace = "com.dy.scraper"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.dy.scraper"
        minSdk = 26
        targetSdk = 34
        versionCode = vCode
        versionName = vName

        buildConfigField("String", "BUILD_TIME", "\"$buildTime\"")
        buildConfigField("String", "GIT_COMMIT", "\"$gitCommit\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    // AndroidX
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.webkit:webkit:1.9.0")
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // Room (SQLite ORM)
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // OkHttp (network download)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Gson (JSON parsing)
    implementation("com.google.code.gson:gson:2.10.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-service:2.7.0")
}