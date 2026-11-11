package forge.media.pocket.config

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
}
