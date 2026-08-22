import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * Номер сборки: на CI это номер прогона, локально — 0.
 *
 * Благодаря этому versionCode у сборки с Actions всегда выше локальной,
 * и Android не отказывается ставить её поверх.
 */
val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "").toIntOrNull() ?: 0

/** Короткий хеш коммита: читаем из окружения CI, иначе прямо из .git. */
fun gitShortSha(): String {
    System.getenv("GITHUB_SHA")?.take(7)?.takeIf { it.isNotEmpty() }?.let { return it }
    val gitDir = File(rootDir.parentFile, ".git")
    return try {
        val head = File(gitDir, "HEAD").readText().trim()
        val sha = if (head.startsWith("ref:")) {
            val ref = head.removePrefix("ref:").trim()
            val direct = File(gitDir, ref)
            if (direct.exists()) direct.readText().trim()
            else File(gitDir, "packed-refs").readLines()
                .firstOrNull { it.endsWith(" $ref") }?.substringBefore(' ') ?: ""
        } else head
        sha.take(7).ifEmpty { "unknown" }
    } catch (e: Exception) {
        "unknown"
    }
}

android {
    namespace = "com.shiftplayer"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.shiftplayer"
        minSdk = 21
        targetSdk = 34
        // Каждая сборка с CI новее предыдущей: иначе Android считает их
        // одной версией и отказывается обновлять приложение на телевизоре.
        versionCode = if (buildNumber > 0) 1000 + buildNumber else 1
        versionName = if (buildNumber > 0) "1.0.$buildNumber" else "1.0.0-local"

        buildConfigField("String", "GIT_SHA", "\"${gitShortSha()}\"")
    }

    buildFeatures {
        // В AGP 8 BuildConfig по умолчанию не генерируется.
        buildConfig = true
    }

    buildTypes {
        release {
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
}

dependencies {
    val media3 = "1.3.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-exoplayer-dash:$media3")
    implementation("androidx.media3:media3-exoplayer-rtsp:$media3")
    implementation("androidx.media3:media3-exoplayer-smoothstreaming:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.core:core-ktx:1.12.0")
}
