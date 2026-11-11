import React, { useState } from 'react';
import {
  Check,
  Compass,
  Copy,
  Cpu,
  Download,
  FolderArchive,
  Rocket,
  Smartphone,
} from 'lucide-react';

interface ProjectFile {
  step: 1 | 2 | 3 | 4 | 5 | 6;
  path: string;
  language: string;
  description: string;
  content: string;
}

const PROJECT_FILES: ProjectFile[] = [
  // ================= STEP 1 FILES =================
  {
    step: 1,
    path: 'settings.gradle.kts',
    language: 'kotlin',
    description:
      'Root Gradle settings with Google, MavenCentral, JitPack (4A), and flatDir app/libs (1B).',
    content: `pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\\\.android.*")
                includeGroupByRegex("com\\\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
        flatDir {
            dirs("app/libs")
        }
    }
}

rootProject.name = "PocketMediaForge"
include(":app")`,
  },
  {
    step: 1,
    path: 'gradle.properties',
    language: 'properties',
    description: 'JVM heap, AndroidX, and non-transitive R-class settings for headless GitHub Actions CI.',
    content: `org.gradle.jvmargs=-Xmx4096m -Dfile.encoding=UTF-8
org.gradle.parallel=true
android.useAndroidX=true
android.nonTransitiveRClass=true
kotlin.code.style=official`,
  },
  {
    step: 1,
    path: 'build.gradle.kts',
    language: 'kotlin',
    description: 'Top-level Gradle configuration (AGP 8.7.2, Kotlin 2.0.21, Serialization).',
    content: `plugins {
    id("com.android.application") version "8.7.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21" apply false
}`,
  },
  {
    step: 1,
    path: 'app/build.gradle.kts',
    language: 'kotlin',
    description:
      'Hybrid 6C config: reads optional GitHub Secrets from environment variables into BuildConfig defaults, while allowing on-device override in MainActivity.',
    content: `plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

fun envOrEmpty(key: String): String =
    System.getenv(key)?.replace("\\"", "\\\\\\"") ?: ""

android {
    namespace = "forge.media.pocket"
    compileSdk = 34

    defaultConfig {
        applicationId = "forge.media.pocket"
        minSdk = 29
        targetSdk = 34

        versionCode = 1
        versionName = "1.2.0-snapdragon"

        // Strictly ARM64-v8a for Qualcomm Snapdragon
        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        // Option 6C Hybrid: Pre-fills from GitHub Actions Secrets if set, or editable on phone screen
        buildConfigField("String", "DEFAULT_TELEGRAM_BOT_TOKEN", "\\"\${envOrEmpty("TELEGRAM_BOT_TOKEN")}\\"")
        buildConfigField("String", "DEFAULT_GEMINI_API_KEY", "\\"\${envOrEmpty("GEMINI_API_KEY")}\\"")
        buildConfigField("String", "DEFAULT_ALLOWED_CHAT_ID", "\\"\${envOrEmpty("ALLOWED_CHAT_ID")}\\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
        }
        debug {
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi"
        )
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/io.netty.versions.properties"
        }
        jniLibs {
            // Option 4A: Required so SELinux allows executing bundled python/yt-dlp binaries from /lib/arm64
            useLegacyPackaging = true
        }
    }
}

dependencies {
    val ktorVersion = "2.3.12"
    val coroutinesVersion = "1.8.1"
    val workVersion = "2.9.1"

    // Core AndroidX & Lifecycle Service
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-service:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.work:work-runtime-ktx:\$workVersion")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:\$coroutinesVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:\$coroutinesVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:\$coroutinesVersion")

    // Option 1B: Self-contained FFmpegKit Full-GPL AAR downloaded into app/libs by CI
    implementation(files("libs/ffmpeg-kit-full-gpl-6.0-2.LTS.aar"))
    implementation("com.arthenica:smart-exception-java:0.2.1")

    // Option 4A: Native yt-dlp + Aria2c wrapper for Android
    implementation("io.github.junkfood02.youtubedl-android:library:0.17.2")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.17.2")
    implementation("io.github.junkfood02.youtubedl-android:aria2c:0.17.2")

    // Google ML Kit Face Detection (Bundled model)
    implementation("com.google.mlkit:face-detection:16.1.7")

    // Ktor Client
    implementation("io.ktor:ktor-client-core:\$ktorVersion")
    implementation("io.ktor:ktor-client-okhttp:\$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:\$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:\$ktorVersion")
    implementation("io.ktor:ktor-client-logging:\$ktorVersion")

    // Kotlinx Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
}`,
  },
  {
    step: 1,
    path: 'app/proguard-rules.pro',
    language: 'properties',
    description: 'Keep rules for FFmpegKit JNI, YoutubeDL-Android, Google ML Kit, and Kotlinx Serialization.',
    content: `-keep class com.arthenica.ffmpegkit.** { *; }
-keep class com.yausername.** { *; }
-keep class com.google.mlkit.** { *; }
-keepattributes *Annotation*, InnerClasses, Signature
-dontwarn java.awt.**
-dontwarn javax.naming.**`,
  },
  {
    step: 1,
    path: 'app/src/main/AndroidManifest.xml',
    language: 'xml',
    description:
      'Android 14 (API 34) manifest registering MainActivity (Launcher) and PipelineForegroundService (specialUse|mediaPlayback).',
    content: `<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
    <uses-permission android:name="android.permission.CHANGE_WIFI_STATE" />
    <uses-permission android:name="android.permission.WAKE_LOCK" />
    <uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

    <!-- Android 14 (API 34) Strict Foreground Service Permissions (Option 3B) -->
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />

    <application
        android:allowBackup="false"
        android:extractNativeLibs="true"
        android:hardwareAccelerated="true"
        android:largeHeap="true"
        android:label="Pocket Media Forge"
        android:supportsRtl="true"
        android:usesCleartextTraffic="false"
        android:theme="@style/Theme.AppCompat.DayNight.NoActionBar"
        tools:targetApi="34">

        <activity
            android:name=".ui.MainActivity"
            android:exported="true"
            android:launchMode="singleTop"
            android:windowSoftInputMode="adjustResize">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <service
            android:name=".service.PipelineForegroundService"
            android:enabled="true"
            android:exported="false"
            android:stopWithTask="false"
            android:foregroundServiceType="specialUse|mediaPlayback">
            <property
                android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
                android:value="Autonomous hardware MediaCodec rendering node and Telegram Bot long-polling worker on OxygenOS" />
        </service>

    </application>
</manifest>`,
  },
  {
    step: 1,
    path: '.github/workflows/build-apk.yml',
    language: 'yaml',
    description:
      'Hybrid 6C CI workflow: fetches FFmpegKit AAR (1B), injects optional GitHub Secrets into BuildConfig, and compiles self-contained ARM64 APK.',
    content: `name: Build Pocket Media Forge APK

on:
  push:
    branches: [ "main", "master" ]
  pull_request:
    branches: [ "main", "master" ]
  workflow_dispatch:

jobs:
  assemble-arm64:
    runs-on: ubuntu-latest
    timeout-minutes: 30

    steps:
      - name: Checkout Repository
        uses: actions/checkout@v4

      - name: Set up JDK 17 (Temurin)
        uses: actions/setup-java@v4
        with:
          distribution: 'temurin'
          java-version: '17'

      - name: Setup Android SDK
        uses: android-actions/setup-android@v3

      - name: Download FFmpegKit Full-GPL 6.0-2.LTS AAR (Option 1B)
        run: |
          mkdir -p app/libs
          curl -L --fail --retry 3 --retry-delay 5 \\
            -o app/libs/ffmpeg-kit-full-gpl-6.0-2.LTS.aar \\
            "https://github.com/arthenica/ffmpeg-kit/releases/download/v6.0.LTS/ffmpeg-kit-full-gpl-6.0-2.LTS.aar"
          ls -lh app/libs/ffmpeg-kit-full-gpl-6.0-2.LTS.aar

      - name: Setup Gradle with Cache
        uses: gradle/actions/setup-gradle@v3
        with:
          gradle-version: '8.9'

      - name: Build Release APK (ARM64-v8a Self-Contained)
        env:
          TELEGRAM_BOT_TOKEN: \${{ secrets.TELEGRAM_BOT_TOKEN }}
          GEMINI_API_KEY: \${{ secrets.GEMINI_API_KEY }}
          ALLOWED_CHAT_ID: \${{ secrets.ALLOWED_CHAT_ID }}
        run: gradle assembleRelease --stacktrace --no-daemon

      - name: Upload Compiled APK Artifact
        uses: actions/upload-artifact@v4
        with:
          name: PocketMediaForge-arm64-v8a-release
          path: app/build/outputs/apk/release/*.apk
          if-no-files-found: error
          retention-days: 14`,
  },

  // ================= STEP 2 FILES =================
  {
    step: 2,
    path: 'app/src/main/java/forge/media/pocket/service/PowerOptimizationHelper.kt',
    language: 'kotlin',
    description:
      'Manages PARTIAL_WAKE_LOCK, Low-Latency WifiLock, OxygenOS battery intents, and the Screen-Off-Only Silent Audio Loop (Option 3B).',
    content: `package forge.media.pocket.service

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class PowerOptimizationHelper(private val context: Context) {

    companion object {
        private const val TAG = "PowerOptHelper"
        private const val WAKELOCK_TAG = "PocketMediaForge::PipelineWakeLock"
        private const val WIFILOCK_TAG = "PocketMediaForge::PipelineWifiLock"
        private const val SAMPLE_RATE_HZ = 8000
    }

    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val wifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private val audioScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var silentAudioJob: Job? = null
    private var audioTrack: AudioTrack? = null
    private val isScreenOffAudioRunning = AtomicBoolean(false)
    private var isReceiverRegistered = false

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    Log.i(TAG, "Screen OFF detected -> Engaging OxygenOS Silent Audio anti-freeze loop")
                    startSilentAudioLoop()
                }
                Intent.ACTION_SCREEN_ON -> {
                    Log.i(TAG, "Screen ON detected -> Releasing Silent Audio loop")
                    stopSilentAudioLoop()
                }
            }
        }
    }

    @SuppressLint("WakelockTimeout")
    fun acquireLocksAndRegisterScreenWatcher() {
        if (wakeLock == null) {
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                WAKELOCK_TAG
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
        } else if (wakeLock?.isHeld == false) {
            wakeLock?.acquire()
        }

        if (wifiLock == null) {
            val lockMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                @Suppress("DEPRECATION")
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wifiManager.createWifiLock(lockMode, WIFILOCK_TAG).apply {
                setReferenceCounted(false)
                acquire()
            }
        } else if (wifiLock?.isHeld == false) {
            wifiLock?.acquire()
        }

        registerScreenStateReceiver()

        if (!powerManager.isInteractive) {
            startSilentAudioLoop()
        }
    }

    fun releaseAll() {
        unregisterScreenStateReceiver()
        stopSilentAudioLoop()
        audioScope.cancel()

        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing WakeLock", e)
        } finally {
            wakeLock = null
        }

        try {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing WifiLock", e)
        } finally {
            wifiLock = null
        }
    }

    private fun registerScreenStateReceiver() {
        if (isReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(screenStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(screenStateReceiver, filter)
        }
        isReceiverRegistered = true
    }

    private fun unregisterScreenStateReceiver() {
        if (!isReceiverRegistered) return
        try {
            context.unregisterReceiver(screenStateReceiver)
        } catch (_: IllegalArgumentException) {
        } finally {
            isReceiverRegistered = false
        }
    }

    private fun startSilentAudioLoop() {
        if (!isScreenOffAudioRunning.compareAndSet(false, true)) return

        silentAudioJob = audioScope.launch {
            try {
                val minBufferSize = AudioTrack.getMinBufferSize(
                    SAMPLE_RATE_HZ,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                ).coerceAtLeast(SAMPLE_RATE_HZ)

                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE_HZ)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(minBufferSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                audioTrack = track
                track.play()

                val silentBuffer = ByteArray(minBufferSize)
                while (isActive && isScreenOffAudioRunning.get()) {
                    val written = track.write(silentBuffer, 0, silentBuffer.size)
                    if (written < 0) break
                    delay(250L)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to run Screen-Off Silent Audio loop", e)
            } finally {
                releaseAudioTrackInstance()
            }
        }
    }

    private fun stopSilentAudioLoop() {
        if (!isScreenOffAudioRunning.compareAndSet(true, false)) return
        silentAudioJob?.cancel()
        silentAudioJob = null
        releaseAudioTrackInstance()
    }

    private fun releaseAudioTrackInstance() {
        try {
            audioTrack?.let { track ->
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.stop()
                }
                track.release()
            }
        } catch (_: Exception) {
        } finally {
            audioTrack = null
        }
    }

    fun isIgnoringBatteryOptimizations(): Boolean =
        powerManager.isIgnoringBatteryOptimizations(context.packageName)

    @SuppressLint("BatteryLife")
    fun buildIgnoreBatteryOptimizationIntent(): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:\${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    fun buildOxygenOsAutoLaunchIntent(): Intent? {
        val oplusTargets = listOf(
            ComponentName(
                "com.coloros.safecenter",
                "com.coloros.safecenter.permission.startup.StartupAppListActivity"
            ),
            ComponentName(
                "com.oplus.battery",
                "com.oplus.powermanager.fuelgaue.PowerControlActivity"
            ),
            ComponentName(
                "com.coloros.oppoguardelf",
                "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity"
            ),
            ComponentName(
                "com.oneplus.security",
                "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"
            )
        )
        for (component in oplusTargets) {
            val intent = Intent().apply {
                this.component = component
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (context.packageManager.resolveActivity(intent, 0) != null) {
                return intent
            }
        }
        return null
    }
}`,
  },
  {
    step: 2,
    path: 'app/src/main/java/forge/media/pocket/service/PipelineForegroundService.kt',
    language: 'kotlin',
    description:
      'Android 14 Foreground LifecycleService reading dynamic credentials from NodeConfigStore (6C Hybrid), initializing yt-dlp/Aria2c, and running TelegramBotWorker.',
    content: `package forge.media.pocket.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.yausername.aria2c.Aria2c
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import forge.media.pocket.clutter.ClutterEntropyFilter
import forge.media.pocket.compositor.FFmpegHardwareEngine
import forge.media.pocket.config.NodeConfigStore
import forge.media.pocket.discovery.TopicHunterEngine
import forge.media.pocket.subtitles.SubtitleAssTransformer
import forge.media.pocket.telegram.GeminiMetadataClient
import forge.media.pocket.telegram.TelegramBotWorker
import forge.media.pocket.vision.FaceCenteringHelper
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed class ServiceStatus {
    data object Stopped : ServiceStatus()
    data object Initializing : ServiceStatus()
    data object IdleListening : ServiceStatus()
    data class Processing(val jobId: String, val stage: String) : ServiceStatus()
    data class Error(val reason: String) : ServiceStatus()
}

class PipelineForegroundService : LifecycleService() {

    companion object {
        private const val TAG = "PipelineFgService"
        private const val NOTIFICATION_ID = 4096
        private const val CHANNEL_ID = "pocket_media_forge_pipeline"

        const val ACTION_START_ENGINE = "forge.media.pocket.action.START_ENGINE"
        const val ACTION_RELOAD_CREDENTIALS = "forge.media.pocket.action.RELOAD_CREDENTIALS"
        const val ACTION_STOP_ENGINE = "forge.media.pocket.action.STOP_ENGINE"

        private val _statusFlow = MutableStateFlow<ServiceStatus>(ServiceStatus.Stopped)
        val statusFlow: StateFlow<ServiceStatus> = _statusFlow.asStateFlow()

        fun startService(context: Context) {
            val intent = Intent(context, PipelineForegroundService::class.java).apply {
                action = ACTION_START_ENGINE
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun reloadCredentials(context: Context) {
            val intent = Intent(context, PipelineForegroundService::class.java).apply {
                action = ACTION_RELOAD_CREDENTIALS
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stopService(context: Context) {
            val intent = Intent(context, PipelineForegroundService::class.java).apply {
                action = ACTION_STOP_ENGINE
            }
            context.startService(intent)
        }
    }

    private lateinit var powerHelper: PowerOptimizationHelper
    private lateinit var notificationManager: NotificationManager
    private lateinit var configStore: NodeConfigStore
    private var telegramWorker: TelegramBotWorker? = null

    private val jobQueue = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val renderDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)

    @Volatile
    private var isNativeEnvReady = false

    override fun onCreate() {
        super.onCreate()
        configStore = NodeConfigStore(applicationContext)
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()

        powerHelper = PowerOptimizationHelper(applicationContext)
        powerHelper.acquireLocksAndRegisterScreenWatcher()

        promoteToForeground("Initializing Snapdragon Media Pipeline...")

        lifecycleScope.launch(Dispatchers.IO) {
            initializeNativeToolchains()
            startSequentialJobConsumer()
            restartTelegramBotController()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP_ENGINE -> {
                _statusFlow.value = ServiceStatus.Stopped
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RELOAD_CREDENTIALS -> {
                promoteToForeground("Reloading Telegram & Gemini credentials...")
                lifecycleScope.launch(Dispatchers.IO) {
                    restartTelegramBotController()
                }
            }
            else -> promoteToForeground(currentStatusText())
        }
        return START_STICKY
    }

    fun submitHardwareTask(jobId: String, stageDescription: String, block: suspend () -> Unit) {
        jobQueue.trySend {
            _statusFlow.value = ServiceStatus.Processing(jobId, stageDescription)
            updateNotification("Job #\$jobId: \$stageDescription")
            block()
        }
    }

    private suspend fun initializeNativeToolchains() = withContext(Dispatchers.IO) {
        try {
            _statusFlow.value = ServiceStatus.Initializing
            updateNotification("Bootstrapping native yt-dlp & Aria2c binaries...")

            YoutubeDL.getInstance().init(applicationContext)
            FFmpeg.getInstance().init(applicationContext)
            Aria2c.getInstance().init(applicationContext)

            File(filesDir, "clutter_pool").mkdirs()
            File(cacheDir, "work_staging").mkdirs()

            isNativeEnvReady = true
            _statusFlow.value = ServiceStatus.IdleListening
            updateNotification("Idle • Listening for Telegram commands (Snapdragon HW Ready)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize native toolchains", e)
            _statusFlow.value = ServiceStatus.Error("Native init failed: \${e.message}")
            updateNotification("Error: Native toolchain init failed")
        }
    }

    private fun restartTelegramBotController() {
        telegramWorker?.stopPolling()
        val token = configStore.getTelegramBotToken()
        if (token.isBlank()) {
            _statusFlow.value = ServiceStatus.Error("Telegram Bot Token not set (enter in app UI)")
            updateNotification("Waiting for Telegram Bot Token in app UI...")
            return
        }

        val worker = TelegramBotWorker(
            context = applicationContext,
            configStore = configStore,
            clutterFilter = ClutterEntropyFilter(applicationContext),
            topicHunter = TopicHunterEngine(applicationContext),
            faceHelper = FaceCenteringHelper(applicationContext),
            subtitleTransformer = SubtitleAssTransformer(applicationContext, configStore),
            hardwareEngine = FFmpegHardwareEngine(applicationContext),
            geminiClient = GeminiMetadataClient(configStore),
            enqueueHardwareTask = { jobId, stage, task ->
                submitHardwareTask(jobId, stage, task)
            }
        )
        telegramWorker = worker
        _statusFlow.value = ServiceStatus.IdleListening
        updateNotification("Online • Telegram Bot Polling Active")
        worker.startPolling(lifecycleScope)
    }

    private fun startSequentialJobConsumer() {
        lifecycleScope.launch(renderDispatcher) {
            for (task in jobQueue) {
                if (!isNativeEnvReady) continue
                try {
                    task.invoke()
                } catch (e: Exception) {
                    Log.e(TAG, "Hardware task failed", e)
                    _statusFlow.value = ServiceStatus.Error("Task error: \${e.message}")
                } finally {
                    _statusFlow.value = ServiceStatus.IdleListening
                    updateNotification("Idle • Listening for Telegram commands")
                }
            }
        }
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "Android 14 FGS onTimeout triggered for fgsType=\$fgsType")
        super.onTimeout(startId, fgsType)
    }

    override fun onDestroy() {
        telegramWorker?.stopPolling()
        jobQueue.close()
        powerHelper.releaseAll()
        _statusFlow.value = ServiceStatus.Stopped
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)

    private fun promoteToForeground(statusText: String) {
        val notification = buildNotification(statusText)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(statusText: String) {
        notificationManager.notify(NOTIFICATION_ID, buildNotification(statusText))
    }

    private fun buildNotification(statusText: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Pocket Media Forge • HW Node")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Autonomous Media Pipeline",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps Snapdragon hardware transcoder and Telegram worker alive on OxygenOS"
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun currentStatusText(): String {
        return when (val s = _statusFlow.value) {
            is ServiceStatus.Stopped -> "Node Stopped"
            is ServiceStatus.Initializing -> "Initializing native toolchains..."
            is ServiceStatus.IdleListening -> "Idle • Listening for Telegram commands"
            is ServiceStatus.Processing -> "Job \${s.jobId}: \${s.stage}"
            is ServiceStatus.Error -> "Alert: \${s.reason}"
        }
    }
}`,
  },

  // ================= STEP 3 FILES =================
  {
    step: 3,
    path: 'app/src/main/java/forge/media/pocket/vision/FaceCenteringHelper.kt',
    language: 'kotlin',
    description:
      'Implements 3.1A (1 FPS FFmpeg batch thumbnail extraction) + 3.2 Adaptive Multi-Face Span (centers 1 speaker, fits both speakers with 12% margin if 2+ faces, or cancels tight crop with blurred pad if too wide).',
    content: `package forge.media.pocket.vision

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.roundToInt

sealed class UpperBoxFramingPlan {
    abstract val ffmpegVideoFilter: String
    abstract val summary: String
    abstract val hasBottomFaceRisk: Boolean

    data class TightCrop(
        val cropWidth: Int,
        val cropHeight: Int,
        val cropX: Int,
        val cropY: Int,
        val speakerCount: Int
    ) : UpperBoxFramingPlan() {
        override val ffmpegVideoFilter: String
            get() = "crop=\${cropWidth}:\${cropHeight}:\${cropX}:\${cropY},scale=1080:960:flags=lanczos,setsar=1"

        override val summary: String
            get() = "TightCrop(speakers=\$speakerCount, x=\$cropX, w=\$cropWidth, h=\$cropHeight)"

        override val hasBottomFaceRisk: Boolean
            get() = true
    }

    data class WideFitMultiSpeaker(
        val spanX: Int,
        val spanWidth: Int,
        val sourceHeight: Int
    ) : UpperBoxFramingPlan() {
        override val ffmpegVideoFilter: String
            get() = buildString {
                append("crop=\${spanWidth}:\${sourceHeight}:\${spanX}:0,")
                append("scale=1080:960:force_original_aspect_ratio=decrease:flags=lanczos,")
                append("pad=1080:960:(ow-iw)/2:(oh-ih)/2:color=#101014,setsar=1")
            }

        override val summary: String
            get() = "WideFitMultiSpeaker(spanX=\$spanX, spanW=\$spanWidth -> fitted into 1080x960)"

        override val hasBottomFaceRisk: Boolean
            get() = false
    }

    data object UncroppedFullFrame : UpperBoxFramingPlan() {
        override val ffmpegVideoFilter: String
            get() = "scale=1080:960:force_original_aspect_ratio=decrease:flags=lanczos,pad=1080:960:(ow-iw)/2:(oh-ih)/2:color=#101014,setsar=1"

        override val summary: String
            get() = "UncroppedFullFrame(16:9 fitted into 1080x960)"

        override val hasBottomFaceRisk: Boolean
            get() = false
    }
}

class FaceCenteringHelper(private val context: Context) {

    companion object {
        private const val TAG = "FaceCenteringHelper"
        private const val TARGET_UPPER_W = 1080
        private const val TARGET_UPPER_H = 960
        private const val TARGET_ASPECT = TARGET_UPPER_W.toDouble() / TARGET_UPPER_H.toDouble()
        private const val THUMB_WIDTH = 480
        private const val MULTI_FACE_MARGIN_RATIO = 0.12
        private const val EMA_ALPHA = 0.35
    }

    private val detector: FaceDetector by lazy {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setMinFaceSize(0.06f)
            .build()
        FaceDetection.getClient(options)
    }

    private data class FrameFaceObservation(
        val faceCount: Int,
        val primaryCenterXNorm: Double,
        val minLeftNorm: Double,
        val maxRightNorm: Double
    )

    suspend fun computeFramingPlan(
        videoFile: File,
        enableSmartFaceCrop: Boolean = true,
        maxSampleSeconds: Int = 60
    ): UpperBoxFramingPlan = withContext(Dispatchers.IO) {
        if (!enableSmartFaceCrop) {
            return@withContext UpperBoxFramingPlan.UncroppedFullFrame
        }

        val (srcWidth, srcHeight) = probeVideoDimensions(videoFile)
        val baseCropHeight = srcHeight
        val baseCropWidth = (srcHeight * TARGET_ASPECT).roundToInt().coerceAtMost(srcWidth)
        val defaultCenterX = ((srcWidth - baseCropWidth) / 2).coerceAtLeast(0)

        val framesDir = File(context.cacheDir, "face_samples_\${System.currentTimeMillis()}").apply {
            mkdirs()
        }

        try {
            val extractedFrames = extractBatchThumbnails1Fps(
                videoFile = videoFile,
                outputDir = framesDir,
                maxDurationSec = maxSampleSeconds
            )

            if (extractedFrames.isEmpty()) {
                return@withContext UpperBoxFramingPlan.TightCrop(
                    cropWidth = baseCropWidth,
                    cropHeight = baseCropHeight,
                    cropX = defaultCenterX,
                    cropY = 0,
                    speakerCount = 0
                )
            }

            val observations = mutableListOf<FrameFaceObservation>()

            for (frameFile in extractedFrames) {
                val bitmap = BitmapFactory.decodeFile(frameFile.absolutePath) ?: continue
                val bmpW = bitmap.width.toDouble()
                val bmpH = bitmap.height.toDouble()

                try {
                    val inputImage = InputImage.fromBitmap(bitmap, 0)
                    val faces = detector.process(inputImage).await()

                    val validFaces = faces.filter { face ->
                        val areaRatio = (face.boundingBox.width() * face.boundingBox.height()) / (bmpW * bmpH)
                        areaRatio >= 0.012
                    }

                    if (validFaces.isNotEmpty()) {
                        val dominant = validFaces.maxByOrNull {
                            it.boundingBox.width() * it.boundingBox.height()
                        }!!

                        val activeSpeakers = validFaces.filter {
                            val domArea = dominant.boundingBox.width() * dominant.boundingBox.height()
                            val curArea = it.boundingBox.width() * it.boundingBox.height()
                            curArea >= domArea * 0.35
                        }

                        val minLeft = activeSpeakers.minOf {
                            (it.boundingBox.left.toDouble() / bmpW).coerceIn(0.0, 1.0)
                        }
                        val maxRight = activeSpeakers.maxOf {
                            (it.boundingBox.right.toDouble() / bmpW).coerceIn(0.0, 1.0)
                        }
                        val domCenterNorm =
                            (dominant.boundingBox.centerX().toDouble() / bmpW).coerceIn(0.0, 1.0)

                        observations.add(
                            FrameFaceObservation(
                                faceCount = activeSpeakers.size,
                                primaryCenterXNorm = domCenterNorm,
                                minLeftNorm = minLeft,
                                maxRightNorm = maxRight
                            )
                        )
                    }
                } finally {
                    bitmap.recycle()
                }
            }

            if (observations.isEmpty()) {
                return@withContext UpperBoxFramingPlan.TightCrop(
                    cropWidth = baseCropWidth,
                    cropHeight = baseCropHeight,
                    cropX = defaultCenterX,
                    cropY = 0,
                    speakerCount = 0
                )
            }

            val multiSpeakerFrames = observations.filter { it.faceCount >= 2 }
            val multiSpeakerRatio = multiSpeakerFrames.size.toDouble() / observations.size.toDouble()

            if (multiSpeakerFrames.isNotEmpty() && multiSpeakerRatio >= 0.25) {
                val medianLeftNorm = median(multiSpeakerFrames.map { it.minLeftNorm })
                val medianRightNorm = median(multiSpeakerFrames.map { it.maxRightNorm })

                val paddedLeftPx = ((medianLeftNorm - MULTI_FACE_MARGIN_RATIO) * srcWidth)
                    .roundToInt()
                    .coerceIn(0, srcWidth - 2)
                val paddedRightPx = ((medianRightNorm + MULTI_FACE_MARGIN_RATIO) * srcWidth)
                    .roundToInt()
                    .coerceIn(paddedLeftPx + 2, srcWidth)

                val requiredSpanWidth = paddedRightPx - paddedLeftPx

                return@withContext if (requiredSpanWidth <= baseCropWidth) {
                    val groupCenterPx = (paddedLeftPx + paddedRightPx) / 2
                    val alignedCropX = (groupCenterPx - baseCropWidth / 2)
                        .coerceIn(0, srcWidth - baseCropWidth)
                        .let { it - (it % 2) }

                    UpperBoxFramingPlan.TightCrop(
                        cropWidth = baseCropWidth - (baseCropWidth % 2),
                        cropHeight = baseCropHeight - (baseCropHeight % 2),
                        cropX = alignedCropX,
                        cropY = 0,
                        speakerCount = 2
                    )
                } else {
                    val evenSpanW = requiredSpanWidth - (requiredSpanWidth % 2)
                    val evenSpanX = paddedLeftPx - (paddedLeftPx % 2)
                    UpperBoxFramingPlan.WideFitMultiSpeaker(
                        spanX = evenSpanX,
                        spanWidth = evenSpanW.coerceAtLeast(baseCropWidth),
                        sourceHeight = baseCropHeight - (baseCropHeight % 2)
                    )
                }
            }

            val centers = observations.map { it.primaryCenterXNorm }
            val p15 = percentile(centers, 0.15)
            val p85 = percentile(centers, 0.85)
            val spreadPx = ((p85 - p15) * srcWidth).roundToInt()

            if (spreadPx > baseCropWidth * 0.75) {
                val paddedLeftPx = ((p15 - MULTI_FACE_MARGIN_RATIO * 1.5) * srcWidth)
                    .roundToInt()
                    .coerceIn(0, srcWidth - 2)
                val paddedRightPx = ((p85 + MULTI_FACE_MARGIN_RATIO * 1.5) * srcWidth)
                    .roundToInt()
                    .coerceIn(paddedLeftPx + 2, srcWidth)
                val spanW = (paddedRightPx - paddedLeftPx).let { it - (it % 2) }
                val spanX = paddedLeftPx - (paddedLeftPx % 2)

                if (spanW > baseCropWidth) {
                    return@withContext UpperBoxFramingPlan.WideFitMultiSpeaker(
                        spanX = spanX,
                        spanWidth = spanW,
                        sourceHeight = baseCropHeight - (baseCropHeight % 2)
                    )
                }
            }

            var emaCenterNorm = median(centers)
            for (c in centers) {
                if (abs(c - emaCenterNorm) < 0.25) {
                    emaCenterNorm = EMA_ALPHA * c + (1.0 - EMA_ALPHA) * emaCenterNorm
                }
            }

            val targetCenterPx = (emaCenterNorm * srcWidth).roundToInt()
            val rawCropX = (targetCenterPx - baseCropWidth / 2).coerceIn(0, srcWidth - baseCropWidth)
            val evenCropX = rawCropX - (rawCropX % 2)

            UpperBoxFramingPlan.TightCrop(
                cropWidth = baseCropWidth - (baseCropWidth % 2),
                cropHeight = baseCropHeight - (baseCropHeight % 2),
                cropX = evenCropX,
                cropY = 0,
                speakerCount = 1
            )
        } finally {
            framesDir.deleteRecursively()
        }
    }

    private suspend fun extractBatchThumbnails1Fps(
        videoFile: File,
        outputDir: File,
        maxDurationSec: Int
    ): List<File> = suspendCancellableCoroutine { cont ->
        val outPattern = File(outputDir, "sample_%03d.jpg").absolutePath
        val cmd = buildString {
            append("-hide_banner -loglevel error -nostdin ")
            append("-t $maxDurationSec ")
            append("-i \\"\${videoFile.absolutePath}\\" ")
            append("-vf \\"fps=1,scale=$THUMB_WIDTH:-2:flags=fast_bilinear\\" ")
            append("-q:v 5 \\"$outPattern\\"")
        }

        FFmpegKit.executeAsync(cmd) { session ->
            if (ReturnCode.isSuccess(session.returnCode)) {
                val files = outputDir.listFiles { f -> f.extension == "jpg" }
                    ?.sortedBy { it.name }
                    ?: emptyList()
                if (cont.isActive) cont.resume(files)
            } else {
                if (cont.isActive) cont.resume(emptyList())
            }
        }
    }

    private fun probeVideoDimensions(videoFile: File): Pair<Int, Int> {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(videoFile.absolutePath)
            val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 1920
            val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 1080
            Pair(w, h)
        } catch (_: Exception) {
            Pair(1920, 1080)
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.5
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[mid - 1] + sorted[mid]) / 2.0
        } else {
            sorted[mid]
        }
    }

    private fun percentile(values: List<Double>, p: Double): Double {
        if (values.isEmpty()) return 0.5
        val sorted = values.sorted()
        val idx = ((sorted.size - 1) * p).roundToInt().coerceIn(0, sorted.lastIndex)
        return sorted[idx]
    }
}`,
  },
  {
    step: 3,
    path: 'app/src/main/java/forge/media/pocket/clutter/ClutterEntropyFilter.kt',
    language: 'kotlin',
    description:
      'Implements 3.3A Dual-Sided Entropy Filter (rejects gt(scene,0.40) hard cuts AND freezedetect > 2.0s pauses), circular pool buffer, source metadata extraction, and Shuffle Stitcher (1080x960 @ 30fps).',
    content: `package forge.media.pocket.clutter

import android.content.Context
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.math.ceil

data class EntropyAuditResult(
    val isMonotonousAndValid: Boolean,
    val hardSceneCutsDetected: Int,
    val freezeIntervalsDetected: Int,
    val rejectionReason: String? = null
)

data class IngestedMediaBundle(
    val videoFile: File,
    val originalTitle: String,
    val uploaderAuthor: String,
    val sourceTags: List<String>
)

class ClutterEntropyFilter(private val context: Context) {

    companion object {
        private const val TAG = "ClutterEntropyFilter"
        private const val SCENE_CUT_THRESHOLD = 0.40
        private const val FREEZE_NOISE_TOLERANCE = 0.003
        private const val FREEZE_MIN_DURATION_SEC = 2.0
        private const val MAX_POOL_CLIPS = 16
        private const val SEGMENT_DURATION_SEC = 60
    }

    private val poolDir = File(context.filesDir, "clutter_pool").apply { mkdirs() }
    private val stagingDir = File(context.cacheDir, "work_staging").apply { mkdirs() }
    private val poolMutex = Mutex()

    private val jsonFormat = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val httpClient = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(jsonFormat)
        }
    }

    @Serializable
    private data class RedditListingResponse(
        val data: RedditListingData? = null
    )

    @Serializable
    private data class RedditListingData(
        val children: List<RedditPostWrapper> = emptyList()
    )

    @Serializable
    private data class RedditPostWrapper(
        val data: RedditPostData? = null
    )

    @Serializable
    private data class RedditPostData(
        val title: String = "",
        @SerialName("is_video") val isVideo: Boolean = false,
        val over18: Boolean = false,
        val media: RedditMedia? = null
    )

    @Serializable
    private data class RedditMedia(
        @SerialName("reddit_video") val redditVideo: RedditVideoPayload? = null
    )

    @Serializable
    private data class RedditVideoPayload(
        @SerialName("fallback_url") val fallbackUrl: String? = null,
        val duration: Int = 0
    )

    suspend fun auditClipEntropy(candidateFile: File): EntropyAuditResult =
        suspendCancellableCoroutine { cont ->
            val filterChain = buildString {
                append("scale=320:180:flags=fast_bilinear,")
                append("freezedetect=n=$FREEZE_NOISE_TOLERANCE:d=$FREEZE_MIN_DURATION_SEC,")
                append("select='gt(scene,$SCENE_CUT_THRESHOLD)',showinfo")
            }

            val cmd = buildString {
                append("-hide_banner -nostdin ")
                append("-i \\"\${candidateFile.absolutePath}\\" ")
                append("-vf \\"$filterChain\\" ")
                append("-an -f null -")
            }

            FFmpegKit.executeAsync(cmd) { session ->
                val logs = session.allLogsAsString ?: ""
                val hardCutCount = Regex("""\\[Parsed_showinfo.*pts_time:""").findAll(logs).count()
                val freezeCount = Regex("""lavfi\\.freezedetect\\.freeze_start""").findAll(logs).count()

                val isClean = (hardCutCount == 0) && (freezeCount == 0)
                val reason = when {
                    hardCutCount > 0 && freezeCount > 0 ->
                        "Rejected: $hardCutCount hard scene cuts (>0.40) and $freezeCount static freezes (>2.0s)"
                    hardCutCount > 0 ->
                        "Rejected: $hardCutCount hard scene cuts (scene > $SCENE_CUT_THRESHOLD)"
                    freezeCount > 0 ->
                        "Rejected: $freezeCount static/frozen screens (>= \${FREEZE_MIN_DURATION_SEC}s)"
                    else -> null
                }

                if (cont.isActive) {
                    cont.resume(
                        EntropyAuditResult(
                            isMonotonousAndValid = isClean,
                            hardSceneCutsDetected = hardCutCount,
                            freezeIntervalsDetected = freezeCount,
                            rejectionReason = reason
                        )
                    )
                }
            }
        }

    suspend fun downloadMediaWithMetadata(
        sourceUrl: String,
        maxDurationSec: Int = 75
    ): IngestedMediaBundle = withContext(Dispatchers.IO) {
        var origTitle = ""
        var origUploader = ""
        var origTags = emptyList<String>()

        try {
            val info = YoutubeDL.getInstance().getInfo(sourceUrl)
            origTitle = info.title ?: ""
            origUploader = info.uploader ?: ""
            origTags = info.tags ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "Metadata pre-fetch warning for $sourceUrl: \${e.message}")
        }

        val file = downloadMediaFromUrl(sourceUrl, maxDurationSec)
        IngestedMediaBundle(
            videoFile = file,
            originalTitle = origTitle,
            uploaderAuthor = origUploader,
            sourceTags = origTags
        )
    }

    suspend fun downloadMediaFromUrl(
        sourceUrl: String,
        maxDurationSec: Int = SEGMENT_DURATION_SEC
    ): File = withContext(Dispatchers.IO) {
        val outFile = File(stagingDir, "ingest_\${UUID.randomUUID()}.mp4")
        val request = YoutubeDLRequest(sourceUrl).apply {
            addOption("-f", "bestvideo[height<=1080][ext=mp4]+bestaudio[ext=m4a]/best[height<=1080][ext=mp4]/best")
            addOption("--merge-output-format", "mp4")
            addOption("--downloader", "libaria2c.so")
            addOption("--external-downloader-args", "aria2c:\\"-x 8 -s 8 -k 1M\\"")
            addOption("--download-sections", "*0-$maxDurationSec")
            addOption("--no-playlist")
            addOption("-o", outFile.absolutePath)
        }

        YoutubeDL.getInstance().execute(request)

        if (!outFile.exists() || outFile.length() == 0L) {
            throw IllegalStateException("yt-dlp failed to download media from $sourceUrl")
        }
        outFile
    }

    suspend fun fetchRedditOddlySatisfyingUrls(limit: Int = 15): List<String> =
        withContext(Dispatchers.IO) {
            try {
                val response: RedditListingResponse = httpClient.get(
                    "https://www.reddit.com/r/oddlysatisfying/hot.json?limit=$limit"
                ) {
                    header(HttpHeaders.UserAgent, "android:forge.media.pocket:v1.2.0 (by /u/pocketforge)")
                }.body()

                response.data?.children
                    ?.mapNotNull { it.data }
                    ?.filter { it.isVideo && !it.over18 }
                    ?.mapNotNull { it.media?.redditVideo?.fallbackUrl }
                    ?: emptyList()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch Reddit r/oddlysatisfying JSON feed", e)
                emptyList()
            }
        }

    suspend fun ingestAndVerifyClutterCandidate(sourceUrl: String): File? =
        withContext(Dispatchers.IO) {
            var rawFile: File? = null
            try {
                rawFile = downloadMediaFromUrl(sourceUrl, maxDurationSec = SEGMENT_DURATION_SEC)
                val audit = auditClipEntropy(rawFile)

                if (!audit.isMonotonousAndValid) {
                    return@withContext null
                }

                val normalizedFile = File(poolDir, "clutter_\${System.currentTimeMillis()}.mp4")
                val standardized = standardizeClipTo1080x960(rawFile, normalizedFile)
                if (!standardized) {
                    normalizedFile.delete()
                    return@withContext null
                }

                enforceCircularBufferQuota()
                normalizedFile
            } catch (e: Exception) {
                Log.e(TAG, "Failed clutter ingestion for $sourceUrl", e)
                null
            } finally {
                rawFile?.delete()
            }
        }

    suspend fun buildShuffledClutterReel(targetDurationSec: Double): File =
        withContext(Dispatchers.IO) {
            var verifiedPool = listVerifiedPoolClips()

            if (verifiedPool.isEmpty()) {
                val redditUrls = fetchRedditOddlySatisfyingUrls(limit = 10)
                for (url in redditUrls) {
                    val added = ingestAndVerifyClutterCandidate(url)
                    if (added != null && listVerifiedPoolClips().size >= 3) break
                }
                verifiedPool = listVerifiedPoolClips()
            }

            if (verifiedPool.isEmpty()) {
                throw IllegalStateException("Clutter pool is empty and no candidate passed the 3.3A entropy filter")
            }

            val neededClipsCount = ceil(targetDurationSec / 20.0).toInt().coerceAtLeast(1)
            val selectedClips = (1..neededClipsCount).map {
                verifiedPool.random()
            }

            if (selectedClips.size == 1) {
                return@withContext selectedClips.first()
            }

            val stitchedOutput = File(stagingDir, "stitched_clutter_\${System.currentTimeMillis()}.mp4")
            val success = stitchNormalizedFragments(
                fragments = selectedClips,
                targetDurationSec = targetDurationSec,
                outputFile = stitchedOutput
            )

            if (!success || !stitchedOutput.exists()) {
                return@withContext selectedClips.first()
            }
            stitchedOutput
        }

    private suspend fun standardizeClipTo1080x960(
        inputFile: File,
        outputFile: File
    ): Boolean = suspendCancellableCoroutine { cont ->
        val vf = buildString {
            append("fps=30,")
            append("scale=1080:960:force_original_aspect_ratio=increase:flags=lanczos,")
            append("crop=1080:960:(iw-1080)/2:(ih-960)/2,")
            append("setsar=1,format=nv12")
        }

        val cmd = buildString {
            append("-hide_banner -loglevel error -nostdin -y ")
            append("-i \\"\${inputFile.absolutePath}\\" ")
            append("-vf \\"$vf\\" ")
            append("-an ")
            append("-c:v h264_mediacodec -b:v 5000k ")
            append("\\"\${outputFile.absolutePath}\\"")
        }

        FFmpegKit.executeAsync(cmd) { session ->
            val ok = ReturnCode.isSuccess(session.returnCode)
            if (cont.isActive) cont.resume(ok)
        }
    }

    private suspend fun stitchNormalizedFragments(
        fragments: List<File>,
        targetDurationSec: Double,
        outputFile: File
    ): Boolean = suspendCancellableCoroutine { cont ->
        val inputsArg = fragments.joinToString(" ") { "-i \\"\${it.absolutePath}\\"" }
        val streamLabels = fragments.indices.joinToString("") { "[\$it:v:0]" }
        val filterComplex = "\${streamLabels}concat=n=\${fragments.size}:v=1:a=0,format=nv12[vout]"

        val cmd = buildString {
            append("-hide_banner -loglevel error -nostdin -y ")
            append("$inputsArg ")
            append("-filter_complex \\"$filterComplex\\" ")
            append("-map \\"[vout]\\" ")
            append("-t \${targetDurationSec + 1.0} ")
            append("-c:v h264_mediacodec -b:v 5500k ")
            append("\\"\${outputFile.absolutePath}\\"")
        }

        FFmpegKit.executeAsync(cmd) { session ->
            val ok = ReturnCode.isSuccess(session.returnCode)
            if (cont.isActive) cont.resume(ok)
        }
    }

    fun listVerifiedPoolClips(): List<File> {
        return poolDir.listFiles { f -> f.isFile && f.extension == "mp4" && f.length() > 100_000L }
            ?.sortedBy { it.lastModified() }
            ?: emptyList()
    }

    private suspend fun enforceCircularBufferQuota() = poolMutex.withLock {
        val current = listVerifiedPoolClips()
        if (current.size > MAX_POOL_CLIPS) {
            val toEvict = current.take(current.size - MAX_POOL_CLIPS)
            toEvict.forEach { oldFile -> oldFile.delete() }
        }
    }
}`,
  },
  {
    step: 3,
    path: 'app/src/main/java/forge/media/pocket/discovery/TopicHunterEngine.kt',
    language: 'kotlin',
    description:
      'Autonomous Content Discovery Engine (Option C: Manual /hunt + Background /autotags Autopilot): searches YouTube Shorts/Clips, Reddit, and Pikabu by user tags/topics with sorting by Popularity over Period (Day/Week/Month/All), Newest, or Viral Velocity, plus anti-duplicate history.',
    content: `package forge.media.pocket.discovery

import android.content.Context
import android.util.Log
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

enum class ContentSortOrder(val label: String) {
    POPULAR("🔥 По популярности (Просмотры/Рейтинг)"),
    NEWEST("🆕 По новизне (Свежие)"),
    VIRAL_VELOCITY("📈 Взлетающие (Просмотры в день)"),
    RELEVANCE("🎯 По точному совпадению тегов")
}

enum class SearchTimePeriod(val label: String, val daysBack: Int?, val redditParam: String) {
    DAY("За 24 часа", 1, "day"),
    WEEK("За неделю", 7, "week"),
    MONTH("За месяц", 30, "month"),
    YEAR("За год", 365, "year"),
    ALL_TIME("За всё время", null, "all")
}

enum class HuntPlatform(val label: String) {
    ALL("🌐 Все площадки"),
    YOUTUBE("▶️ YouTube Clips/Shorts"),
    REDDIT("👽 Reddit Video"),
    PIKABU("🟠 Пикабу Видео")
}

@Serializable
data class DiscoveredMediaCandidate(
    val id: String,
    val url: String,
    val title: String,
    val author: String,
    val platform: HuntPlatform,
    val durationSec: Int,
    val viewOrScoreCount: Long,
    val uploadDateYmd: String = ""
)

@Serializable
data class AutopilotTagSubscription(
    val queryTags: String,
    val platform: HuntPlatform = HuntPlatform.ALL,
    val sortOrder: ContentSortOrder = ContentSortOrder.POPULAR,
    val period: SearchTimePeriod = SearchTimePeriod.WEEK,
    val maxDurationSec: Int = 65
)

class TopicHunterEngine(private val context: Context) {

    companion object {
        private const val TAG = "TopicHunterEngine"
    }

    private val seenIdsFile = File(context.filesDir, "seen_video_ids.txt")
    private val subscriptionsFile = File(context.filesDir, "autopilot_subscriptions.json")
    private val stateMutex = Mutex()

    private val jsonFormat = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val httpClient = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(jsonFormat)
        }
    }

    @Serializable
    private data class YtDlpFlatEntry(
        val id: String = "",
        val url: String? = null,
        @SerialName("webpage_url") val webpageUrl: String? = null,
        val title: String = "",
        val uploader: String? = null,
        val duration: Double? = null,
        @SerialName("view_count") val viewCount: Long? = null,
        @SerialName("upload_date") val uploadDate: String? = null
    )

    @Serializable
    private data class RedditSearchListing(
        val data: RedditSearchData? = null
    )

    @Serializable
    private data class RedditSearchData(
        val children: List<RedditSearchChild> = emptyList()
    )

    @Serializable
    private data class RedditSearchChild(
        val data: RedditSearchPost? = null
    )

    @Serializable
    private data class RedditSearchPost(
        val id: String = "",
        val title: String = "",
        val author: String = "",
        @SerialName("is_video") val isVideo: Boolean = false,
        val over18: Boolean = false,
        val score: Long = 0L,
        @SerialName("created_utc") val createdUtc: Double = 0.0,
        val media: RedditSearchMedia? = null
    )

    @Serializable
    private data class RedditSearchMedia(
        @SerialName("reddit_video") val redditVideo: RedditSearchVideo? = null
    )

    @Serializable
    private data class RedditSearchVideo(
        @SerialName("fallback_url") val fallbackUrl: String? = null,
        val duration: Int = 0
    )

    suspend fun huntByTags(
        queryTags: String,
        platform: HuntPlatform = HuntPlatform.ALL,
        sortOrder: ContentSortOrder = ContentSortOrder.POPULAR,
        period: SearchTimePeriod = SearchTimePeriod.WEEK,
        limitCount: Int = 3,
        minDurationSec: Int = 10,
        maxDurationSec: Int = 75
    ): List<DiscoveredMediaCandidate> = withContext(Dispatchers.IO) {
        val seenIds = loadSeenIds()
        val rawPool = mutableListOf<DiscoveredMediaCandidate>()

        if (platform == HuntPlatform.ALL || platform == HuntPlatform.YOUTUBE) {
            rawPool.addAll(
                searchYouTubeByTags(
                    queryTags = queryTags,
                    sortOrder = sortOrder,
                    period = period,
                    minDurationSec = minDurationSec,
                    maxDurationSec = maxDurationSec
                )
            )
        }

        if (platform == HuntPlatform.ALL || platform == HuntPlatform.REDDIT) {
            rawPool.addAll(
                searchRedditVideosByTags(
                    queryTags = queryTags,
                    sortOrder = sortOrder,
                    period = period,
                    maxDurationSec = maxDurationSec
                )
            )
        }

        if (platform == HuntPlatform.ALL || platform == HuntPlatform.PIKABU) {
            rawPool.addAll(
                searchPikabuByTags(
                    queryTags = queryTags,
                    sortOrder = sortOrder,
                    period = period,
                    maxDurationSec = maxDurationSec
                )
            )
        }

        val unseenCandidates = rawPool.filter { candidate ->
            candidate.id.isNotBlank() && !seenIds.contains(candidate.id)
        }

        val sortedCandidates = when (sortOrder) {
            ContentSortOrder.POPULAR ->
                unseenCandidates.sortedByDescending { it.viewOrScoreCount }
            ContentSortOrder.NEWEST ->
                unseenCandidates.sortedByDescending { it.uploadDateYmd }
            ContentSortOrder.VIRAL_VELOCITY ->
                unseenCandidates.sortedByDescending { computeViralVelocityScore(it) }
            ContentSortOrder.RELEVANCE ->
                unseenCandidates
        }

        val picked = sortedCandidates.take(limitCount.coerceIn(1, 10))
        markCandidatesAsSeen(picked.map { it.id })
        picked
    }

    private fun searchYouTubeByTags(
        queryTags: String,
        sortOrder: ContentSortOrder,
        period: SearchTimePeriod,
        minDurationSec: Int,
        maxDurationSec: Int
    ): List<DiscoveredMediaCandidate> {
        return try {
            val searchPrefix = when (sortOrder) {
                ContentSortOrder.NEWEST -> "ytsearchdate20"
                else -> "ytsearch25"
            }

            val request = YoutubeDLRequest("\$searchPrefix:\$queryTags").apply {
                addOption("--dump-json")
                addOption("--no-playlist")
                addOption("--skip-download")
                addOption("--match-filter", "duration >= \$minDurationSec & duration <= \$maxDurationSec")
                period.daysBack?.let { days ->
                    addOption("--dateafter", "now-\${days}days")
                }
            }

            val response = YoutubeDL.getInstance().execute(request)
            val lines = response.out.lines().filter { it.trim().startsWith("{") }
            val results = mutableListOf<DiscoveredMediaCandidate>()

            for (line in lines) {
                try {
                    val item = jsonFormat.decodeFromString<YtDlpFlatEntry>(line)
                    val resolvedUrl = item.webpageUrl ?: item.url ?: continue
                    val dur = (item.duration ?: 30.0).toInt()
                    if (dur in minDurationSec..maxDurationSec) {
                        results.add(
                            DiscoveredMediaCandidate(
                                id = "yt_\${item.id}",
                                url = resolvedUrl,
                                title = item.title,
                                author = item.uploader ?: "YouTube Creator",
                                platform = HuntPlatform.YOUTUBE,
                                durationSec = dur,
                                viewOrScoreCount = item.viewCount ?: 0L,
                                uploadDateYmd = item.uploadDate ?: ""
                            )
                        )
                    }
                } catch (_: Exception) {
                }
            }
            results
        } catch (e: Exception) {
            Log.w(TAG, "YouTube tag search warning for '$queryTags': \${e.message}")
            emptyList()
        }
    }

    private suspend fun searchRedditVideosByTags(
        queryTags: String,
        sortOrder: ContentSortOrder,
        period: SearchTimePeriod,
        maxDurationSec: Int
    ): List<DiscoveredMediaCandidate> = withContext(Dispatchers.IO) {
        try {
            val redditSort = when (sortOrder) {
                ContentSortOrder.POPULAR -> "top"
                ContentSortOrder.NEWEST -> "new"
                ContentSortOrder.VIRAL_VELOCITY -> "hot"
                ContentSortOrder.RELEVANCE -> "relevance"
            }

            val listing: RedditSearchListing = httpClient.get("https://www.reddit.com/search.json") {
                header(HttpHeaders.UserAgent, "android:forge.media.pocket:v1.2.0 (by /u/pocketforge)")
                parameter("q", "\$queryTags site:v.redd.it")
                parameter("sort", redditSort)
                parameter("t", period.redditParam)
                parameter("limit", 25)
            }.body()

            val sdf = SimpleDateFormat("yyyyMMdd", Locale.US)

            listing.data?.children
                ?.mapNotNull { it.data }
                ?.filter { it.isVideo && !it.over18 }
                ?.mapNotNull { post ->
                    val video = post.media?.redditVideo ?: return@mapNotNull null
                    val url = video.fallbackUrl ?: return@mapNotNull null
                    if (video.duration <= 0 || video.duration > maxDurationSec) return@mapNotNull null

                    val dateStr = if (post.createdUtc > 0) {
                        sdf.format(java.util.Date((post.createdUtc * 1000).toLong()))
                    } else {
                        ""
                    }

                    DiscoveredMediaCandidate(
                        id = "rd_\${post.id}",
                        url = url,
                        title = post.title,
                        author = "u/\${post.author}",
                        platform = HuntPlatform.REDDIT,
                        durationSec = video.duration,
                        viewOrScoreCount = post.score,
                        uploadDateYmd = dateStr
                    )
                } ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "Reddit tag search warning for '$queryTags': \${e.message}")
            emptyList()
        }
    }

    private fun searchPikabuByTags(
        queryTags: String,
        sortOrder: ContentSortOrder,
        period: SearchTimePeriod,
        maxDurationSec: Int
    ): List<DiscoveredMediaCandidate> {
        return try {
            val encodedTag = java.net.URLEncoder.encode(queryTags.trim(), "UTF-8")
            val sortPath = when (sortOrder) {
                ContentSortOrder.NEWEST -> "?n=4"
                ContentSortOrder.POPULAR -> "?r=4"
                else -> ""
            }
            val pikabuSearchUrl = "https://pikabu.ru/tag/\$encodedTag/video\$sortPath"

            val request = YoutubeDLRequest(pikabuSearchUrl).apply {
                addOption("--dump-json")
                addOption("--playlist-end", "12")
                addOption("--skip-download")
                period.daysBack?.let { days ->
                    addOption("--dateafter", "now-\${days}days")
                }
            }

            val response = YoutubeDL.getInstance().execute(request)
            val lines = response.out.lines().filter { it.trim().startsWith("{") }
            val results = mutableListOf<DiscoveredMediaCandidate>()

            for (line in lines) {
                try {
                    val item = jsonFormat.decodeFromString<YtDlpFlatEntry>(line)
                    val resolvedUrl = item.webpageUrl ?: item.url ?: continue
                    val dur = (item.duration ?: 30.0).toInt()
                    if (dur <= maxDurationSec) {
                        results.add(
                            DiscoveredMediaCandidate(
                                id = "pk_\${item.id}",
                                url = resolvedUrl,
                                title = item.title,
                                author = item.uploader ?: "Pikabu Author",
                                platform = HuntPlatform.PIKABU,
                                durationSec = dur,
                                viewOrScoreCount = item.viewCount ?: 0L,
                                uploadDateYmd = item.uploadDate ?: ""
                            )
                        )
                    }
                } catch (_: Exception) {
                }
            }
            results
        } catch (e: Exception) {
            Log.w(TAG, "Pikabu tag search warning for '$queryTags': \${e.message}")
            emptyList()
        }
    }

    private fun computeViralVelocityScore(item: DiscoveredMediaCandidate): Double {
        if (item.uploadDateYmd.length != 8) return item.viewOrScoreCount.toDouble()
        return try {
            val sdf = SimpleDateFormat("yyyyMMdd", Locale.US)
            val uploadTime = sdf.parse(item.uploadDateYmd)?.time ?: return item.viewOrScoreCount.toDouble()
            val daysOld = ((System.currentTimeMillis() - uploadTime) / (1000.0 * 3600.0 * 24.0)).coerceAtLeast(0.5)
            item.viewOrScoreCount.toDouble() / daysOld
        } catch (_: Exception) {
            item.viewOrScoreCount.toDouble()
        }
    }

    suspend fun listAutopilotSubscriptions(): List<AutopilotTagSubscription> = stateMutex.withLock {
        if (!subscriptionsFile.exists()) return@withLock emptyList()
        try {
            jsonFormat.decodeFromString<List<AutopilotTagSubscription>>(subscriptionsFile.readText())
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun addAutopilotSubscription(sub: AutopilotTagSubscription): List<AutopilotTagSubscription> =
        stateMutex.withLock {
            val current = if (subscriptionsFile.exists()) {
                try {
                    jsonFormat.decodeFromString<List<AutopilotTagSubscription>>(subscriptionsFile.readText())
                        .toMutableList()
                } catch (_: Exception) {
                    mutableListOf()
                }
            } else {
                mutableListOf()
            }
            current.removeAll { it.queryTags.equals(sub.queryTags, ignoreCase = true) }
            current.add(sub)
            subscriptionsFile.writeText(jsonFormat.encodeToString(current))
            current
        }

    suspend fun removeAutopilotSubscription(indexOrTag: String): List<AutopilotTagSubscription> =
        stateMutex.withLock {
            if (!subscriptionsFile.exists()) return@withLock emptyList()
            val current = try {
                jsonFormat.decodeFromString<List<AutopilotTagSubscription>>(subscriptionsFile.readText())
                    .toMutableList()
            } catch (_: Exception) {
                mutableListOf()
            }
            val idx = indexOrTag.toIntOrNull()
            if (idx != null && idx in 1..current.size) {
                current.removeAt(idx - 1)
            } else {
                current.removeAll { it.queryTags.equals(indexOrTag, ignoreCase = true) }
            }
            subscriptionsFile.writeText(jsonFormat.encodeToString(current))
            current
        }

    private suspend fun loadSeenIds(): Set<String> = stateMutex.withLock {
        if (!seenIdsFile.exists()) return@withLock emptySet()
        seenIdsFile.readLines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    private suspend fun markCandidatesAsSeen(ids: List<String>) = stateMutex.withLock {
        if (ids.isEmpty()) return@withLock
        val existing = if (seenIdsFile.exists()) {
            seenIdsFile.readLines().toMutableSet()
        } else {
            mutableSetOf()
        }
        existing.addAll(ids)
        val trimmed = existing.toList().takeLast(3000)
        seenIdsFile.writeText(trimmed.joinToString("\\n"))
    }
}`,
  },

  // ================= STEP 4 FILES =================
  {
    step: 4,
    path: 'app/src/main/java/forge/media/pocket/subtitles/SubtitleAssTransformer.kt',
    language: 'kotlin',
    description:
      'Modular, movable ASS Karaoke Subtitle Engine: toggleable on/off, movable across 5 vertical zones or exact custom (X,Y) pixels so it never covers content, switchable between 3-4 word Punch mode and Classic lines, with Gemini 2.5 Flash audio fallback (4.2A).',
    content: `package forge.media.pocket.subtitles

import android.content.Context
import android.util.Base64
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import forge.media.pocket.config.NodeConfigStore
import forge.media.pocket.vision.UpperBoxFramingPlan
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.roundToInt

enum class SubtitleDensityMode {
    PUNCH_3_TO_4_WORDS,
    CLASSIC_6_TO_8_WORDS
}

enum class SubtitlePlacementZone(val defaultY: Int, val label: String) {
    ADAPTIVE_AUTO(960, "Auto (Avoids Faces)"),
    TOP_SAFE(240, "Top (Y=240)"),
    UPPER_THIRD(760, "Upper Box Bottom (Y=760)"),
    CENTER_SEAM(960, "Center Seam (Y=960)"),
    LOWER_BOX_MID(1380, "Lower Box Mid (Y=1380)"),
    BOTTOM_SAFE(1640, "Bottom Safe (Y=1640)"),
    CUSTOM_XY(960, "Custom X/Y")
}

data class SubtitleLayerConfig(
    val enabled: Boolean = true,
    val densityMode: SubtitleDensityMode = SubtitleDensityMode.PUNCH_3_TO_4_WORDS,
    val placementZone: SubtitlePlacementZone = SubtitlePlacementZone.ADAPTIVE_AUTO,
    val customX: Int = 540,
    val customY: Int = 960,
    val fontSizePx: Int = 68,
    val preferredLanguage: String = "ru,en"
) {
    fun resolveCoordinates(framingPlan: UpperBoxFramingPlan?): Pair<Int, Int> {
        val x = customX.coerceIn(140, 940)
        val y = when (placementZone) {
            SubtitlePlacementZone.ADAPTIVE_AUTO -> {
                if (framingPlan?.hasBottomFaceRisk == true) 1100 else 940
            }
            SubtitlePlacementZone.CUSTOM_XY -> customY.coerceIn(120, 1800)
            else -> placementZone.defaultY
        }
        return Pair(x, y)
    }
}

data class TimedWord(
    val text: String,
    val startMs: Long,
    val endMs: Long
)

data class SubtitlePreparationResult(
    val assFile: File?,
    val plainTranscriptText: String
)

class SubtitleAssTransformer(
    private val context: Context,
    private val configStore: NodeConfigStore
) {

    companion object {
        private const val TAG = "SubtitleAssTransformer"
    }

    private val stagingDir = File(context.cacheDir, "work_staging").apply { mkdirs() }

    private val jsonFormat = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val httpClient = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(jsonFormat)
        }
    }

    @Serializable
    private data class GeminiGenerateRequest(
        val contents: List<GeminiContent>,
        val generationConfig: GeminiGenerationConfig
    )

    @Serializable
    private data class GeminiContent(
        val parts: List<GeminiPart>
    )

    @Serializable
    private data class GeminiPart(
        val text: String? = null,
        val inlineData: GeminiInlineData? = null
    )

    @Serializable
    private data class GeminiInlineData(
        val mimeType: String,
        val data: String
    )

    @Serializable
    private data class GeminiGenerationConfig(
        val temperature: Double = 0.1,
        val responseMimeType: String = "application/json"
    )

    @Serializable
    private data class GeminiGenerateResponse(
        val candidates: List<GeminiCandidate> = emptyList()
    )

    @Serializable
    private data class GeminiCandidate(
        val content: GeminiContent? = null
    )

    @Serializable
    private data class TranscribedSegmentDto(
        val startSec: Double,
        val endSec: Double,
        val text: String
    )

    suspend fun prepareAssSubtitles(
        sourceUrl: String,
        localVideoFile: File,
        config: SubtitleLayerConfig,
        framingPlan: UpperBoxFramingPlan?,
        cachedWords: List<TimedWord>? = null
    ): Pair<SubtitlePreparationResult, List<TimedWord>> = withContext(Dispatchers.IO) {
        val timedWords: List<TimedWord> = if (!cachedWords.isNullOrEmpty()) {
            cachedWords
        } else {
            val vttFile = tryDownloadYoutubeVtt(sourceUrl, config.preferredLanguage)
            if (vttFile != null && vttFile.exists()) {
                val parsed = parseWebVttToTimedWords(vttFile.readText())
                vttFile.delete()
                parsed
            } else {
                transcribeViaGeminiAudioFallback(localVideoFile)
            }
        }

        val transcriptPlain = timedWords.joinToString(" ") { it.text }

        if (!config.enabled || timedWords.isEmpty()) {
            return@withContext Pair(
                SubtitlePreparationResult(assFile = null, plainTranscriptText = transcriptPlain),
                timedWords
            )
        }

        val (posX, posY) = config.resolveCoordinates(framingPlan)
        val assFile = File(stagingDir, "subs_\${System.currentTimeMillis()}.ass")
        val assContent = buildKaraokeAssDocument(
            words = timedWords,
            config = config,
            posX = posX,
            posY = posY
        )
        assFile.writeText(assContent, Charsets.UTF_8)
        Pair(
            SubtitlePreparationResult(assFile = assFile, plainTranscriptText = transcriptPlain),
            timedWords
        )
    }

    private fun tryDownloadYoutubeVtt(sourceUrl: String, langPref: String): File? {
        return try {
            val baseName = "vtt_\${System.currentTimeMillis()}"
            val outTemplate = File(stagingDir, "$baseName.%(ext)s").absolutePath
            val request = YoutubeDLRequest(sourceUrl).apply {
                addOption("--skip-download")
                addOption("--write-auto-sub")
                addOption("--write-sub")
                addOption("--sub-langs", langPref)
                addOption("--sub-format", "vtt")
                addOption("--no-playlist")
                addOption("-o", outTemplate)
            }
            YoutubeDL.getInstance().execute(request)

            stagingDir.listFiles { f ->
                f.name.startsWith(baseName) && f.extension.equals("vtt", ignoreCase = true)
            }?.firstOrNull()
        } catch (e: Exception) {
            Log.w(TAG, "Native VTT download unavailable for $sourceUrl: \${e.message}")
            null
        }
    }

    fun parseWebVttToTimedWords(vttRaw: String): List<TimedWord> {
        val lines = vttRaw.lines()
        val cueHeaderRegex = Regex(
            """(\\d{2}:\\d{2}:\\d{2}\\.\\d{3}|\\d{2}:\\d{2}\\.\\d{3})\\s*-->\\s*(\\d{2}:\\d{2}:\\d{2}\\.\\d{3}|\\d{2}:\\d{2}\\.\\d{3})"""
        )
        val inlineTimeTagRegex = Regex("""<(\\d{2}:\\d{2}:\\d{2}\\.\\d{3}|\\d{2}:\\d{2}\\.\\d{3})>""")
        val htmlTagStripRegex = Regex("""<[^>]+>""")

        val extractedWords = mutableListOf<TimedWord>()
        var i = 0

        while (i < lines.size) {
            val line = lines[i].trim()
            val headerMatch = cueHeaderRegex.find(line)
            if (headerMatch != null) {
                val cueStartMs = parseVttTimestampMs(headerMatch.groupValues[1])
                val cueEndMs = parseVttTimestampMs(headerMatch.groupValues[2])
                i++

                val cueLines = mutableListOf<String>()
                while (i < lines.size && lines[i].isNotBlank() && !cueHeaderRegex.containsMatchIn(lines[i])) {
                    cueLines.add(lines[i].trim())
                    i++
                }

                val joinedCue = cueLines.joinToString(" ")
                if (inlineTimeTagRegex.containsMatchIn(joinedCue)) {
                    val tokens = joinedCue.split(Regex("""(?=<\\d{2}:)"""))
                    var currentWordStart = cueStartMs

                    for (token in tokens) {
                        val timeMatch = inlineTimeTagRegex.find(token)
                        val tokenTimeMs = if (timeMatch != null) {
                            parseVttTimestampMs(timeMatch.groupValues[1])
                        } else {
                            currentWordStart
                        }
                        val cleanWord = token.replace(htmlTagStripRegex, "").trim()
                        if (cleanWord.isNotEmpty()) {
                            val endEst = (tokenTimeMs + 280L).coerceAtMost(cueEndMs.coerceAtLeast(tokenTimeMs + 120L))
                            extractedWords.add(
                                TimedWord(
                                    text = cleanWord,
                                    startMs = currentWordStart,
                                    endMs = endEst
                                )
                            )
                            currentWordStart = tokenTimeMs
                        }
                    }
                } else {
                    val cleanText = joinedCue.replace(htmlTagStripRegex, "").trim()
                    val words = cleanText.split(Regex("""\\s+""")).filter { it.isNotBlank() }
                    if (words.isNotEmpty()) {
                        val totalDur = (cueEndMs - cueStartMs).coerceAtLeast(words.size * 120L)
                        val step = totalDur / words.size
                        words.forEachIndexed { idx, w ->
                            val wStart = cueStartMs + idx * step
                            val wEnd = if (idx == words.lastIndex) cueEndMs else wStart + step
                            extractedWords.add(TimedWord(w, wStart, wEnd))
                        }
                    }
                }
            } else {
                i++
            }
        }

        return deduplicateOverlappingWords(extractedWords)
    }

    private fun deduplicateOverlappingWords(raw: List<TimedWord>): List<TimedWord> {
        if (raw.isEmpty()) return emptyList()
        val result = mutableListOf<TimedWord>()
        for (word in raw) {
            val prev = result.lastOrNull()
            if (prev != null &&
                prev.text.equals(word.text, ignoreCase = true) &&
                kotlin.math.abs(word.startMs - prev.startMs) < 450L
            ) {
                continue
            }
            result.add(word)
        }
        return result
    }

    private suspend fun transcribeViaGeminiAudioFallback(videoFile: File): List<TimedWord> =
        withContext(Dispatchers.IO) {
            val apiKey = configStore.getGeminiApiKey()
            if (apiKey.isBlank()) return@withContext emptyList()

            val tempAudio = File(stagingDir, "stt_\${System.currentTimeMillis()}.ogg")
            try {
                val extracted = extractCompactOggAudio(videoFile, tempAudio)
                if (!extracted || !tempAudio.exists()) return@withContext emptyList()

                val base64Audio = Base64.encodeToString(tempAudio.readBytes(), Base64.NO_WRAP)
                val prompt = """
                    Transcribe the speech in this audio into short phrases of 3 to 5 words.
                    Return strictly a JSON array of objects with fields:
                    [{"startSec": 0.0, "endSec": 1.4, "text": "phrase text"}]
                """.trimIndent()

                val requestPayload = GeminiGenerateRequest(
                    contents = listOf(
                        GeminiContent(
                            parts = listOf(
                                GeminiPart(text = prompt),
                                GeminiPart(
                                    inlineData = GeminiInlineData(
                                        mimeType = "audio/ogg",
                                        data = base64Audio
                                    )
                                )
                            )
                        )
                    ),
                    generationConfig = GeminiGenerationConfig(
                        temperature = 0.1,
                        responseMimeType = "application/json"
                    )
                )

                val endpoint =
                    "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=\$apiKey"

                val response: GeminiGenerateResponse = httpClient.post(endpoint) {
                    contentType(ContentType.Application.Json)
                    setBody(requestPayload)
                }.body()

                val rawJson = response.candidates.firstOrNull()
                    ?.content?.parts?.firstOrNull()?.text
                    ?: return@withContext emptyList()

                val segments = jsonFormat.decodeFromString<List<TranscribedSegmentDto>>(rawJson)
                val words = mutableListOf<TimedWord>()

                for (seg in segments) {
                    val tokens = seg.text.trim().split(Regex("""\\s+""")).filter { it.isNotBlank() }
                    if (tokens.isEmpty()) continue
                    val startMs = (seg.startSec * 1000.0).roundToInt().toLong()
                    val endMs = (seg.endSec * 1000.0).roundToInt().toLong().coerceAtLeast(startMs + 200L)
                    val step = ((endMs - startMs) / tokens.size).coerceAtLeast(80L)

                    tokens.forEachIndexed { idx, token ->
                        val wStart = startMs + idx * step
                        val wEnd = if (idx == tokens.lastIndex) endMs else wStart + step
                        words.add(TimedWord(token, wStart, wEnd))
                    }
                }
                words
            } catch (e: Exception) {
                Log.e(TAG, "Gemini audio fallback transcription failed", e)
                emptyList()
            } finally {
                tempAudio.delete()
            }
        }

    private suspend fun extractCompactOggAudio(inputVideo: File, outputOgg: File): Boolean =
        suspendCancellableCoroutine { cont ->
            val cmd = buildString {
                append("-hide_banner -loglevel error -nostdin -y ")
                append("-t 65 ")
                append("-i \\"\${inputVideo.absolutePath}\\" ")
                append("-vn -ac 1 -ar 16000 -c:a libopus -b:a 24k ")
                append("\\"\${outputOgg.absolutePath}\\"")
            }
            FFmpegKit.executeAsync(cmd) { session ->
                if (cont.isActive) cont.resume(ReturnCode.isSuccess(session.returnCode))
            }
        }

    private fun buildKaraokeAssDocument(
        words: List<TimedWord>,
        config: SubtitleLayerConfig,
        posX: Int,
        posY: Int
    ): String {
        val wordsPerCard = when (config.densityMode) {
            SubtitleDensityMode.PUNCH_3_TO_4_WORDS -> 4
            SubtitleDensityMode.CLASSIC_6_TO_8_WORDS -> 7
        }

        val header = """
            [Script Info]
            ScriptType: v4.00+
            PlayResX: 1080
            PlayResY: 1920
            WrapStyle: 0
            ScaledBorderAndShadow: yes

            [V4+ Styles]
            Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
            Style: KaraokeStyle,Sans-Serif,\${config.fontSizePx},&H0000FFFF,&H00FFFFFF,&H00000000,&H80000000,-1,0,0,0,100,100,1,0,1,5,2,5,40,40,40,1

            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
        """.trimIndent()

        val sb = StringBuilder(header).append("\\n")
        val chunks = words.chunked(wordsPerCard)

        for (chunk in chunks) {
            if (chunk.isEmpty()) continue
            val chunkStartMs = chunk.first().startMs
            val chunkEndMs = chunk.last().endMs.coerceAtLeast(chunkStartMs + 250L)

            val startAss = formatAssTime(chunkStartMs)
            val endAss = formatAssTime(chunkEndMs)

            val karaokeText = buildString {
                append("{\\\\an5\\\\pos($posX,$posY)}")
                for (w in chunk) {
                    val durCentiseconds = ((w.endMs - w.startMs) / 10L).coerceIn(8L, 250L)
                    val tag = if (config.densityMode == SubtitleDensityMode.PUNCH_3_TO_4_WORDS) {
                        "\\\\k$durCentiseconds"
                    } else {
                        "\\\\kf$durCentiseconds"
                    }
                    val sanitized = w.text
                        .replace("{", "")
                        .replace("}", "")
                        .replace("\\\\", "")
                    append("{$tag}$sanitized ")
                }
            }.trim()

            sb.append("Dialogue: 0,$startAss,$endAss,KaraokeStyle,,0,0,0,,$karaokeText\\n")
        }

        return sb.toString()
    }

    private fun parseVttTimestampMs(raw: String): Long {
        val parts = raw.trim().split(":")
        return try {
            if (parts.size == 3) {
                val h = parts[0].toLong()
                val m = parts[1].toLong()
                val secParts = parts[2].split(".")
                val s = secParts[0].toLong()
                val ms = secParts.getOrElse(1) { "0" }.padEnd(3, '0').take(3).toLong()
                (h * 3600 + m * 60 + s) * 1000L + ms
            } else {
                val m = parts[0].toLong()
                val secParts = parts[1].split(".")
                val s = secParts[0].toLong()
                val ms = secParts.getOrElse(1) { "0" }.padEnd(3, '0').take(3).toLong()
                (m * 60 + s) * 1000L + ms
            }
        } catch (_: Exception) {
            0L
        }
    }

    private fun formatAssTime(ms: Long): String {
        val clamped = ms.coerceAtLeast(0L)
        val totalSec = clamped / 1000L
        val h = totalSec / 3600L
        val m = (totalSec % 3600L) / 60L
        val s = totalSec % 60L
        val cs = (clamped % 1000L) / 10L
        return String.format(Locale.US, "%d:%02d:%02d.%02d", h, m, s, cs)
    }
}`,
  },
  {
    step: 4,
    path: 'app/src/main/java/forge/media/pocket/compositor/FFmpegHardwareEngine.kt',
    language: 'kotlin',
    description:
      'Composable Layer Hardware Compositor with all 4 Assembly Modes (Split, Fullscreen Edit, Brainrot Voiceover, and Distorted Original Fullscreen) + Micro-Zoom Content-ID breaker + >350ms silence trimmer on Qualcomm h264_mediacodec.',
    content: `package forge.media.pocket.compositor

import android.content.Context
import android.media.MediaMetadataRetriever
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import forge.media.pocket.subtitles.SubtitleLayerConfig
import forge.media.pocket.vision.UpperBoxFramingPlan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.random.Random

enum class VideoLayoutLayer(val label: String) {
    SPLIT_VSTACK("Сплит 50/50 (Спикер + Залипалка)"),
    FULLSCREEN_EDIT("Полноэкранный Эдит 9:16"),
    BRAINROT_VOICEOVER("Геймплей на весь экран + Озвучка"),
    DISTORTED_ORIGINAL("Искажённый оригинал (Анти-Content ID)")
}

enum class AdInsertionMode {
    NONE,
    BANNER_OVERLAY,
    MIDROLL_SPLICE,
    MIDROLL_PIP_OVERLAY,
    PINNED_COMMENT_ONLY
}

data class AdLayerConfig(
    val mode: AdInsertionMode = AdInsertionMode.NONE,
    val bannerText: String = "SPONSORED • LINK IN PINNED COMMENT",
    val bannerImageFile: File? = null,
    val bannerStartSec: Int = 5,
    val bannerEndSec: Int = 15,
    val midrollClipFile: File? = null,
    val sponsorTrackingUrl: String = ""
)

data class AntiDetectDspLayerConfig(
    val enabled: Boolean = true,
    val enableAudioPitchTempoMicroJitter: Boolean = true,
    val enableSilenceTrimming350ms: Boolean = true,
    val enableMicroZoom: Boolean = true,
    val enableVideoNoiseInjection: Boolean = true,
    val enableMicroColorEqShift: Boolean = true
)

data class ModularRenderConfig(
    val layoutLayer: VideoLayoutLayer = VideoLayoutLayer.SPLIT_VSTACK,
    val enableSmartFaceCrop: Boolean = true,
    val subtitleLayer: SubtitleLayerConfig = SubtitleLayerConfig(),
    val adLayer: AdLayerConfig = AdLayerConfig(),
    val dspLayer: AntiDetectDspLayerConfig = AntiDetectDspLayerConfig(),
    val enableAiMetadataGeneration: Boolean = true,
    val videoBitrateKbps: Int = 6000
)

class FFmpegHardwareEngine(private val context: Context) {

    companion object {
        private const val TAG = "FFmpegHardwareEngine"
    }

    private val stagingDir = File(context.cacheDir, "work_staging").apply { mkdirs() }

    suspend fun renderPipeline(
        primaryVideoFile: File,
        clutterReelFile: File?,
        framingPlan: UpperBoxFramingPlan,
        assSubtitleFile: File?,
        config: ModularRenderConfig,
        isPreviewPass: Boolean
    ): File = withContext(Dispatchers.IO) {
        val totalDurationSec = probeDurationSeconds(primaryVideoFile).coerceAtLeast(5.0)
        val renderDurationSec = if (isPreviewPass) {
            5.0
        } else {
            totalDurationSec.coerceAtMost(90.0)
        }

        val previewStartOffsetSec = if (isPreviewPass && totalDurationSec > 8.0) 2.0 else 0.0

        val outFile = File(
            stagingDir,
            if (isPreviewPass) {
                "preview_5s_\${System.currentTimeMillis()}.mp4"
            } else {
                "final_render_\${System.currentTimeMillis()}.mp4"
            }
        )

        val command = buildModularFFmpegCommand(
            primaryVideoFile = primaryVideoFile,
            clutterReelFile = clutterReelFile,
            framingPlan = framingPlan,
            assSubtitleFile = assSubtitleFile,
            config = config,
            startOffsetSec = previewStartOffsetSec,
            durationSec = renderDurationSec,
            isPreviewPass = isPreviewPass,
            outputFile = outFile
        )

        Log.i(TAG, "Executing Qualcomm h264_mediacodec command: \$command")

        val success = executeHardwareCommand(command)
        if (!success || !outFile.exists() || outFile.length() == 0L) {
            throw IllegalStateException(
                "Qualcomm h264_mediacodec hardware render failed (Option 2B strict mode)."
            )
        }

        outFile
    }

    suspend fun extractCleanAudioTrack(primaryVideoFile: File, outputM4a: File): Boolean =
        suspendCancellableCoroutine { cont ->
            val cmd = buildString {
                append("-hide_banner -loglevel error -nostdin -y ")
                append("-i \\"\${primaryVideoFile.absolutePath}\\" ")
                append("-vn -c:a aac -b:a 192k -ar 48000 ")
                append("\\"\${outputM4a.absolutePath}\\"")
            }
            FFmpegKit.executeAsync(cmd) { session ->
                if (cont.isActive) cont.resume(ReturnCode.isSuccess(session.returnCode))
            }
        }

    private fun buildModularFFmpegCommand(
        primaryVideoFile: File,
        clutterReelFile: File?,
        framingPlan: UpperBoxFramingPlan,
        assSubtitleFile: File?,
        config: ModularRenderConfig,
        startOffsetSec: Double,
        durationSec: Double,
        isPreviewPass: Boolean,
        outputFile: File
    ): String {
        val inputs = mutableListOf<String>()
        val filterStages = mutableListOf<String>()

        val seekArg = if (startOffsetSec > 0.0) {
            String.format(Locale.US, "-ss %.2f ", startOffsetSec)
        } else {
            ""
        }
        inputs.add("\$seekArg-t \${String.format(Locale.US, "%.2f", durationSec)} -i \\"\${primaryVideoFile.absolutePath}\\"")

        var nextInputIndex = 1
        var clutterInputIndex = -1
        val needsClutterStream =
            (config.layoutLayer == VideoLayoutLayer.SPLIT_VSTACK ||
                config.layoutLayer == VideoLayoutLayer.BRAINROT_VOICEOVER) &&
                clutterReelFile != null

        if (needsClutterStream) {
            clutterInputIndex = nextInputIndex++
            inputs.add("-stream_loop -1 -t \${String.format(Locale.US, "%.2f", durationSec)} -i \\"\${clutterReelFile!!.absolutePath}\\"")
        }

        var bannerInputIndex = -1
        if (config.adLayer.mode == AdInsertionMode.BANNER_OVERLAY &&
            config.adLayer.bannerImageFile?.exists() == true
        ) {
            bannerInputIndex = nextInputIndex++
            inputs.add("-i \\"\${config.adLayer.bannerImageFile.absolutePath}\\"")
        }

        var midrollInputIndex = -1
        val hasMidrollClip = (config.adLayer.mode == AdInsertionMode.MIDROLL_SPLICE ||
            config.adLayer.mode == AdInsertionMode.MIDROLL_PIP_OVERLAY) &&
            config.adLayer.midrollClipFile?.exists() == true &&
            !isPreviewPass
        if (hasMidrollClip) {
            midrollInputIndex = nextInputIndex++
            inputs.add("-t 3.0 -i \\"\${config.adLayer.midrollClipFile!!.absolutePath}\\"")
        }

        when (config.layoutLayer) {
            VideoLayoutLayer.SPLIT_VSTACK -> {
                val upperFilter = framingPlan.ffmpegVideoFilter
                filterStages.add("[0:v:0]fps=30,\$upperFilter[v_top]")

                if (clutterInputIndex >= 0) {
                    filterStages.add(
                        "[\$clutterInputIndex:v:0]fps=30,scale=1080:960:force_original_aspect_ratio=increase:flags=lanczos," +
                            "crop=1080:960:(iw-1080)/2:(ih-960)/2,setsar=1[v_bot]"
                    )
                    filterStages.add("[v_top][v_bot]vstack=inputs=2[v_base]")
                } else {
                    filterStages.add("[v_top]pad=1080:1920:0:0:color=#08080C[v_base]")
                }
            }

            VideoLayoutLayer.FULLSCREEN_EDIT -> {
                filterStages.add(
                    "[0:v:0]fps=30,scale=1080:1920:force_original_aspect_ratio=increase:flags=lanczos," +
                        "crop=1080:1920:(iw-1080)/2:(ih-1920)/2,setsar=1[v_base]"
                )
            }

            VideoLayoutLayer.BRAINROT_VOICEOVER -> {
                val srcIdx = if (clutterInputIndex >= 0) clutterInputIndex else 0
                filterStages.add(
                    "[\$srcIdx:v:0]fps=30,scale=1080:1920:force_original_aspect_ratio=increase:flags=lanczos," +
                        "crop=1080:1920:(iw-1080)/2:(ih-1920)/2,setsar=1[v_base]"
                )
            }

            VideoLayoutLayer.DISTORTED_ORIGINAL -> {
                filterStages.add(
                    "[0:v:0]fps=30,scale=1080:1920:force_original_aspect_ratio=decrease:flags=lanczos," +
                        "pad=1080:1920:(ow-iw)/2:(oh-ih)/2:color=#0A0A0E,setsar=1[v_base]"
                )
            }
        }

        var currentVideoLabel = "[v_base]"

        if (config.dspLayer.enabled) {
            val videoDspFilters = mutableListOf<String>()
            if (config.dspLayer.enableMicroZoom) {
                videoDspFilters.add("scale=1118:1988:flags=bilinear,crop=1080:1920:18:34")
            }
            if (config.dspLayer.enableVideoNoiseInjection) {
                videoDspFilters.add("noise=alls=3:allf=t+u")
            }
            if (config.dspLayer.enableMicroColorEqShift) {
                val contrast = String.format(Locale.US, "%.3f", 1.012 + Random.nextDouble(0.0, 0.012))
                val saturation = String.format(Locale.US, "%.3f", 1.015 + Random.nextDouble(0.0, 0.015))
                videoDspFilters.add("eq=contrast=\$contrast:saturation=\$saturation")
            }
            if (videoDspFilters.isNotEmpty()) {
                filterStages.add("\$currentVideoLabel\${videoDspFilters.joinToString(",")}[v_dsp]")
                currentVideoLabel = "[v_dsp]"
            }
        }

        if (config.subtitleLayer.enabled && assSubtitleFile != null && assSubtitleFile.exists()) {
            val escapedAssPath = assSubtitleFile.absolutePath
                .replace("\\\\", "/")
                .replace(":", "\\\\:")
                .replace("'", "\\\\'")
            filterStages.add("\$currentVideoLabel" + "ass=filename='\$escapedAssPath'[v_sub]")
            currentVideoLabel = "[v_sub]"
        }

        var currentAudioLabel = "[0:a:0]"

        val audioFilters = mutableListOf<String>()
        audioFilters.add("aresample=48000")
        audioFilters.add("aformat=sample_fmts=fltp:channel_layouts=stereo")

        if (config.dspLayer.enabled) {
            if (config.dspLayer.enableSilenceTrimming350ms) {
                audioFilters.add(
                    "silenceremove=start_periods=1:start_duration=0.15:start_threshold=-45dB:" +
                        "stop_periods=-1:stop_duration=0.35:stop_threshold=-45dB"
                )
            }
            if (config.dspLayer.enableAudioPitchTempoMicroJitter) {
                val pitchFactor = 1.015
                val tempoFactor = 1.008
                audioFilters.add("asetrate=48000*\$pitchFactor,aresample=48000,atempo=\$tempoFactor")
            }
        }

        filterStages.add("\$currentAudioLabel\${audioFilters.joinToString(",")}[a_main]")
        currentAudioLabel = "[a_main]"

        when (config.adLayer.mode) {
            AdInsertionMode.BANNER_OVERLAY -> {
                val startT = if (isPreviewPass) 0 else config.adLayer.bannerStartSec
                val endT = if (isPreviewPass) 5 else config.adLayer.bannerEndSec

                if (bannerInputIndex >= 0) {
                    filterStages.add(
                        "[\$bannerInputIndex:v:0]scale=960:-2[v_banner];" +
                            "\$currentVideoLabel[v_banner]overlay=(W-w)/2:120:enable='between(t,\$startT,\$endT)'[v_ad]"
                    )
                    currentVideoLabel = "[v_ad]"
                } else {
                    val safeBannerText = config.adLayer.bannerText
                        .replace(":", "\\\\:")
                        .replace("'", "")
                    filterStages.add(
                        "\$currentVideoLabel" +
                            "drawbox=x=60:y=110:w=960:h=96:color=black@0.78:t=fill:enable='between(t,\$startT,\$endT)'," +
                            "drawtext=text='\$safeBannerText':fontsize=36:fontcolor=yellow:" +
                            "x=(w-text_w)/2:y=142:enable='between(t,\$startT,\$endT)'[v_ad]"
                    )
                    currentVideoLabel = "[v_ad]"
                }
            }

            AdInsertionMode.MIDROLL_PIP_OVERLAY -> {
                if (midrollInputIndex >= 0) {
                    val midPoint = String.format(Locale.US, "%.1f", (durationSec / 2.0).coerceAtLeast(2.0))
                    val midEnd = String.format(Locale.US, "%.1f", (durationSec / 2.0 + 3.0))
                    filterStages.add(
                        "[\$midrollInputIndex:v:0]fps=30,scale=1080:1920:force_original_aspect_ratio=increase," +
                            "crop=1080:1920,setpts=PTS-STARTPTS+(\$midPoint/TB)[v_mid];" +
                            "\$currentVideoLabel[v_mid]overlay=0:0:enable='between(t,\$midPoint,\$midEnd)'[v_ad]"
                    )
                    currentVideoLabel = "[v_ad]"
                }
            }

            AdInsertionMode.MIDROLL_SPLICE -> {
                if (midrollInputIndex >= 0 && durationSec >= 8.0) {
                    val splitSec = String.format(Locale.US, "%.2f", durationSec / 2.0)
                    filterStages.add(
                        "\$currentVideoLabel" + "split=2[v_p1_raw][v_p2_raw];" +
                            "[v_p1_raw]trim=0:\$splitSec,setpts=PTS-STARTPTS[v_p1];" +
                            "[v_p2_raw]trim=\$splitSec,setpts=PTS-STARTPTS[v_p2];" +
                            "[\$midrollInputIndex:v:0]fps=30,scale=1080:1920:force_original_aspect_ratio=increase," +
                            "crop=1080:1920,setsar=1,setpts=PTS-STARTPTS[v_mid];" +
                            "\$currentAudioLabel" + "asplit=2[a_p1_raw][a_p2_raw];" +
                            "[a_p1_raw]atrim=0:\$splitSec,asetpts=PTS-STARTPTS[a_p1];" +
                            "[a_p2_raw]atrim=\$splitSec,asetpts=PTS-STARTPTS[a_p2];" +
                            "[\$midrollInputIndex:a:0]aresample=48000,aformat=sample_fmts=fltp:channel_layouts=stereo,asetpts=PTS-STARTPTS[a_mid];" +
                            "[v_p1][a_p1][v_mid][a_mid][v_p2][a_p2]concat=n=3:v=1:a=1[v_spliced][a_spliced]"
                    )
                    currentVideoLabel = "[v_spliced]"
                    currentAudioLabel = "[a_spliced]"
                }
            }

            AdInsertionMode.NONE, AdInsertionMode.PINNED_COMMENT_ONLY -> {
            }
        }

        filterStages.add("\$currentVideoLabel" + "format=nv12[v_out]")

        val filterComplex = filterStages.joinToString(";")

        val safeBitrateKbps = if (isPreviewPass) {
            3500
        } else {
            val maxAllowedKbpsFor48Mb = ((47.0 * 8192.0) / durationSec).toInt() - 160
            config.videoBitrateKbps.coerceAtMost(maxAllowedKbpsFor48Mb.coerceAtLeast(2200))
        }

        return buildString {
            append("-hide_banner -loglevel warning -nostdin -y ")
            append(inputs.joinToString(" "))
            append(" -filter_complex \\"\$filterComplex\\" ")
            append("-map \\"[v_out]\\" -map \\"\$currentAudioLabel\\" ")
            append("-c:v h264_mediacodec -b:v \${safeBitrateKbps}k ")
            append("-c:a aac -b:a 160k -ar 48000 ")
            append("-movflags +faststart ")
            append("\\"\${outputFile.absolutePath}\\"")
        }
    }

    private suspend fun executeHardwareCommand(cmd: String): Boolean =
        suspendCancellableCoroutine { cont ->
            FFmpegKit.executeAsync(cmd) { session ->
                val ok = ReturnCode.isSuccess(session.returnCode)
                if (!ok) {
                    Log.e(TAG, "FFmpeg h264_mediacodec execution failed:\\n\${session.allLogsAsString}")
                }
                if (cont.isActive) cont.resume(ok)
            }
        }

    fun probeDurationSeconds(videoFile: File): Double {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(videoFile.absolutePath)
            val ms = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 30_000L
            ms / 1000.0
        } catch (_: Exception) {
            30.0
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }
}`,
  },

  // ================= STEP 5 FILES =================
  {
    step: 5,
    path: 'app/src/main/java/forge/media/pocket/telegram/GeminiMetadataClient.kt',
    language: 'kotlin',
    description:
      'Gemini 2.5 Flash Client reading dynamic API key from NodeConfigStore: generates 3 distinct Title options, Hashtags, and Pinned Comment with automatic Author / Anime Title / Podcast attribution.',
    content: `package forge.media.pocket.telegram

import android.util.Log
import forge.media.pocket.compositor.AdInsertionMode
import forge.media.pocket.compositor.AdLayerConfig
import forge.media.pocket.config.NodeConfigStore
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class GeneratedSocialMetadata(
    val titles: List<String>,
    val description: String,
    val hashtags: List<String>,
    val sourceAttribution: String,
    val pinnedComment: String
)

class GeminiMetadataClient(private val configStore: NodeConfigStore) {

    companion object {
        private const val TAG = "GeminiMetadataClient"
    }

    private val jsonFormat = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val httpClient = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(jsonFormat)
        }
    }

    @Serializable
    private data class GeminiGenerateRequest(
        val contents: List<GeminiContent>,
        val generationConfig: GeminiGenerationConfig
    )

    @Serializable
    private data class GeminiContent(
        val parts: List<GeminiPart>
    )

    @Serializable
    private data class GeminiPart(
        val text: String
    )

    @Serializable
    private data class GeminiGenerationConfig(
        val temperature: Double = 0.4,
        val responseMimeType: String = "application/json"
    )

    @Serializable
    private data class GeminiGenerateResponse(
        val candidates: List<GeminiCandidate> = emptyList()
    )

    @Serializable
    private data class GeminiCandidate(
        val content: GeminiContent? = null
    )

    suspend fun generateMetadataIfEnabled(
        enabled: Boolean,
        sourceUrl: String,
        originalTitle: String,
        originalAuthor: String,
        sourceTags: List<String>,
        transcriptText: String,
        adConfig: AdLayerConfig
    ): GeneratedSocialMetadata? = withContext(Dispatchers.IO) {
        val apiKey = configStore.getGeminiApiKey()
        if (!enabled || apiKey.isBlank()) {
            return@withContext null
        }

        val promoInstruction = if (
            adConfig.mode == AdInsertionMode.PINNED_COMMENT_ONLY ||
            adConfig.sponsorTrackingUrl.isNotBlank()
        ) {
            "IMPORTANT (Ad Strategy C): Naturally weave this promotional hook and link into 'pinnedComment' and 'description': " +
                "\${adConfig.bannerText} \${adConfig.sponsorTrackingUrl}"
        } else {
            "In 'pinnedComment', include the original author or anime/show title and ask an engaging question."
        }

        val prompt = """
            You are a viral short-form video producer (YouTube Shorts / Reels / TikTok).
            Source URL: \$sourceUrl
            Original Video Title: \$originalTitle
            Original Channel / Author: \$originalAuthor
            Original Tags: \${sourceTags.take(15).joinToString(", ")}
            Spoken Transcript Excerpt: \${transcriptText.take(1500)}
            
            1. Identify the exact creator/speaker name OR anime/movie/game title from the metadata above.
            2. Create 3 different catchy viral titles (under 70 chars each).
            3. \$promoInstruction
            
            Respond strictly in Russian as a JSON object matching this schema:
            {
              "titles": [
                "Вариант названия 1 (интрига)",
                "Вариант названия 2 (прямой хук)",
                "Вариант названия 3 (вопрос/триггер)"
              ],
              "description": "Краткое описание в 1-2 предложения",
              "hashtags": ["#shorts", "#рек", "#интересное"],
              "sourceAttribution": "Автор / Тайтл аниме / Источник",
              "pinnedComment": "Текст для закреплённого комментария (с указанием автора/тайтла и промо-хуком если включён)"
            }
        """.trimIndent()

        try {
            val endpoint =
                "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=\$apiKey"

            val response: GeminiGenerateResponse = httpClient.post(endpoint) {
                contentType(ContentType.Application.Json)
                setBody(
                    GeminiGenerateRequest(
                        contents = listOf(GeminiContent(parts = listOf(GeminiPart(text = prompt)))),
                        generationConfig = GeminiGenerationConfig(
                            temperature = 0.4,
                            responseMimeType = "application/json"
                        )
                    )
                )
            }.body()

            val rawJson = response.candidates.firstOrNull()
                ?.content?.parts?.firstOrNull()?.text
                ?: return@withContext null

            jsonFormat.decodeFromString<GeneratedSocialMetadata>(rawJson)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate Gemini metadata", e)
            null
        }
    }
}`,
  },
  {
    step: 5,
    path: 'app/src/main/java/forge/media/pocket/telegram/TelegramBotWorker.kt',
    language: 'kotlin',
    description:
      'Complete Telegram Bot Controller with Allowed Chat ID security filter, /hunt & /autotags discovery, interactive layer adjustments, uncompressed MP4 document delivery, and Raw Source Pack export.',
    content: `package forge.media.pocket.telegram

import android.content.Context
import android.os.Environment
import android.util.Log
import forge.media.pocket.clutter.ClutterEntropyFilter
import forge.media.pocket.compositor.AdInsertionMode
import forge.media.pocket.compositor.AdLayerConfig
import forge.media.pocket.compositor.FFmpegHardwareEngine
import forge.media.pocket.compositor.ModularRenderConfig
import forge.media.pocket.compositor.VideoLayoutLayer
import forge.media.pocket.config.NodeConfigStore
import forge.media.pocket.discovery.AutopilotTagSubscription
import forge.media.pocket.discovery.ContentSortOrder
import forge.media.pocket.discovery.HuntPlatform
import forge.media.pocket.discovery.SearchTimePeriod
import forge.media.pocket.discovery.TopicHunterEngine
import forge.media.pocket.service.PipelineForegroundService
import forge.media.pocket.subtitles.SubtitleAssTransformer
import forge.media.pocket.subtitles.SubtitleDensityMode
import forge.media.pocket.subtitles.SubtitlePlacementZone
import forge.media.pocket.subtitles.TimedWord
import forge.media.pocket.vision.FaceCenteringHelper
import forge.media.pocket.vision.UpperBoxFramingPlan
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

data class InteractiveJobSession(
    val jobId: String,
    val chatId: Long,
    val sourceUrl: String,
    val originalTitle: String,
    val originalAuthor: String,
    val sourceTags: List<String>,
    val primaryVideoFile: File,
    val clutterReelFile: File,
    var framingPlan: UpperBoxFramingPlan,
    var cachedWords: List<TimedWord>,
    var latestAssFile: File?,
    var transcriptText: String,
    var config: ModularRenderConfig
)

data class PendingHuntFilterState(
    val queryTags: String,
    var platform: HuntPlatform = HuntPlatform.ALL,
    var sortOrder: ContentSortOrder = ContentSortOrder.POPULAR,
    var period: SearchTimePeriod = SearchTimePeriod.WEEK,
    var count: Int = 3
)

class TelegramBotWorker(
    private val context: Context,
    private val configStore: NodeConfigStore,
    private val clutterFilter: ClutterEntropyFilter,
    private val topicHunter: TopicHunterEngine,
    private val faceHelper: FaceCenteringHelper,
    private val subtitleTransformer: SubtitleAssTransformer,
    private val hardwareEngine: FFmpegHardwareEngine,
    private val geminiClient: GeminiMetadataClient,
    private val enqueueHardwareTask: (jobId: String, stage: String, block: suspend () -> Unit) -> Unit
) {

    companion object {
        private const val TAG = "TelegramBotWorker"
    }

    private val botToken: String
        get() = configStore.getTelegramBotToken()

    private val sessions = ConcurrentHashMap<String, InteractiveJobSession>()
    private val huntFilters = ConcurrentHashMap<String, PendingHuntFilterState>()

    private var pollingJob: Job? = null
    private var autopilotJob: Job? = null
    private val isAutopilotEnabled = AtomicBoolean(false)
    @Volatile
    private var autopilotChatId: Long = 0L
    @Volatile
    private var autopilotIntervalMinutes: Long = 60L

    private var lastUpdateId: Long = 0L

    private val jsonFormat = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val httpClient = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(jsonFormat)
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 120_000L
            connectTimeoutMillis = 20_000L
            socketTimeoutMillis = 120_000L
        }
    }

    @Serializable
    private data class TgUpdatesResponse(
        val ok: Boolean = false,
        val result: List<TgUpdate> = emptyList()
    )

    @Serializable
    private data class TgUpdate(
        @SerialName("update_id") val updateId: Long,
        val message: TgMessage? = null,
        @SerialName("channel_post") val channelPost: TgMessage? = null,
        @SerialName("callback_query") val callbackQuery: TgCallbackQuery? = null
    )

    @Serializable
    private data class TgMessage(
        @SerialName("message_id") val messageId: Long,
        val chat: TgChat,
        val text: String? = null
    )

    @Serializable
    private data class TgChat(
        val id: Long
    )

    @Serializable
    private data class TgCallbackQuery(
        val id: String,
        val message: TgMessage? = null,
        val data: String? = null
    )

    @Serializable
    private data class InlineKeyboardMarkup(
        @SerialName("inline_keyboard") val inlineKeyboard: List<List<InlineKeyboardButton>>
    )

    @Serializable
    private data class InlineKeyboardButton(
        val text: String,
        @SerialName("callback_data") val callbackData: String
    )

    fun startPolling(scope: CoroutineScope) {
        if (pollingJob?.isActive == true) return
        pollingJob = scope.launch(Dispatchers.IO) {
            Log.i(TAG, "Started Telegram Long-Polling loop")
            while (isActive) {
                val activeToken = botToken
                if (activeToken.isBlank()) {
                    delay(5000L)
                    continue
                }
                try {
                    val response: TgUpdatesResponse = httpClient.get(
                        "https://api.telegram.org/bot\$activeToken/getUpdates"
                    ) {
                        parameter("offset", lastUpdateId + 1)
                        parameter("timeout", 25)
                    }.body()

                    if (response.ok) {
                        for (update in response.result) {
                            lastUpdateId = maxOf(lastUpdateId, update.updateId)
                            handleUpdate(scope, update)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Telegram long-poll cycle warning: \${e.message}")
                    delay(3000L)
                }
            }
        }
    }

    fun stopPolling() {
        pollingJob?.cancel()
        autopilotJob?.cancel()
        pollingJob = null
        autopilotJob = null
    }

    private fun isChatAuthorized(chatId: Long): Boolean {
        val allowedRaw = configStore.getAllowedChatId().trim()
        if (allowedRaw.isEmpty()) return true
        val allowedLong = allowedRaw.toLongOrNull() ?: return true
        return chatId == allowedLong
    }

    private suspend fun handleUpdate(scope: CoroutineScope, update: TgUpdate) {
        val incomingMsg = update.message ?: update.channelPost
        incomingMsg?.let { msg ->
            val chatId = msg.chat.id
            if (!isChatAuthorized(chatId)) {
                Log.w(TAG, "Ignored message from unauthorized chatId=\$chatId")
                return
            }

            val text = msg.text?.trim() ?: return
            if (autopilotChatId == 0L) autopilotChatId = chatId

            when {
                text.startsWith("/status") -> {
                    val poolSize = clutterFilter.listVerifiedPoolClips().size
                    val subs = topicHunter.listAutopilotSubscriptions()
                    val svcState = PipelineForegroundService.statusFlow.value
                    sendMessage(
                        chatId = chatId,
                        text = "⚡ Pocket Media Forge (Snapdragon ARM64)\n" +
                            "• Ваш Chat ID: \$chatId\n" +
                            "• Статус: \$svcState\n" +
                            "• Проверенных фонов в пуле (3.3A): \$poolSize шт.\n" +
                            "• Активных сессий: \${sessions.size}\n" +
                            "• Фоновый Автопилот по тегам: \${if (isAutopilotEnabled.get()) "ВКЛ (каждые \$autopilotIntervalMinutes мин)" else "ПАУЗА"} (подписок: \${subs.size})"
                    )
                }

                text.startsWith("/hunt") -> {
                    handleHuntCommand(chatId, text)
                }

                text.startsWith("/autotags") -> {
                    handleAutotagsCommand(scope, chatId, text)
                }

                text.startsWith("/clip") || text.startsWith("/process") || text.startsWith("http") -> {
                    handleIncomingJobCommand(chatId, text)
                }

                else -> {
                    sendMessage(
                        chatId = chatId,
                        text = "🛠 Команды Pocket Media Forge:\n\n" +
                            "1️⃣ Поиск видео по твоим тегам и тематикам:\n" +
                            "• /hunt <теги/тема> — откроет пульт выбора сортировки (Популярное / Новизна / Взлетающее) и периода (24ч / Неделя / Месяц / Всё время)\n\n" +
                            "2️⃣ Фоновый Автопилот по тегам:\n" +
                            "• /autotags add <теги> [sort=top|new|hot] [period=day|week|month]\n" +
                            "• /autotags list — список подписок и пульт Автопилота\n" +
                            "• /autotags del <номер>\n\n" +
                            "3️⃣ Сборка по прямой ссылке:\n" +
                            "• /process <url> [preset=podcast|edit|brainrot|original]\n" +
                            "• /status"
                    )
                }
            }
        }

        update.callbackQuery?.let { cb ->
            answerCallbackQuery(cb.id)
            val chatId = cb.message?.chat?.id ?: return
            if (!isChatAuthorized(chatId)) return
            val data = cb.data ?: return
            handleInlineButtonCallback(scope, chatId, data)
        }
    }

    private suspend fun handleHuntCommand(chatId: Long, rawText: String) {
        val queryRaw = rawText.removePrefix("/hunt").trim()
        if (queryRaw.isBlank()) {
            sendMessage(
                chatId,
                "🔎 Укажи теги или тематику после /hunt.\nПримеры:\n" +
                    "• /hunt аниме эдит магическая битва\n" +
                    "• /hunt стендап отношения\n" +
                    "• /hunt пикабу физика залипашка"
            )
            return
        }

        val huntId = UUID.randomUUID().toString().take(6)
        val state = parseHuntArguments(queryRaw)
        huntFilters[huntId] = state

        sendHuntConfiguratorMessage(chatId, huntId, state)
    }

    private fun parseHuntArguments(rawQuery: String): PendingHuntFilterState {
        val tokens = rawQuery.split(Regex("""\\s+"""))
        var sort = ContentSortOrder.POPULAR
        var period = SearchTimePeriod.WEEK
        var platform = HuntPlatform.ALL
        var count = 3
        val tagWords = mutableListOf<String>()

        for (t in tokens) {
            val low = t.lowercase()
            when {
                low == "sort=new" || low == "new" -> sort = ContentSortOrder.NEWEST
                low == "sort=top" || low == "top" -> sort = ContentSortOrder.POPULAR
                low == "sort=hot" || low == "hot" -> sort = ContentSortOrder.VIRAL_VELOCITY
                low == "period=day" || low == "day" -> period = SearchTimePeriod.DAY
                low == "period=week" || low == "week" -> period = SearchTimePeriod.WEEK
                low == "period=month" || low == "month" -> period = SearchTimePeriod.MONTH
                low == "period=year" || low == "year" -> period = SearchTimePeriod.YEAR
                low == "period=all" || low == "all" -> period = SearchTimePeriod.ALL_TIME
                low == "yt" || low == "youtube" -> platform = HuntPlatform.YOUTUBE
                low == "reddit" -> platform = HuntPlatform.REDDIT
                low == "pikabu" || low == "пикабу" -> platform = HuntPlatform.PIKABU
                low.startsWith("count=") -> count = low.removePrefix("count=").toIntOrNull()?.coerceIn(1, 8) ?: 3
                else -> tagWords.add(t)
            }
        }

        val cleanQuery = tagWords.joinToString(" ").ifBlank { rawQuery }
        return PendingHuntFilterState(
            queryTags = cleanQuery,
            platform = platform,
            sortOrder = sort,
            period = period,
            count = count
        )
    }

    private suspend fun sendHuntConfiguratorMessage(
        chatId: Long,
        huntId: String,
        state: PendingHuntFilterState
    ) {
        val text = buildString {
            append("🎯 Поиск контента по тегам: «").append(state.queryTags).append("»\n\n")
            append("• Площадка: ").append(state.platform.label).append("\n")
            append("• Сортировка: ").append(state.sortOrder.label).append("\n")
            append("• Период: ").append(state.period.label).append("\n")
            append("• Количество роликов: ").append(state.count).append(" шт.\n\n")
            append("Настрой фильтры кнопками ниже или сразу жми «🚀 Найти и собрать превью»:")
        }

        val keyboard = InlineKeyboardMarkup(
            inlineKeyboard = listOf(
                listOf(
                    InlineKeyboardButton("🚀 Найти и собрать превью (\${state.count} шт.)", "hunt_go:\$huntId"),
                    InlineKeyboardButton("🤖 В Автопилот", "hunt_save_auto:\$huntId")
                ),
                listOf(
                    InlineKeyboardButton("Сорт: \${state.sortOrder.label.take(18)}", "hunt_sort:\$huntId"),
                    InlineKeyboardButton("Период: \${state.period.label}", "hunt_period:\$huntId")
                ),
                listOf(
                    InlineKeyboardButton("Источник: \${state.platform.label}", "hunt_plat:\$huntId"),
                    InlineKeyboardButton("Кол-во: \${state.count} шт.", "hunt_cnt:\$huntId")
                )
            )
        )

        sendMessageWithKeyboard(chatId, text, keyboard)
    }

    private suspend fun executeTagHuntBatch(chatId: Long, state: PendingHuntFilterState) {
        sendMessage(
            chatId,
            "🔎 Ищу \${state.count} небаянистых видео по теме «\${state.queryTags}» (\${state.sortOrder.label}, \${state.period.label})..."
        )

        enqueueHardwareTask("hunt", "Hunting videos for '\${state.queryTags}'") {
            val candidates = topicHunter.huntByTags(
                queryTags = state.queryTags,
                platform = state.platform,
                sortOrder = state.sortOrder,
                period = state.period,
                limitCount = state.count
            )

            if (candidates.isEmpty()) {
                sendMessage(
                    chatId,
                    "⚠️ По запросу «\${state.queryTags}» за период «\${state.period.label}» новых видео (10–75 сек) не найдено. Попробуй расширить период (За месяц / За всё время)."
                )
                return@enqueueHardwareTask
            }

            sendMessage(
                chatId,
                "✅ Найдено \${candidates.size} роликов! Начинаю конвейерную сборку 5-сек превью для каждого..."
            )

            for (cand in candidates) {
                try {
                    ingestAndCreateInteractiveSession(
                        chatId = chatId,
                        url = cand.url,
                        initialConfig = buildDefaultConfigWithSavedAdAssets(),
                        overrideTitle = cand.title,
                        overrideAuthor = cand.author
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed candidate \${cand.url}", e)
                }
            }
        }
    }

    private suspend fun handleAutotagsCommand(scope: CoroutineScope, chatId: Long, rawText: String) {
        val arg = rawText.removePrefix("/autotags").trim()
        when {
            arg.startsWith("add ") -> {
                val queryPart = arg.removePrefix("add ").trim()
                val parsed = parseHuntArguments(queryPart)
                val updated = topicHunter.addAutopilotSubscription(
                    AutopilotTagSubscription(
                        queryTags = parsed.queryTags,
                        platform = parsed.platform,
                        sortOrder = parsed.sortOrder,
                        period = parsed.period
                    )
                )
                sendAutopilotDashboard(chatId, updated)
            }

            arg.startsWith("del ") -> {
                val target = arg.removePrefix("del ").trim()
                val updated = topicHunter.removeAutopilotSubscription(target)
                sendAutopilotDashboard(chatId, updated)
            }

            arg == "on" -> {
                startAutopilotLoopIfNeeded(scope, chatId)
                sendAutopilotDashboard(chatId, topicHunter.listAutopilotSubscriptions())
            }

            arg == "off" -> {
                isAutopilotEnabled.set(false)
                autopilotJob?.cancel()
                sendAutopilotDashboard(chatId, topicHunter.listAutopilotSubscriptions())
            }

            else -> {
                sendAutopilotDashboard(chatId, topicHunter.listAutopilotSubscriptions())
            }
        }
    }

    private suspend fun sendAutopilotDashboard(
        chatId: Long,
        subs: List<AutopilotTagSubscription>
    ) {
        val statusStr = if (isAutopilotEnabled.get()) "🟢 АКТИВЕН (каждые \$autopilotIntervalMinutes мин)" else "⏸ НА ПАУЗЕ"
        val sb = StringBuilder("🤖 Фоновый Автопилот по тегам: \$statusStr\n\n")
        if (subs.isEmpty()) {
            sb.append("Список тегов пуст. Добавь через:\n/autotags add аниме эдит sort=top period=week")
        } else {
            subs.forEachIndexed { idx, s ->
                sb.append("\${idx + 1}. «\${s.queryTags}» [\${s.platform.name} | \${s.sortOrder.name} | \${s.period.label}]\n")
            }
        }

        val keyboard = InlineKeyboardMarkup(
            inlineKeyboard = listOf(
                listOf(
                    InlineKeyboardButton(
                        if (isAutopilotEnabled.get()) "⏸ Поставить Автопилот на паузу" else "▶️ Включить Автопилот",
                        "auto_toggle:0"
                    ),
                    InlineKeyboardButton("⚡ Проверить все теги сейчас", "auto_run_now:0")
                )
            )
        )
        sendMessageWithKeyboard(chatId, sb.toString(), keyboard)
    }

    private fun startAutopilotLoopIfNeeded(scope: CoroutineScope, chatId: Long) {
        autopilotChatId = chatId
        if (!isAutopilotEnabled.compareAndSet(false, true)) return

        autopilotJob = scope.launch(Dispatchers.IO) {
            Log.i(TAG, "Autopilot background tag hunter started")
            while (isActive && isAutopilotEnabled.get()) {
                runSingleAutopilotSweep(autopilotChatId)
                delay(autopilotIntervalMinutes * 60_000L)
            }
        }
    }

    private suspend fun runSingleAutopilotSweep(chatId: Long) {
        val subs = topicHunter.listAutopilotSubscriptions()
        if (subs.isEmpty()) return

        for (sub in subs) {
            enqueueHardwareTask("auto", "Autopilot hunting '\${sub.queryTags}'") {
                val found = topicHunter.huntByTags(
                    queryTags = sub.queryTags,
                    platform = sub.platform,
                    sortOrder = sub.sortOrder,
                    period = sub.period,
                    limitCount = 1
                )
                for (cand in found) {
                    ingestAndCreateInteractiveSession(
                        chatId = chatId,
                        url = cand.url,
                        initialConfig = buildDefaultConfigWithSavedAdAssets(),
                        overrideTitle = cand.title,
                        overrideAuthor = cand.author
                    )
                }
            }
        }
    }

    private suspend fun handleIncomingJobCommand(chatId: Long, rawCommand: String) {
        val tokens = rawCommand.split(Regex("""\\s+"""))
        val url = tokens.firstOrNull { it.startsWith("http://") || it.startsWith("https://") }
        if (url == null) {
            sendMessage(chatId, "❌ Не найдена ссылка. Используй /process <url> или /hunt <теги>.")
            return
        }

        val initialConfig = parseCommandFlagsToConfig(tokens)
        val jobId = UUID.randomUUID().toString().take(6)

        sendMessage(
            chatId = chatId,
            text = "⏳ Задача #\$jobId в очереди Snapdragon MediaCodec.\n" +
                "Качаю исходник, проверяю фон (3.3A), центрирую лицо (ML Kit) и собираю 5-сек превью..."
        )

        enqueueHardwareTask(jobId, "Ingesting & building 5s preview") {
            ingestAndCreateInteractiveSession(
                chatId = chatId,
                url = url,
                initialConfig = initialConfig,
                fixedJobId = jobId
            )
        }
    }

    private suspend fun ingestAndCreateInteractiveSession(
        chatId: Long,
        url: String,
        initialConfig: ModularRenderConfig,
        fixedJobId: String? = null,
        overrideTitle: String? = null,
        overrideAuthor: String? = null
    ) {
        val jobId = fixedJobId ?: UUID.randomUUID().toString().take(6)
        val bundle = clutterFilter.downloadMediaWithMetadata(url, maxDurationSec = 75)
        val durationSec = hardwareEngine.probeDurationSeconds(bundle.videoFile)
        val clutterFile = clutterFilter.buildShuffledClutterReel(durationSec)

        val framingPlan = faceHelper.computeFramingPlan(
            videoFile = bundle.videoFile,
            enableSmartFaceCrop = initialConfig.enableSmartFaceCrop
        )

        val (subResult, words) = subtitleTransformer.prepareAssSubtitles(
            sourceUrl = url,
            localVideoFile = bundle.videoFile,
            config = initialConfig.subtitleLayer,
            framingPlan = framingPlan
        )

        val session = InteractiveJobSession(
            jobId = jobId,
            chatId = chatId,
            sourceUrl = url,
            originalTitle = overrideTitle?.ifBlank { bundle.originalTitle } ?: bundle.originalTitle,
            originalAuthor = overrideAuthor?.ifBlank { bundle.uploaderAuthor } ?: bundle.uploaderAuthor,
            sourceTags = bundle.sourceTags,
            primaryVideoFile = bundle.videoFile,
            clutterReelFile = clutterFile,
            framingPlan = framingPlan,
            cachedWords = words,
            latestAssFile = subResult.assFile,
            transcriptText = subResult.plainTranscriptText,
            config = initialConfig
        )
        sessions[jobId] = session

        renderAndSendPreviewWithControls(session)
    }

    private suspend fun renderAndSendPreviewWithControls(session: InteractiveJobSession) {
        val previewFile = hardwareEngine.renderPipeline(
            primaryVideoFile = session.primaryVideoFile,
            clutterReelFile = session.clutterReelFile,
            framingPlan = session.framingPlan,
            assSubtitleFile = session.latestAssFile,
            config = session.config,
            isPreviewPass = true
        )

        val (_, resolvedSubY) = session.config.subtitleLayer.resolveCoordinates(session.framingPlan)
        val caption = buildString {
            append("🎬 Превью #").append(session.jobId).append(" (5 сек)\n")
            if (session.originalTitle.isNotBlank()) {
                append("📌 Исходник: ").append(session.originalTitle.take(60))
                if (session.originalAuthor.isNotBlank()) {
                    append(" (").append(session.originalAuthor).append(")")
                }
                append("\n")
            }
            append("• Режим: ").append(session.config.layoutLayer.label).append("\n")
            append("• Кроп лица: ").append(session.framingPlan.summary).append("\n")
            append("• Субтитры: ").append(
                if (session.config.subtitleLayer.enabled) {
                    "ВКЛ (\${session.config.subtitleLayer.placementZone.label}, Y=\$resolvedSubY)"
                } else {
                    "ВЫКЛ"
                }
            ).append("\n")
            append("• Обход Content ID (Зум+Питч+Паузы): ").append(if (session.config.dspLayer.enabled) "ВКЛ" else "ВЫКЛ").append("\n")
            append("• Реклама: ").append(session.config.adLayer.mode).append("\n")
            append("• AI-Описание (3 названия + тайтл): ").append(if (session.config.enableAiMetadataGeneration) "ВКЛ" else "ВЫКЛ")
        }

        val keyboard = buildInteractiveLayerKeyboard(session)
        sendVideoWithKeyboard(
            chatId = session.chatId,
            videoFile = previewFile,
            caption = caption,
            keyboard = keyboard
        )
        previewFile.delete()
    }

    private fun buildInteractiveLayerKeyboard(session: InteractiveJobSession): InlineKeyboardMarkup {
        val id = session.jobId
        val cfg = session.config
        val (_, subY) = cfg.subtitleLayer.resolveCoordinates(session.framingPlan)

        val subToggleLabel = if (cfg.subtitleLayer.enabled) "💬 Сабы: ВКЛ (Y=\$subY)" else "💬 Сабы: ВЫКЛ"
        val subStyleLabel = if (cfg.subtitleLayer.densityMode == SubtitleDensityMode.PUNCH_3_TO_4_WORDS) {
            "🔤 Стиль: Панч 3-4 сл."
        } else {
            "🔤 Стиль: Строка 6-8 сл."
        }
        val cropLabel = if (cfg.enableSmartFaceCrop) "👤 Кроп: Авто ML" else "👤 Кроп: ВЫКЛ (16:9)"
        val layoutLabel = "📐 Режим: \${cfg.layoutLayer.name.take(12)}"
        val dspLabel = if (cfg.dspLayer.enabled) "🛡 Анти-ID: ВКЛ" else "🛡 Анти-ID: ВЫКЛ"
        val adLabel = "📢 Реклама: \${cfg.adLayer.mode.name.take(10)}"
        val aiMetaLabel = if (cfg.enableAiMetadataGeneration) "📝 AI (3 назв.): ВКЛ" else "📝 AI: ВЫКЛ"

        return InlineKeyboardMarkup(
            inlineKeyboard = listOf(
                listOf(
                    InlineKeyboardButton("✅ Одобрить (MP4 без сжатия)", "approve:\$id"),
                    InlineKeyboardButton("📦 Скинуть все исходники", "rawpack:\$id")
                ),
                listOf(
                    InlineKeyboardButton(subToggleLabel, "toggle_sub:\$id"),
                    InlineKeyboardButton(subStyleLabel, "cycle_sub_style:\$id")
                ),
                listOf(
                    InlineKeyboardButton("⬆️ Сабы выше (-120px)", "sub_y_up:\$id"),
                    InlineKeyboardButton("⬇️ Сабы ниже (+120px)", "sub_y_down:\$id"),
                    InlineKeyboardButton("📍 Зона", "cycle_sub_zone:\$id")
                ),
                listOf(
                    InlineKeyboardButton(cropLabel, "toggle_crop:\$id"),
                    InlineKeyboardButton(layoutLabel, "cycle_layout:\$id")
                ),
                listOf(
                    InlineKeyboardButton(dspLabel, "toggle_dsp:\$id"),
                    InlineKeyboardButton(adLabel, "cycle_ad:\$id"),
                    InlineKeyboardButton(aiMetaLabel, "toggle_ai:\$id")
                ),
                listOf(
                    InlineKeyboardButton("🔄 Обновить 5с превью", "repreview:\$id"),
                    InlineKeyboardButton("🗑 В мусор", "reject:\$id")
                )
            )
        )
    }

    private suspend fun handleInlineButtonCallback(
        scope: CoroutineScope,
        chatId: Long,
        data: String
    ) {
        val parts = data.split(":")
        if (parts.size != 2) return
        val action = parts[0]
        val targetId = parts[1]

        if (action.startsWith("hunt_")) {
            val state = huntFilters[targetId] ?: return
            when (action) {
                "hunt_sort" -> {
                    val orders = ContentSortOrder.entries
                    state.sortOrder = orders[(state.sortOrder.ordinal + 1) % orders.size]
                    sendHuntConfiguratorMessage(chatId, targetId, state)
                }
                "hunt_period" -> {
                    val periods = SearchTimePeriod.entries
                    state.period = periods[(state.period.ordinal + 1) % periods.size]
                    sendHuntConfiguratorMessage(chatId, targetId, state)
                }
                "hunt_plat" -> {
                    val plats = HuntPlatform.entries
                    state.platform = plats[(state.platform.ordinal + 1) % plats.size]
                    sendHuntConfiguratorMessage(chatId, targetId, state)
                }
                "hunt_cnt" -> {
                    state.count = if (state.count >= 5) 1 else state.count + 2
                    sendHuntConfiguratorMessage(chatId, targetId, state)
                }
                "hunt_save_auto" -> {
                    val subs = topicHunter.addAutopilotSubscription(
                        AutopilotTagSubscription(
                            queryTags = state.queryTags,
                            platform = state.platform,
                            sortOrder = state.sortOrder,
                            period = state.period
                        )
                    )
                    sendAutopilotDashboard(chatId, subs)
                }
                "hunt_go" -> {
                    huntFilters.remove(targetId)
                    executeTagHuntBatch(chatId, state)
                }
            }
            return
        }

        if (action == "auto_toggle") {
            if (isAutopilotEnabled.get()) {
                isAutopilotEnabled.set(false)
                autopilotJob?.cancel()
            } else {
                startAutopilotLoopIfNeeded(scope, chatId)
            }
            sendAutopilotDashboard(chatId, topicHunter.listAutopilotSubscriptions())
            return
        }
        if (action == "auto_run_now") {
            sendMessage(chatId, "⚡ Запускаю внеочередной обход всех подписок Автопилота...")
            runSingleAutopilotSweep(chatId)
            return
        }

        val session = sessions[targetId]
        if (session == null) {
            sendMessage(chatId, "⚠️ Сессия #\$targetId уже закрыта или очищена.")
            return
        }

        when (action) {
            "toggle_sub" -> {
                val cur = session.config.subtitleLayer
                session.config = session.config.copy(
                    subtitleLayer = cur.copy(enabled = !cur.enabled)
                )
                regenerateAssForSession(session)
                sendMessage(chatId, "💬 Субтитры: \${if (session.config.subtitleLayer.enabled) "ВКЛ" else "ВЫКЛ"}")
            }

            "cycle_sub_style" -> {
                val cur = session.config.subtitleLayer
                val nextMode = if (cur.densityMode == SubtitleDensityMode.PUNCH_3_TO_4_WORDS) {
                    SubtitleDensityMode.CLASSIC_6_TO_8_WORDS
                } else {
                    SubtitleDensityMode.PUNCH_3_TO_4_WORDS
                }
                session.config = session.config.copy(
                    subtitleLayer = cur.copy(densityMode = nextMode)
                )
                regenerateAssForSession(session)
                sendMessage(chatId, "🔤 Стиль субтитров: \$nextMode")
            }

            "sub_y_up" -> {
                val cur = session.config.subtitleLayer
                val (_, currentY) = cur.resolveCoordinates(session.framingPlan)
                val newY = (currentY - 120).coerceIn(140, 1780)
                session.config = session.config.copy(
                    subtitleLayer = cur.copy(
                        placementZone = SubtitlePlacementZone.CUSTOM_XY,
                        customY = newY
                    )
                )
                regenerateAssForSession(session)
                sendMessage(chatId, "⬆️ Блок субтитров сдвинут вверх: Y=\$newY px")
            }

            "sub_y_down" -> {
                val cur = session.config.subtitleLayer
                val (_, currentY) = cur.resolveCoordinates(session.framingPlan)
                val newY = (currentY + 120).coerceIn(140, 1780)
                session.config = session.config.copy(
                    subtitleLayer = cur.copy(
                        placementZone = SubtitlePlacementZone.CUSTOM_XY,
                        customY = newY
                    )
                )
                regenerateAssForSession(session)
                sendMessage(chatId, "⬇️ Блок субтитров сдвинут вниз: Y=\$newY px")
            }

            "cycle_sub_zone" -> {
                val zones = SubtitlePlacementZone.entries
                val curZone = session.config.subtitleLayer.placementZone
                val nextZone = zones[(curZone.ordinal + 1) % zones.size]
                session.config = session.config.copy(
                    subtitleLayer = session.config.subtitleLayer.copy(placementZone = nextZone)
                )
                regenerateAssForSession(session)
                sendMessage(chatId, "📍 Зона субтитров: \${nextZone.label}")
            }

            "toggle_crop" -> {
                val nextCrop = !session.config.enableSmartFaceCrop
                session.config = session.config.copy(enableSmartFaceCrop = nextCrop)
                enqueueHardwareTask(targetId, "Recomputing face crop") {
                    session.framingPlan = faceHelper.computeFramingPlan(
                        videoFile = session.primaryVideoFile,
                        enableSmartFaceCrop = nextCrop
                    )
                    regenerateAssForSession(session)
                    sendMessage(chatId, "👤 Кроп лица: \${session.framingPlan.summary}")
                }
            }

            "cycle_layout" -> {
                val layouts = VideoLayoutLayer.entries
                val nextLayout = layouts[(session.config.layoutLayer.ordinal + 1) % layouts.size]
                session.config = session.config.copy(layoutLayer = nextLayout)
                sendMessage(chatId, "📐 Режим сборки: \${nextLayout.label}")
            }

            "toggle_dsp" -> {
                val nextDsp = !session.config.dspLayer.enabled
                session.config = session.config.copy(
                    dspLayer = session.config.dspLayer.copy(enabled = nextDsp)
                )
                sendMessage(chatId, "🛡 Обход Content ID (Зум+Питч+Паузы): \${if (nextDsp) "ВКЛ" else "ВЫКЛ"}")
            }

            "cycle_ad" -> {
                val modes = AdInsertionMode.entries
                val nextAd = modes[(session.config.adLayer.mode.ordinal + 1) % modes.size]
                session.config = session.config.copy(
                    adLayer = session.config.adLayer.copy(mode = nextAd)
                )
                sendMessage(chatId, "📢 Режим рекламы: \$nextAd")
            }

            "toggle_ai" -> {
                val nextAi = !session.config.enableAiMetadataGeneration
                session.config = session.config.copy(enableAiMetadataGeneration = nextAi)
                sendMessage(chatId, "📝 Генерация AI-описания (3 названия + тайтл): \${if (nextAi) "ВКЛ" else "ВЫКЛ"}")
            }

            "repreview" -> {
                sendMessage(chatId, "🔄 Пересобираю 5-сек превью для #\$targetId...")
                enqueueHardwareTask(targetId, "Re-rendering 5s preview") {
                    renderAndSendPreviewWithControls(session)
                }
            }

            "approve" -> {
                sendMessage(chatId, "🚀 Запущен полный рендер #\$targetId на Snapdragon (h264_mediacodec)...")
                enqueueHardwareTask(targetId, "Full hardware render") {
                    executeFullRenderAndDeliver(session)
                }
            }

            "rawpack" -> {
                sendMessage(
                    chatId,
                    "📦 Выгружаю все раздельные исходники по задаче #\$targetId (в память телефона Downloads и сюда в чат)..."
                )
                enqueueHardwareTask(targetId, "Exporting raw source pack") {
                    exportAndDeliverRawSourcePack(session)
                }
            }

            "reject" -> {
                cleanupSession(targetId)
                sendMessage(chatId, "🗑 Задача #\$targetId отправлена в мусор, кэш очищен.")
            }
        }
    }

    private suspend fun executeFullRenderAndDeliver(session: InteractiveJobSession) {
        val finalMp4 = hardwareEngine.renderPipeline(
            primaryVideoFile = session.primaryVideoFile,
            clutterReelFile = session.clutterReelFile,
            framingPlan = session.framingPlan,
            assSubtitleFile = session.latestAssFile,
            config = session.config,
            isPreviewPass = false
        )

        val phoneExportDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "PocketMediaForge/Job_\${session.jobId}"
        ).apply { mkdirs() }
        val localSavedCopy = File(phoneExportDir, "final_\${session.jobId}.mp4")
        finalMp4.copyTo(localSavedCopy, overwrite = true)

        val metadata = geminiClient.generateMetadataIfEnabled(
            enabled = session.config.enableAiMetadataGeneration,
            sourceUrl = session.sourceUrl,
            originalTitle = session.originalTitle,
            originalAuthor = session.originalAuthor,
            sourceTags = session.sourceTags,
            transcriptText = session.transcriptText,
            adConfig = session.config.adLayer
        )

        val caption = buildString {
            append("✅ Готовый рендер #").append(session.jobId).append(" (Без сжатия)\n")
            append("📁 На телефоне: Downloads/PocketMediaForge/Job_").append(session.jobId).append("\n\n")
            if (metadata != null) {
                append("🎯 3 варианта названия:\n")
                metadata.titles.forEachIndexed { idx, t ->
                    append("  ").append(idx + 1).append(") ").append(t).append("\n")
                }
                append("\n🎬 Источник / Тайтл: ").append(metadata.sourceAttribution).append("\n")
                append("📝 Описание: ").append(metadata.description).append("\n")
                append("🏷 Хэштеги: ").append(metadata.hashtags.joinToString(" ")).append("\n\n")
                append("📌 Закреплённый комментарий:\n").append(metadata.pinnedComment)
            }
        }

        sendUncompressedDocument(
            chatId = session.chatId,
            file = finalMp4,
            caption = caption
        )

        finalMp4.delete()
        cleanupSession(session.jobId)
    }

    private suspend fun exportAndDeliverRawSourcePack(session: InteractiveJobSession) {
        val rawExportDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "PocketMediaForge/Job_\${session.jobId}_RAW"
        ).apply { mkdirs() }

        val rawPrimary = File(rawExportDir, "1_primary_source_\${session.jobId}.mp4")
        session.primaryVideoFile.copyTo(rawPrimary, overwrite = true)

        val rawClutter = File(rawExportDir, "2_clutter_1080x960_\${session.jobId}.mp4")
        session.clutterReelFile.copyTo(rawClutter, overwrite = true)

        val rawAudio = File(rawExportDir, "4_audio_48k_\${session.jobId}.m4a")
        hardwareEngine.extractCleanAudioTrack(session.primaryVideoFile, rawAudio)

        var rawAss: File? = null
        session.latestAssFile?.let { ass ->
            if (ass.exists()) {
                rawAss = File(rawExportDir, "3_subtitles_\${session.jobId}.ass")
                ass.copyTo(rawAss!!, overwrite = true)
            }
        }

        sendMessage(
            chatId = session.chatId,
            text = "📂 Все исходники для ручной сборки сохранены на телефоне в:\n" +
                "Downloads/PocketMediaForge/Job_\${session.jobId}_RAW/\n" +
                "Выгружаю файлы без сжатия..."
        )

        sendUncompressedDocument(session.chatId, rawPrimary, "1️⃣ Исходник речи (Primary Video)")
        sendUncompressedDocument(session.chatId, rawClutter, "2️⃣ Проверенный монотонный фон (1080x960 @ 30fps)")
        rawAss?.let { sendUncompressedDocument(session.chatId, it, "3️⃣ Субтитры (.ASS Karaoke)") }
        if (rawAudio.exists()) {
            sendUncompressedDocument(session.chatId, rawAudio, "4️⃣ Чистая аудиодорожка (48kHz AAC)")
        }
    }

    private suspend fun regenerateAssForSession(session: InteractiveJobSession) {
        session.latestAssFile?.delete()
        val (subRes, _) = subtitleTransformer.prepareAssSubtitles(
            sourceUrl = session.sourceUrl,
            localVideoFile = session.primaryVideoFile,
            config = session.config.subtitleLayer,
            framingPlan = session.framingPlan,
            cachedWords = session.cachedWords
        )
        session.latestAssFile = subRes.assFile
    }

    private fun buildDefaultConfigWithSavedAdAssets(): ModularRenderConfig {
        val bannerText = configStore.getCustomBannerText()
        val promoUrl = configStore.getSponsorPromoUrl()
        val midrollFile = configStore.getMidrollVideoFile()
        return ModularRenderConfig(
            adLayer = AdLayerConfig(
                mode = AdInsertionMode.NONE,
                bannerText = bannerText,
                midrollClipFile = midrollFile,
                sponsorTrackingUrl = promoUrl
            )
        )
    }

    private fun parseCommandFlagsToConfig(tokens: List<String>): ModularRenderConfig {
        var config = buildDefaultConfigWithSavedAdAssets()
        for (token in tokens) {
            val lower = token.lowercase()
            when {
                lower == "preset=edit" || lower == "layout=full" ->
                    config = config.copy(layoutLayer = VideoLayoutLayer.FULLSCREEN_EDIT)
                lower == "preset=brainrot" ->
                    config = config.copy(layoutLayer = VideoLayoutLayer.BRAINROT_VOICEOVER)
                lower == "preset=original" ->
                    config = config.copy(layoutLayer = VideoLayoutLayer.DISTORTED_ORIGINAL)
                lower == "ad=banner" ->
                    config = config.copy(adLayer = config.adLayer.copy(mode = AdInsertionMode.BANNER_OVERLAY))
                lower == "ad=midroll" ->
                    config = config.copy(adLayer = config.adLayer.copy(mode = AdInsertionMode.MIDROLL_SPLICE))
                lower == "ad=comment" ->
                    config = config.copy(adLayer = config.adLayer.copy(mode = AdInsertionMode.PINNED_COMMENT_ONLY))
                lower == "sub=off" ->
                    config = config.copy(subtitleLayer = config.subtitleLayer.copy(enabled = false))
                lower == "crop=off" ->
                    config = config.copy(enableSmartFaceCrop = false)
                lower == "dsp=off" ->
                    config = config.copy(dspLayer = config.dspLayer.copy(enabled = false))
                lower == "ai=off" ->
                    config = config.copy(enableAiMetadataGeneration = false)
                lower.startsWith("sub_y=") -> {
                    val yVal = lower.removePrefix("sub_y=").toIntOrNull()
                    if (yVal != null) {
                        config = config.copy(
                            subtitleLayer = config.subtitleLayer.copy(
                                placementZone = SubtitlePlacementZone.CUSTOM_XY,
                                customY = yVal
                            )
                        )
                    }
                }
            }
        }
        return config
    }

    private fun cleanupSession(jobId: String) {
        val removed = sessions.remove(jobId) ?: return
        removed.primaryVideoFile.delete()
        removed.latestAssFile?.delete()
    }

    private suspend fun sendMessage(chatId: Long, text: String) = withContext(Dispatchers.IO) {
        val activeToken = botToken
        if (activeToken.isBlank()) return@withContext
        try {
            httpClient.post("https://api.telegram.org/bot\$activeToken/sendMessage") {
                parameter("chat_id", chatId)
                parameter("text", text)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send Telegram message", e)
        }
    }

    private suspend fun sendMessageWithKeyboard(
        chatId: Long,
        text: String,
        keyboard: InlineKeyboardMarkup
    ) = withContext(Dispatchers.IO) {
        val activeToken = botToken
        if (activeToken.isBlank()) return@withContext
        try {
            val markupJson = jsonFormat.encodeToString(keyboard)
            httpClient.post("https://api.telegram.org/bot\$activeToken/sendMessage") {
                parameter("chat_id", chatId)
                parameter("text", text)
                parameter("reply_markup", markupJson)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send message with keyboard", e)
        }
    }

    private suspend fun answerCallbackQuery(callbackQueryId: String) = withContext(Dispatchers.IO) {
        val activeToken = botToken
        if (activeToken.isBlank()) return@withContext
        try {
            httpClient.post("https://api.telegram.org/bot\$activeToken/answerCallbackQuery") {
                parameter("callback_query_id", callbackQueryId)
            }
        } catch (_: Exception) {
        }
    }

    private suspend fun sendVideoWithKeyboard(
        chatId: Long,
        videoFile: File,
        caption: String,
        keyboard: InlineKeyboardMarkup
    ) = withContext(Dispatchers.IO) {
        val activeToken = botToken
        if (activeToken.isBlank()) return@withContext
        try {
            val markupJson = jsonFormat.encodeToString(keyboard)
            httpClient.post("https://api.telegram.org/bot\$activeToken/sendVideo") {
                setBody(
                    MultiPartFormDataContent(
                        formData {
                            append("chat_id", chatId.toString())
                            append("caption", caption.take(1020))
                            append("reply_markup", markupJson)
                            append(
                                key = "video",
                                value = videoFile.readBytes(),
                                headers = Headers.build {
                                    append(HttpHeaders.ContentType, "video/mp4")
                                    append(
                                        HttpHeaders.ContentDisposition,
                                        "filename=\\"\${videoFile.name}\\""
                                    )
                                }
                            )
                        }
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to upload preview video with keyboard", e)
        }
    }

    private suspend fun sendUncompressedDocument(
        chatId: Long,
        file: File,
        caption: String
    ) = withContext(Dispatchers.IO) {
        val activeToken = botToken
        if (activeToken.isBlank()) return@withContext
        try {
            httpClient.post("https://api.telegram.org/bot\$activeToken/sendDocument") {
                setBody(
                    MultiPartFormDataContent(
                        formData {
                            append("chat_id", chatId.toString())
                            append("caption", caption.take(1020))
                            append(
                                key = "document",
                                value = file.readBytes(),
                                headers = Headers.build {
                                    append(HttpHeaders.ContentType, "application/octet-stream")
                                    append(
                                        HttpHeaders.ContentDisposition,
                                        "filename=\\"\${file.name}\\""
                                    )
                                }
                            )
                        }
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to upload uncompressed document \${file.name}", e)
        }
    }
}`,
  },

  // ================= STEP 6 FILES (HYBRID 6C ON-DEVICE UI + SECRETS STORE) =================
  {
    step: 6,
    path: 'app/src/main/java/forge/media/pocket/config/NodeConfigStore.kt',
    language: 'kotlin',
    description:
      'Option 6C Hybrid Credential & Ad Asset Store: reads initial defaults from GitHub Secrets (BuildConfig) and lets you edit/override Bot Token, Gemini Key, Allowed Chat ID, and Sponsor Hooks anytime on the phone screen without rebuilding the APK.',
    content: `package forge.media.pocket.config

import android.content.Context
import forge.media.pocket.BuildConfig
import java.io.File

/**
 * Option 6C Hybrid Configuration Store:
 * - Falls back to BuildConfig values injected by GitHub Secrets during CI build.
 * - Allows live on-device editing via MainActivity without recompiling the APK.
 */
class NodeConfigStore(private val context: Context) {

    companion object {
        private const val PREFS_NAME = "pocket_media_forge_node_prefs"
        private const val KEY_BOT_TOKEN = "telegram_bot_token"
        private const val KEY_GEMINI_API_KEY = "gemini_api_key"
        private const val KEY_ALLOWED_CHAT_ID = "allowed_chat_id"
        private const val KEY_BANNER_TEXT = "custom_banner_text"
        private const val KEY_PROMO_URL = "sponsor_promo_url"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getTelegramBotToken(): String {
        val local = prefs.getString(KEY_BOT_TOKEN, null)?.trim()
        return if (!local.isNullOrEmpty()) local else BuildConfig.DEFAULT_TELEGRAM_BOT_TOKEN.trim()
    }

    fun setTelegramBotToken(token: String) {
        prefs.edit().putString(KEY_BOT_TOKEN, token.trim()).apply()
    }

    fun getGeminiApiKey(): String {
        val local = prefs.getString(KEY_GEMINI_API_KEY, null)?.trim()
        return if (!local.isNullOrEmpty()) local else BuildConfig.DEFAULT_GEMINI_API_KEY.trim()
    }

    fun setGeminiApiKey(key: String) {
        prefs.edit().putString(KEY_GEMINI_API_KEY, key.trim()).apply()
    }

    fun getAllowedChatId(): String {
        val local = prefs.getString(KEY_ALLOWED_CHAT_ID, null)?.trim()
        return if (!local.isNullOrEmpty()) local else BuildConfig.DEFAULT_ALLOWED_CHAT_ID.trim()
    }

    fun setAllowedChatId(chatId: String) {
        prefs.edit().putString(KEY_ALLOWED_CHAT_ID, chatId.trim()).apply()
    }

    fun getCustomBannerText(): String {
        return prefs.getString(KEY_BANNER_TEXT, null)
            ?.takeIf { it.isNotBlank() }
            ?: "SPONSORED • ССЫЛКА В ЗАКРЕПЕ"
    }

    fun setCustomBannerText(text: String) {
        prefs.edit().putString(KEY_BANNER_TEXT, text.trim()).apply()
    }

    fun getSponsorPromoUrl(): String {
        return prefs.getString(KEY_PROMO_URL, "") ?: ""
    }

    fun setSponsorPromoUrl(url: String) {
        prefs.edit().putString(KEY_PROMO_URL, url.trim()).apply()
    }

    fun getMidrollVideoFile(): File? {
        val f = File(context.filesDir, "ad_assets/midroll.mp4")
        return if (f.exists() && f.length() > 0L) f else null
    }
}`,
  },
  {
    step: 6,
    path: 'app/src/main/java/forge/media/pocket/ui/MainActivity.kt',
    language: 'kotlin',
    description:
      'Dark Engineering Control Panel on the phone screen (Option 6C): edit Bot Token / Gemini Key / Allowed Chat ID, pick Midroll MP4 from phone storage, grant Android 14 Notification & OxygenOS Battery exemptions, and start/stop the hardware node.',
    content: `package forge.media.pocket.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import forge.media.pocket.config.NodeConfigStore
import forge.media.pocket.service.PipelineForegroundService
import forge.media.pocket.service.PowerOptimizationHelper
import forge.media.pocket.service.ServiceStatus
import kotlinx.coroutines.launch
import java.io.File

/**
 * Programmatic Zero-XML Dark Control Panel for OnePlus / Snapdragon Node (Step 6 - Option 6C).
 *
 * Why programmatic UI?
 * - Eliminates XML layout inflation issues and R-class resource mismatches in headless CI builds.
 * - Provides direct input fields for Telegram Bot Token, Gemini API Key, Allowed Chat ID,
 *   Ad Banner Text, and Midroll video picker, plus OxygenOS battery whitelist buttons.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var configStore: NodeConfigStore
    private lateinit var powerHelper: PowerOptimizationHelper

    private lateinit var statusBadgeView: TextView
    private lateinit var poolStatsView: TextView
    private lateinit var tokenInput: EditText
    private lateinit var geminiKeyInput: EditText
    private lateinit var chatIdInput: EditText
    private lateinit var bannerTextInput: EditText
    private lateinit var promoUrlInput: EditText
    private lateinit var midrollStatusView: TextView

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    private val pickMidrollVideoLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            copyMidrollToInternalStorage(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configStore = NodeConfigStore(applicationContext)
        powerHelper = PowerOptimizationHelper(applicationContext)

        requestNotificationPermissionIfNeeded()
        setContentView(buildEngineeringDashboardView())
        observeServiceStatus()
        refreshPoolAndAdStats()
    }

    private fun buildEngineeringDashboardView(): ScrollView {
        val scrollView = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#09090B"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(20)
            setPadding(pad, dp(28), pad, dp(40))
        }

        // Header Title
        root.addView(TextView(this).apply {
            text = "POCKET MEDIA FORGE"
            setTextColor(Color.parseColor("#F59E0B"))
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
        })

        root.addView(TextView(this).apply {
            text = "Android 14 (API 34) • Qualcomm h264_mediacodec • OxygenOS Node"
            setTextColor(Color.parseColor("#A1A1AA"))
            textSize = 12f
            setPadding(0, dp(4), 0, dp(16))
        })

        // Live Service Status Card
        statusBadgeView = TextView(this).apply {
            text = "STATUS: CHECKING..."
            setTextColor(Color.parseColor("#10B981"))
            setBackgroundColor(Color.parseColor("#18181B"))
            textSize = 13f
            typeface = Typeface.MONOSPACE
            val p = dp(14)
            setPadding(p, p, p, p)
        }
        root.addView(statusBadgeView)

        poolStatsView = TextView(this).apply {
            setTextColor(Color.parseColor("#D4D4D8"))
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(8), 0, dp(16))
        }
        root.addView(poolStatsView)

        // Start / Stop Service Row
        val serviceRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f
        }

        val startBtn = createStyledButton("▶️ ЗАПУСТИТЬ УЗЕЛ", "#10B981", "#052E16").apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(6)
            }
            setOnClickListener {
                saveInputsToStore()
                PipelineForegroundService.startService(this@MainActivity)
                Toast.makeText(this@MainActivity, "Foreground Service запущен", Toast.LENGTH_SHORT).show()
            }
        }

        val stopBtn = createStyledButton("⏹ ОСТАНОВИТЬ", "#EF4444", "#450A0A").apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(6)
            }
            setOnClickListener {
                PipelineForegroundService.stopService(this@MainActivity)
                Toast.makeText(this@MainActivity, "Узел остановлен", Toast.LENGTH_SHORT).show()
            }
        }

        serviceRow.addView(startBtn)
        serviceRow.addView(stopBtn)
        root.addView(serviceRow)

        // Section: Credentials (Hybrid 6C)
        root.addView(sectionHeader("1. КЛЮЧИ И ДОСТУП (СОХРАНЯЮТСЯ НА ТЕЛЕФОНЕ)"))

        tokenInput = createInputField(
            hint = "Telegram Bot Token (123456:ABC-DEF...)",
            initial = configStore.getTelegramBotToken(),
            isSecret = true
        )
        root.addView(fieldLabel("Telegram Bot Token:"))
        root.addView(tokenInput)

        geminiKeyInput = createInputField(
            hint = "Gemini API Key (AIzaSy...)",
            initial = configStore.getGeminiApiKey(),
            isSecret = true
        )
        root.addView(fieldLabel("Gemini 2.5 Flash API Key:"))
        root.addView(geminiKeyInput)

        chatIdInput = createInputField(
            hint = "Твой Telegram Chat ID или ID канала (пусто = доступен всем)",
            initial = configStore.getAllowedChatId(),
            isSecret = false
        )
        root.addView(fieldLabel("Разрешённый Chat ID (Белый список):"))
        root.addView(chatIdInput)

        // Section: Ad Engine Assets
        root.addView(sectionHeader("2. РЕКЛАМНЫЙ ДВИЖОК (БАННЕР / МИДРОЛЛ / ССЫЛКА)"))

        bannerTextInput = createInputField(
            hint = "Текст рекламной плашки",
            initial = configStore.getCustomBannerText(),
            isSecret = false
        )
        root.addView(fieldLabel("Текст для баннера (Strategy A):"))
        root.addView(bannerTextInput)

        promoUrlInput = createInputField(
            hint = "Промо-хук и ссылка для закрепа (Strategy C)",
            initial = configStore.getSponsorPromoUrl(),
            isSecret = false
        )
        root.addView(fieldLabel("Промо-текст и ссылка в закреп (Strategy C):"))
        root.addView(promoUrlInput)

        midrollStatusView = TextView(this).apply {
            setTextColor(Color.parseColor("#A1A1AA"))
            textSize = 12f
            setPadding(0, dp(8), 0, dp(6))
        }
        root.addView(midrollStatusView)

        root.addView(createStyledButton("📂 ВЫБРАТЬ 3-СЕК МИДРОЛЛ (.MP4) С ТЕЛЕФОНА", "#E4E4E7", "#27272A").apply {
            setOnClickListener {
                pickMidrollVideoLauncher.launch("video/mp4")
            }
        })

        root.addView(createStyledButton("💾 СОХРАНИТЬ КЛЮЧИ И ПРИМЕНИТЬ НА ЛЕТУ", "#F59E0B", "#451A03").apply {
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(14)
            }
            layoutParams = lp
            setOnClickListener {
                saveInputsToStore()
                PipelineForegroundService.reloadCredentials(this@MainActivity)
                Toast.makeText(this@MainActivity, "Ключи сохранены и применены!", Toast.LENGTH_SHORT).show()
            }
        })

        // Section: OxygenOS / ColorOS Power Exemptions
        root.addView(sectionHeader("3. ЗАЩИТА ОТ УБИЙСТВА ПРОЦЕССА (OXYGENOS / COLOROS)"))

        root.addView(createStyledButton("🔋 1. ОТКЛЮЧИТЬ КОНТРОЛЬ БАТАРЕИ (DOZE)", "#38BDF8", "#082F49").apply {
            setOnClickListener {
                startActivity(powerHelper.buildIgnoreBatteryOptimizationIntent())
            }
        })

        root.addView(createStyledButton("🛡 2. ОТКРЫТЬ АВТОЗАПУСК OXYGENOS / COLOROS", "#A78BFA", "#2E1065").apply {
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(8)
            }
            layoutParams = lp
            setOnClickListener {
                val intent = powerHelper.buildOxygenOsAutoLaunchIntent()
                if (intent != null) {
                    startActivity(intent)
                } else {
                    Toast.makeText(
                        this@MainActivity,
                        "Спец-меню ColorOS не найдено, достаточно стандартного отключения Doze",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        })

        root.addView(createStyledButton("🧹 ОЧИСТИТЬ ВРЕМЕННЫЙ КЭШ СБОРКИ", "#A1A1AA", "#18181B").apply {
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(16)
            }
            layoutParams = lp
            setOnClickListener {
                File(cacheDir, "work_staging").deleteRecursively()
                File(cacheDir, "work_staging").mkdirs()
                refreshPoolAndAdStats()
                Toast.makeText(this@MainActivity, "Временный кэш очищен", Toast.LENGTH_SHORT).show()
            }
        })

        scrollView.addView(root)
        return scrollView
    }

    private fun saveInputsToStore() {
        configStore.setTelegramBotToken(tokenInput.text.toString())
        configStore.setGeminiApiKey(geminiKeyInput.text.toString())
        configStore.setAllowedChatId(chatIdInput.text.toString())
        configStore.setCustomBannerText(bannerTextInput.text.toString())
        configStore.setSponsorPromoUrl(promoUrlInput.text.toString())
        refreshPoolAndAdStats()
    }

    private fun copyMidrollToInternalStorage(uri: Uri) {
        try {
            val adDir = File(filesDir, "ad_assets").apply { mkdirs() }
            val dest = File(adDir, "midroll.mp4")
            contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            refreshPoolAndAdStats()
            Toast.makeText(this, "Мидролл сохранён (\${dest.length() / 1024} КБ)", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка копирования мидролла: \${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun refreshPoolAndAdStats() {
        val poolCount = File(filesDir, "clutter_pool").listFiles { f -> f.extension == "mp4" }?.size ?: 0
        val batteryOk = powerHelper.isIgnoringBatteryOptimizations()
        poolStatsView.text = "Clutter Pool: \$poolCount clips | Doze Exemption: \${if (batteryOk) "GRANTED" else "NOT GRANTED"}"

        val midroll = configStore.getMidrollVideoFile()
        midrollStatusView.text = if (midroll != null) {
            "Мидролл (Strategy B): Загружен (\${midroll.length() / 1024} KB)"
        } else {
            "Мидролл (Strategy B): Не выбран"
        }
    }

    private fun observeServiceStatus() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                PipelineForegroundService.statusFlow.collect { status ->
                    val (text, colorHex) = when (status) {
                        is ServiceStatus.Stopped -> "⏹ УЗЕЛ ОСТАНОВЛЕН" to "#A1A1AA"
                        is ServiceStatus.Initializing -> "⏳ ИНИЦИАЛИЗАЦИЯ YT-DLP & ARIA2C..." to "#F59E0B"
                        is ServiceStatus.IdleListening -> "🟢 ОНЛАЙН • СЛУШАЮ КОМАНДЫ В TELEGRAM" to "#10B981"
                        is ServiceStatus.Processing -> "⚙️ РЕНДЕР #\${status.jobId}: \${status.stage}" to "#38BDF8"
                        is ServiceStatus.Error -> "⚠️ \${status.reason}" to "#EF4444"
                    }
                    statusBadgeView.text = text
                    statusBadgeView.setTextColor(Color.parseColor(colorHex))
                    refreshPoolAndAdStats()
                }
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun sectionHeader(title: String): TextView = TextView(this).apply {
        text = title
        setTextColor(Color.parseColor("#F59E0B"))
        textSize = 11f
        typeface = Typeface.MONOSPACE
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(22), 0, dp(8))
    }

    private fun fieldLabel(label: String): TextView = TextView(this).apply {
        text = label
        setTextColor(Color.parseColor("#A1A1AA"))
        textSize = 12f
        setPadding(0, dp(8), 0, dp(4))
    }

    private fun createInputField(hint: String, initial: String, isSecret: Boolean): EditText =
        EditText(this).apply {
            this.hint = hint
            setText(initial)
            setHintTextColor(Color.parseColor("#52525B"))
            setTextColor(Color.parseColor("#F4F4F5"))
            setBackgroundColor(Color.parseColor("#18181B"))
            textSize = 13f
            typeface = Typeface.MONOSPACE
            val p = dp(12)
            setPadding(p, p, p, p)
            inputType = if (isSecret) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            } else {
                InputType.TYPE_CLASS_TEXT
            }
        }

    private fun createStyledButton(text: String, textColorHex: String, bgHex: String): Button =
        Button(this).apply {
            this.text = text
            setTextColor(Color.parseColor(textColorHex))
            setBackgroundColor(Color.parseColor(bgHex))
            textSize = 12f
            isAllCaps = false
            gravity = Gravity.CENTER
            val p = dp(12)
            setPadding(p, p, p, p)
        }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}`,
  },
];

