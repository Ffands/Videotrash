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
            updateNotification("Job #$jobId: $stageDescription")
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
            _statusFlow.value = ServiceStatus.Error("Native init failed: ${e.message}")
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
                    _statusFlow.value = ServiceStatus.Error("Task error: ${e.message}")
                } finally {
                    _statusFlow.value = ServiceStatus.IdleListening
                    updateNotification("Idle • Listening for Telegram commands")
                }
            }
        }
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "Android 14 FGS onTimeout triggered for fgsType=$fgsType")
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
            is ServiceStatus.Processing -> "Job ${s.jobId}: ${s.stage}"
            is ServiceStatus.Error -> "Alert: ${s.reason}"
        }
    }
}
