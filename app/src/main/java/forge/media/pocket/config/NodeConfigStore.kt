package forge.media.pocket.config

import android.content.Context
import android.util.Log
import forge.media.pocket.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Centralized Diagnostic Logger that records EVERY action since app/service startup
 * into both an in-memory ring buffer (for live UI & fast Telegram display) and a persistent
 * log file on disk (for full log file export in Telegram).
 */
object DiagnosticLogger {
    private const val TAG = "PocketForgeDiag"
    private const val MAX_MEMORY_LINES = 400

    private val memoryLines = ConcurrentLinkedDeque<String>()
    private val _logFlow = MutableStateFlow<List<String>>(emptyList())
    val logFlow: StateFlow<List<String>> = _logFlow.asStateFlow()

    @Volatile
    private var logFile: File? = null

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun init(context: Context) {
        if (logFile == null) {
            val f = File(context.filesDir, "diagnostic_boot.log")
            logFile = f
            try {
                if (f.exists() && f.length() > 512_000L) {
                    f.writeText("")
                }
            } catch (_: Exception) {
            }
            log("BOOT", "=== ЗАПУСК СЕССИИ ЛОГИРОВАНИЯ POCKET MEDIA FORGE ===")
        }
    }

    @Synchronized
    fun log(stage: String, message: String) {
        val ts = timeFormat.format(Date())
        val cleanMsg = message.trim()
        val formatted = "[$ts] [$stage] $cleanMsg"
        Log.i(TAG, "[$stage] $cleanMsg")

        memoryLines.addLast(formatted)
        while (memoryLines.size > MAX_MEMORY_LINES) {
            memoryLines.pollFirst()
        }
        _logFlow.value = memoryLines.toList()

        try {
            logFile?.appendText("$formatted\n")
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun logError(stage: String, message: String, throwable: Throwable? = null) {
        val errDetail = if (throwable != null) {
            "$message | ${throwable.javaClass.simpleName}: ${throwable.message}"
        } else {
            message
        }
        log("$stage:ERR", "❌ $errDetail")
    }

    fun getRecentLinesText(maxLines: Int = 45): String {
        val list = memoryLines.toList()
        if (list.isEmpty()) return "Лог пока пуст."
        return list.takeLast(maxLines).joinToString("\n")
    }

    fun getFullLogFile(context: Context): File {
        val f = logFile ?: File(context.filesDir, "diagnostic_boot.log")
        if (!f.exists()) {
            f.writeText(getRecentLinesText(MAX_MEMORY_LINES))
        }
        return f
    }

    @Synchronized
    fun clear(context: Context) {
        memoryLines.clear()
        try {
            File(context.filesDir, "diagnostic_boot.log").writeText("")
        } catch (_: Exception) {
        }
        log("LOG", "Журнал очищен пользователем.")
    }
}

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

    fun getAllowedChatIdLong(): Long {
        return getAllowedChatId().toLongOrNull() ?: 0L
    }

    fun setAllowedChatId(chatId: String) {
        prefs.edit().putString(KEY_ALLOWED_CHAT_ID, chatId.trim()).apply()
    }

    fun getYoutubeCookiesFile(): File? {
        val f = File(context.filesDir, "youtube_cookies.txt")
        return if (f.exists() && f.length() > 10L) f else null
    }
}
