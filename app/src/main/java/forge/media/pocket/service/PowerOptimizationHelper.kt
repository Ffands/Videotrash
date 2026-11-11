package forge.media.pocket.service

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
            data = Uri.parse("package:${context.packageName}")
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
}