export default function App() {
  const [selectedStepFilter, setSelectedStepFilter] = useState<'all' | 1 | 2 | 3 | 4 | 5 | 6>(6);
  const [selectedFilePath, setSelectedFilePath] = useState<string>(
    'app/src/main/java/forge/media/pocket/ui/MainActivity.kt'
  );
  const [copiedPath, setCopiedPath] = useState<string | null>(null);

  const filteredFiles = PROJECT_FILES.filter((f) =>
    selectedStepFilter === 'all' ? true : f.step === selectedStepFilter
  );

  const currentFile =
    PROJECT_FILES.find((f) => f.path === selectedFilePath) || filteredFiles[0];

  const handleCopy = (content: string, path: string) => {
    navigator.clipboard.writeText(content);
    setCopiedPath(path);
    setTimeout(() => setCopiedPath(null), 2000);
  };

  const handleDownloadFile = (file: ProjectFile) => {
    const blob = new Blob([file.content], { type: 'text/plain;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = file.path.split('/').pop() || 'file.kt';
    a.click();
    URL.revokeObjectURL(url);
  };

  const handleDownloadSetupScript = () => {
    const lines: string[] = [
      '#!/usr/bin/env bash',
      'set -e',
      'echo "Generating Complete Pocket Media Forge Android 14 Project (18 files)..."',
    ];
    for (const file of PROJECT_FILES) {
      const dir = file.path.includes('/')
        ? file.path.substring(0, file.path.lastIndexOf('/'))
        : '';
      if (dir) {
        lines.push(`mkdir -p "${dir}"`);
      }
      lines.push(`cat << 'EOF_FORGE_FILE' > "${file.path}"`);
      lines.push(file.content);
      lines.push('EOF_FORGE_FILE');
    }
    lines.push('echo "All 18 files created! Push to GitHub to build your APK via GitHub Actions."');

    const blob = new Blob([lines.join('\n')], { type: 'text/x-shellscript;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = 'generate_pocket_media_forge.sh';
    a.click();
    URL.revokeObjectURL(url);
  };

  return (
    <div className="min-h-screen bg-zinc-950 text-zinc-100 font-sans selection:bg-amber-500/30">
      {/* Header */}
      <header className="border-b border-zinc-800 bg-zinc-900/70 backdrop-blur sticky top-0 z-20">
        <div className="max-w-7xl mx-auto px-4 sm:px-6 py-4 flex flex-wrap items-center justify-between gap-4">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 rounded-lg bg-emerald-500/10 border border-emerald-500/30 flex items-center justify-center text-emerald-400">
              <Cpu className="w-5 h-5" />
            </div>
            <div>
              <div className="flex items-center gap-2">
                <h1 className="text-lg font-bold tracking-tight text-white">
                  POCKET MEDIA FORGE
                </h1>
                <span className="text-xs font-mono uppercase px-2 py-0.5 rounded bg-emerald-500/20 text-emerald-300 border border-emerald-500/40">
                  100% Production Ready (18 Files)
                </span>
              </div>
              <p className="text-xs text-zinc-400">
                Steps 1–6 Complete • Hybrid 6C (GitHub Secrets + Phone UI) • Tag Hunter • Snapdragon h264_mediacodec
              </p>
            </div>
          </div>

          <div className="flex items-center gap-2">
            <button
              onClick={handleDownloadSetupScript}
              className="px-4 py-2 rounded-lg bg-emerald-500 hover:bg-emerald-400 text-zinc-950 text-xs font-bold transition flex items-center gap-2 shadow-lg shadow-emerald-500/10"
            >
              <FolderArchive className="w-4 h-4" />
              Скачать весь проект одним скриптом (.sh)
            </button>
          </div>
        </div>
      </header>

      <main className="max-w-7xl mx-auto px-4 sm:px-6 py-6">
        <div className="grid grid-cols-1 lg:grid-cols-12 gap-6">
          {/* Sidebar file tree */}
          <div className="lg:col-span-4 space-y-4">
            <div className="bg-zinc-900 border border-zinc-800 rounded-xl p-4">
              <div className="flex items-center justify-between mb-3">
                <span className="text-xs font-mono uppercase tracking-wider text-zinc-400">
                  Шаги 1–6 (18 файлов)
                </span>
                <div className="flex flex-wrap gap-1">
                  {(['all', 1, 2, 3, 4, 5, 6] as const).map((stepFilter) => (
                    <button
                      key={String(stepFilter)}
                      onClick={() => setSelectedStepFilter(stepFilter)}
                      className={`px-2 py-0.5 rounded text-xs font-mono transition ${
                        selectedStepFilter === stepFilter
                          ? 'bg-amber-500 text-zinc-950 font-bold'
                          : 'bg-zinc-800 text-zinc-400 hover:text-zinc-200'
                      }`}
                    >
                      {stepFilter === 'all' ? 'Все' : `S${stepFilter}`}
                    </button>
                  ))}
                </div>
              </div>

              <div className="space-y-1.5 max-h-[52vh] overflow-y-auto pr-1">
                {filteredFiles.map((file) => (
                  <button
                    key={file.path}
                    onClick={() => setSelectedFilePath(file.path)}
                    className={`w-full text-left px-3 py-2 rounded-lg text-xs font-mono transition flex items-center justify-between ${
                      currentFile.path === file.path
                        ? 'bg-amber-500/15 text-amber-300 border border-amber-500/30'
                        : 'text-zinc-300 hover:bg-zinc-800/70 border border-transparent'
                    }`}
                  >
                    <div className="truncate pr-2">
                      <span className="text-[10px] text-zinc-500 mr-1.5">[S{file.step}]</span>
                      {file.path.split('/').pop()}
                    </div>
                    <span className="text-[10px] px-1.5 py-0.5 rounded bg-zinc-800 text-zinc-400 shrink-0">
                      {file.language}
                    </span>
                  </button>
                ))}
              </div>
            </div>

            <div className="bg-zinc-900/70 border border-emerald-500/25 rounded-xl p-4 space-y-2.5">
              <div className="flex items-center gap-2 text-emerald-400 text-xs font-semibold">
                <Smartphone className="w-4 h-4 shrink-0" />
                <span>Шаг 6 (Гибрид 6В) готов к сборке</span>
              </div>
              <ul className="text-xs text-zinc-300 space-y-1.5 list-disc list-inside leading-relaxed">
                <li>
                  <strong>MainActivity.kt:</strong> тёмная панель на экране OnePlus для ввода/смены
                  Bot Token, Gemini Key, Chat ID, выбора файла мидролла и выдачи прав OxygenOS.
                </li>
                <li>
                  <strong>NodeConfigStore.kt:</strong> берёт ключи из GitHub Secrets по умолчанию,
                  но даёт менять их на лету с экрана телефона без пересборки APK.
                </li>
              </ul>
            </div>
          </div>

          {/* Code viewer */}
          <div className="lg:col-span-8">
            <div className="bg-zinc-900 border border-zinc-800 rounded-xl overflow-hidden">
              <div className="px-4 py-3 border-b border-zinc-800 flex flex-wrap items-center justify-between gap-2 bg-zinc-900/90">
                <div>
                  <div className="font-mono text-sm text-amber-300 font-semibold">
                    {currentFile.path}
                  </div>
                  <p className="text-xs text-zinc-400 mt-0.5">{currentFile.description}</p>
                </div>
                <div className="flex items-center gap-2">
                  <button
                    onClick={() => handleCopy(currentFile.content, currentFile.path)}
                    className="px-3 py-1.5 rounded-lg bg-zinc-800 hover:bg-zinc-700 text-xs font-medium text-zinc-200 flex items-center gap-1.5 transition"
                  >
                    {copiedPath === currentFile.path ? (
                      <>
                        <Check className="w-3.5 h-3.5 text-emerald-400" />
                        Скопировано
                      </>
                    ) : (
                      <>
                        <Copy className="w-3.5 h-3.5" />
                        Копировать
                      </>
                    )}
                  </button>
                  <button
                    onClick={() => handleDownloadFile(currentFile)}
                    className="px-3 py-1.5 rounded-lg bg-zinc-800 hover:bg-zinc-700 text-xs font-medium text-zinc-200 flex items-center gap-1.5 transition"
                  >
                    <Download className="w-3.5 h-3.5" />
                    Скачать файл
                  </button>
                </div>
              </div>
              <pre className="p-4 text-xs font-mono text-zinc-200 overflow-x-auto leading-relaxed max-h-[74vh]">
                <code>{currentFile.content}</code>
              </pre>
            </div>
          </div>
        </div>
      </main>
    </div>
  );
}
