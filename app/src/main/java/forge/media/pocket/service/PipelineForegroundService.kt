package forge.media.pocket.service

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
import com.yausername.youtubedl_android.YoutubeDLRequest
import forge.media.pocket.clutter.ClutterEntropyFilter
import forge.media.pocket.compositor.FFmpegHardwareEngine
import forge.media.pocket.config.DiagnosticLogger
import forge.media.pocket.config.NodeConfigStore
import forge.media.pocket.discovery.TopicHunterEngine
import forge.media.pocket.subtitles.SubtitleAssTransformer
import forge.media.pocket.telegram.GeminiMetadataClient
import forge.media.pocket.telegram.TelegramBotWorker
import forge.media.pocket.vision.FaceCenteringHelper
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.utils.io.jvm.javaio.copyTo
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

        private val updaterMutex = Mutex()

        private val updaterHttpClient by lazy {
            HttpClient(OkHttp) {
                install(HttpTimeout) {
                    requestTimeoutMillis = 90_000L
                    connectTimeoutMillis = 20_000L
                    socketTimeoutMillis = 90_000L
                }
            }
        }

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

        fun queryRealYtDlpVersion(): String {
            return try {
                val req = YoutubeDLRequest(emptyList()).apply {
                    addOption("--version")
                }
                val out = YoutubeDL.getInstance().execute(req).out.trim()
                out.lines().firstOrNull { it.isNotBlank() }?.trim() ?: "unknown"
            } catch (e: Exception) {
                "err (${e.message?.take(40)})"
            }
        }

        suspend fun ensureModernYtDlpBinary(
            context: Context,
            forceDownload: Boolean = false
        ): String = updaterMutex.withLock {
            withContext(Dispatchers.IO) {
                val currentVer = queryRealYtDlpVersion()
                DiagnosticLogger.log("YTDLP-VER", "Проверка реальной версии бинарника yt-dlp: $currentVer")

                if (!forceDownload && (currentVer.startsWith("2026.") || currentVer.startsWith("2025.12"))) {
                    DiagnosticLogger.log("YTDLP-UPDATE", "Бинарник yt-dlp уже свежий ($currentVer), замена не требуется.")
                    return@withContext currentVer
                }

                val ytdlpDir = File(File(context.noBackupFilesDir, "youtubedl-android"), "yt-dlp").apply {
                    mkdirs()
                }
                val targetBinary = File(ytdlpDir, "yt-dlp")
                val backupBinary = File(ytdlpDir, "yt-dlp.bundled.bak")

                if (targetBinary.exists() && targetBinary.length() > 100_000L && !backupBinary.exists()) {
                    try {
                        targetBinary.copyTo(backupBinary, overwrite = true)
                    } catch (_: Exception) {
                    }
                }

                // Candidate 1: 2026.10.21 (Adds tv_samsung default YouTube client & supports Python 3.9/3.10!)
                // Candidate 2: latest (2026.10.26+, requires Python 3.11+)
                val candidateUrls = listOf(
                    "2026.10.21 (tv_samsung + Py3.10)" to "https://github.com/yt-dlp/yt-dlp/releases/download/2026.10.21/yt-dlp",
                    "latest" to "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp"
                )

                for ((label, url) in candidateUrls) {
                    val tempFile = File(context.cacheDir, "ytdlp_download_${System.currentTimeMillis()}.tmp")
                    try {
                        DiagnosticLogger.log("YTDLP-UPDATE", "Скачиваю ядро yt-dlp [$label] напрямую с GitHub (таймаут 90с)...")
                        val response = updaterHttpClient.get(url) {
                            header(
                                HttpHeaders.UserAgent,
                                "Mozilla/5.0 (Linux; Android 14; OnePlus) AppleWebKit/537.36 Chrome/126.0.0.0 Mobile Safari/537.36"
                            )
                        }
                        if (!response.status.isSuccess()) {
                            DiagnosticLogger.log("YTDLP-UPDATE", "GitHub вернул HTTP ${response.status.value} для $label")
                            continue
                        }

                        tempFile.outputStream().use { out ->
                            response.bodyAsChannel().copyTo(out)
                        }

                        if (!tempFile.exists() || tempFile.length() < 1_000_000L) {
                            DiagnosticLogger.log("YTDLP-UPDATE", "Файл $label слишком мал (${tempFile.length()} байт), пропуск")
                            continue
                        }

                        tempFile.copyTo(targetBinary, overwrite = true)
                        targetBinary.setExecutable(true, false)
                        targetBinary.setReadable(true, false)

                        val testedVer = queryRealYtDlpVersion()
                        if (testedVer.startsWith("202")) {
                            DiagnosticLogger.log(
                                "YTDLP-UPDATE",
                                "✅ Ядро yt-dlp успешно обновлено и проверено! Рабочая версия: $testedVer (${targetBinary.length() / 1024} KB)"
                            )
                            return@withContext testedVer
                        } else {
                            DiagnosticLogger.log(
                                "YTDLP-UPDATE",
                                "⚠️ Бинарник [$label] не запустился на текущем libpython.so (ответ: $testedVer). Пробую следующий..."
                            )
                        }
                    } catch (e: Exception) {
                        DiagnosticLogger.logError("YTDLP-UPDATE", "Сбой прямого скачивания [$label]", e)
                    } finally {
                        tempFile.delete()
                    }
                }

                // Restore backup if custom downloads failed compatibility check
                if (backupBinary.exists() && backupBinary.length() > 100_000L) {
                    try {
                        backupBinary.copyTo(targetBinary, overwrite = true)
                        targetBinary.setExecutable(true, false)
                    } catch (_: Exception) {
                    }
                }

                val fallbackVer = queryRealYtDlpVersion()
                DiagnosticLogger.log("YTDLP-UPDATE", "Оставлена базовая версия yt-dlp: $fallbackVer")
                fallbackVer
            }
        }
    }

    private lateinit var powerHelper: PowerOptimizationHelper
    private lateinit var notificationManager: NotificationManager
    private lateinit var configStore: NodeConfigStore
    private var telegramWorker: TelegramBotWorker? = null

    private val jobQueue = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val renderDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val lifecycleMutex = Mutex()

    @Volatile
    private var isNativeEnvReady = false

    override fun onCreate() {
        super.onCreate()
        DiagnosticLogger.init(applicationContext)
        DiagnosticLogger.log("SERVICE", "onCreate() вызван. Инициализация узла Snapdragon...")

        configStore = NodeConfigStore(applicationContext)
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()

        powerHelper = PowerOptimizationHelper(applicationContext)
        powerHelper.acquireLocksAndRegisterScreenWatcher()
        DiagnosticLogger.log(
            "POWER",
            "WakeLock и WifiLock захвачены. Игнор батареи=${powerHelper.isIgnoringBatteryOptimizations()}"
        )

        promoteToForeground("Initializing Snapdragon Media Pipeline...")

        lifecycleScope.launch(Dispatchers.IO) {
            lifecycleMutex.withLock {
                initializeNativeToolchains()
                startSequentialJobConsumer()
                restartTelegramBotControllerInternal()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        DiagnosticLogger.log("SERVICE", "onStartCommand action=${intent?.action ?: "null"}")
        when (intent?.action) {
            ACTION_STOP_ENGINE -> {
                DiagnosticLogger.log("SERVICE", "Получена команда остановки узла (ACTION_STOP_ENGINE)")
                _statusFlow.value = ServiceStatus.Stopped
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RELOAD_CREDENTIALS -> {
                if (!isNativeEnvReady) {
                    DiagnosticLogger.log(
                        "SERVICE",
                        "ACTION_RELOAD_CREDENTIALS получен во время первичной инициализации — бот запустится автоматически после готовности yt-dlp."
                    )
                } else {
                    DiagnosticLogger.log("SERVICE", "Перезагрузка ключей Telegram и Gemini...")
                    promoteToForeground("Reloading Telegram & Gemini credentials...")
                    lifecycleScope.launch(Dispatchers.IO) {
                        lifecycleMutex.withLock {
                            restartTelegramBotControllerInternal()
                        }
                    }
                }
            }
            else -> promoteToForeground(currentStatusText())
        }
        return START_STICKY
    }

    fun submitHardwareTask(jobId: String, stageDescription: String, block: suspend () -> Unit) {
        DiagnosticLogger.log("QUEUE", "Задача #$jobId поставлена в очередь: $stageDescription")
        jobQueue.trySend {
            _statusFlow.value = ServiceStatus.Processing(jobId, stageDescription)
            updateNotification("Job #$jobId: $stageDescription")
            DiagnosticLogger.log("WORKER", "▶️ Старт выполнения задачи #$jobId: $stageDescription")
            block()
            DiagnosticLogger.log("WORKER", "✅ Завершено выполнение задачи #$jobId")
        }
    }

    private suspend fun initializeNativeToolchains() = withContext(Dispatchers.IO) {
        try {
            _statusFlow.value = ServiceStatus.Initializing
            updateNotification("Bootstrapping native yt-dlp & Aria2c binaries...")

            DiagnosticLogger.log("YTDLP-INIT", "Распаковка бинарников YoutubeDL (0.18.1), FFmpeg, Aria2c...")
            YoutubeDL.getInstance().init(applicationContext)
            FFmpeg.getInstance().init(applicationContext)
            Aria2c.getInstance().init(applicationContext)

            File(filesDir, "clutter_pool").mkdirs()
            File(cacheDir, "work_staging").mkdirs()

            updateNotification("Updating yt-dlp binary for YouTube 2026...")
            val activeVersion = ensureModernYtDlpBinary(applicationContext, forceDownload = false)
            DiagnosticLogger.log("YTDLP-INIT", "✅ Бинарники готовы к работе. Активная версия yt-dlp: $activeVersion")

            isNativeEnvReady = true
            _statusFlow.value = ServiceStatus.IdleListening
            updateNotification("Idle • Listening for Telegram commands (yt-dlp $activeVersion)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize native toolchains", e)
            DiagnosticLogger.logError("YTDLP-INIT", "Критическая ошибка инициализации бинарников", e)
            _statusFlow.value = ServiceStatus.Error("Native init failed: ${e.message}")
            updateNotification("Error: Native toolchain init failed")
        }
    }

    private fun restartTelegramBotControllerInternal() {
        telegramWorker?.stopPolling()
        val token = configStore.getTelegramBotToken()
        if (token.isBlank()) {
            DiagnosticLogger.log("TELEGRAM", "⚠️ Токен бота пуст! Ожидаю ввода в окне телефона.")
            _statusFlow.value = ServiceStatus.Error("Telegram Bot Token not set (enter in app UI)")
            updateNotification("Waiting for Telegram Bot Token in app UI...")
            return
        }

        DiagnosticLogger.log(
            "TELEGRAM",
            "Запуск Long-Polling бота (токен ${token.take(8)}..., allowedChatId=${configStore.getAllowedChatId().ifBlank { "ЛЮБОЙ" }})"
        )

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
                while (!isNativeEnvReady) {
                    kotlinx.coroutines.delay(500L)
                }
                try {
                    task.invoke()
                } catch (e: Exception) {
                    Log.e(TAG, "Hardware task failed", e)
                    DiagnosticLogger.logError("WORKER", "Необработанная ошибка задачи", e)
                    _statusFlow.value = ServiceStatus.Error("Task error: ${e.message}")
                } finally {
                    _statusFlow.value = ServiceStatus.IdleListening
                    updateNotification("Idle • Listening for Telegram commands")
                }
            }
        }
    }

    override fun onTimeout(startId: Int) {
        DiagnosticLogger.log("SERVICE", "⚠️ Android 14 FGS onTimeout triggered for startId=$startId")
        super.onTimeout(startId)
    }

    override fun onDestroy() {
        DiagnosticLogger.log("SERVICE", "onDestroy() вызван — остановка сервиса.")
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
            is ServiceStatus.Processing -> "Job ${s.jobId}: ${s.stage}"
            is ServiceStatus.Error -> "Alert: ${s.reason}"
        }
    }
}
