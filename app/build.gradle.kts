plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

import java.io.FileInputStream
import java.util.Properties

System.getenv("STEPDADDY_ISOLATED_BUILD_DIR")?.trim()?.takeIf { it.isNotEmpty() }?.let { dir ->
    layout.buildDirectory.set(file(dir))
}

val ktorVersion = "2.3.12"

val gitHash: String = runCatching {
    providers.exec {
        commandLine("git", "rev-parse", "--short", "HEAD")
    }.standardOutput.asText.get().trim()
}.getOrDefault("unknown")

val buildTime: Long = System.currentTimeMillis()

val stepdaddyVersionFile = sequenceOf(
    rootProject.file("STEPDADDY_VERSION"),
    rootProject.file("../STEPDADDY_VERSION"),
).firstOrNull { it.isFile } ?: rootProject.file("STEPDADDY_VERSION")
fun readStepdaddyVersionProp(name: String, default: String): String {
    if (!stepdaddyVersionFile.isFile) return default
    return stepdaddyVersionFile.readLines()
        .map { it.trim() }
        .firstOrNull { it.startsWith("$name=") }
        ?.substringAfter("=")
        ?.trim()
        ?: default
}
val stepdaddyVersionName = readStepdaddyVersionProp("STEPDADDY_VERSION", "3.0.14")
val stepdaddyVersionCode = readStepdaddyVersionProp("VERSION_CODE", "30014").toInt()

val localProps = Properties()
val localPropsFile = rootProject.file("local.properties")
if (localPropsFile.isFile) {
    localPropsFile.inputStream().use { localProps.load(it) }
}
val defaultTmdbApiKey = sequenceOf(
    System.getenv("TMDB_API_KEY"),
    localProps.getProperty("TMDB_API_KEY"),
).firstOrNull { !it.isNullOrBlank() }?.trim().orEmpty()

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    FileInputStream(keystorePropertiesFile).use(keystoreProperties::load)
}
val tiviMateApkUrl =
    "https://github.com/thothassistantai-web/tivimate-daddy/releases/download/" +
        "tivimate-daddy-v${stepdaddyVersionName}/TiviMate-4.6.1-StepDaddy-${stepdaddyVersionName}.apk"

android {
    namespace = "com.thothassistant.stepdaddy.gateway"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.thothassistant.stepdaddy.gateway"
        minSdk = 24
        targetSdk = 34
        versionCode = stepdaddyVersionCode
        versionName = stepdaddyVersionName

        buildConfigField("int", "DEFAULT_PORT", "3000")
        buildConfigField("String", "DEFAULT_API_URL", "\"http://127.0.0.1:3000\"")
        buildConfigField(
            "String",
            "DEFAULT_DLHD_BASE_URL",
            "\"https://daddylive.eu\"",
        )
        buildConfigField("boolean", "DEFAULT_SUPPLEMENT_SPORTS_ENABLED", "true")
        buildConfigField("boolean", "DEFAULT_SUPPLEMENT_IPTV_ORG_ENABLED", "true")
        buildConfigField("boolean", "DEFAULT_SUPPLEMENT_NTV_CX_ENABLED", "true")
        buildConfigField("boolean", "DEFAULT_SUPPLEMENT_ADULT_SWIM_ENABLED", "true")
        buildConfigField("boolean", "DEFAULT_SUPPLEMENT_XYZ_STREAMS_ENABLED", "false")
        buildConfigField("boolean", "DEFAULT_SUPPLEMENT_XYZ_STREAMS_EPG_DISCOVERY_ENABLED", "true")
        buildConfigField("boolean", "DEFAULT_SUPPLEMENT_TMDB_MOVIES_ENABLED", "true")
        buildConfigField("String", "DEFAULT_TMDB_API_KEY", "\"$defaultTmdbApiKey\"")
        buildConfigField("boolean", "DEFAULT_SUPPLEMENT_NTV_CX_SUPPLEMENT_ONLY", "false")
        buildConfigField("boolean", "DEFAULT_GATEWAY_EPG_ENABLED", "true")
        buildConfigField(
            "String",
            "DEFAULT_EXTERNAL_EPG_URL",
            "\"https://epgshare01.online/epgshare01/epg_ripper_US2.xml.gz," +
                "https://epgshare01.online/epgshare01/epg_ripper_US_SPORTS1.xml.gz," +
                "https://epgshare01.online/epgshare01/epg_ripper_US_LOCALS1.xml.gz\"",
        )
        buildConfigField("boolean", "DEFAULT_IPTV_ORG_EPG_ENABLED", "true")
        buildConfigField("String", "DEFAULT_IPTV_ORG_EPG_URL", "\"\"")
        buildConfigField("boolean", "DEFAULT_AUTO_CHECK_UPDATES", "true")
        buildConfigField("boolean", "DEFAULT_AUTO_DOWNLOAD_UPDATES", "false")
        buildConfigField("String", "DEFAULT_PLAYLIST_TITLE_STYLE", "\"XTREAM_CATEGORY\"")
        buildConfigField("String", "DEFAULT_SUPPLEMENT_IMPORT_MODE", "\"FULL_CATALOG\"")
        buildConfigField(
            "String",
            "DEFAULT_UPDATE_MANIFEST_URL",
            "\"https://api.github.com/repos/thothassistantai-web/stepdaddy-gateway-android/releases/latest\"",
        )
        buildConfigField("String", "DEFAULT_UPDATE_DRIVE_FOLDER_URL", "\"\"")
        buildConfigField(
            "String",
            "DEFAULT_TIVIMATE_STEPDADDY_APK_URL",
            "\"$tiviMateApkUrl\"",
        )
        buildConfigField(
            "String",
            "GATEWAY_GITHUB_RELEASE_REPO",
            "\"thothassistantai-web/stepdaddy-gateway-android\"",
        )
        buildConfigField(
            "String",
            "TIVIMATE_GITHUB_RELEASE_REPO",
            "\"thothassistantai-web/tivimate-daddy\"",
        )
        buildConfigField("String", "TIVIMATE_RELEASE_TAG_PREFIX", "\"tivimate-daddy-v\"")
        buildConfigField("String", "DEFAULT_TIVIMATE_PATCH_VERSION", "\"$stepdaddyVersionName\"")
        buildConfigField("int", "DEFAULT_TIVIMATE_PATCH_VERSION_CODE", "$stepdaddyVersionCode")
        buildConfigField("String", "GIT_HASH", "\"$gitHash\"")
        buildConfigField("long", "BUILD_TIME", "${buildTime}L")
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")

    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-service:2.7.0")
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-cio:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("com.google.zxing:core:3.5.3")

    val media3Version = "1.2.1"
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-exoplayer-hls:$media3Version")
    implementation("androidx.media3:media3-ui:$media3Version")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
}
