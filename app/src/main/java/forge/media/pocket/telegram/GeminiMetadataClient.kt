package forge.media.pocket.telegram

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
            adConfig.promoLinkForComment.isNotBlank()
        ) {
            "IMPORTANT (Ad Strategy C): Naturally weave this promotional hook and link into 'pinnedComment' and 'description': " +
                "${adConfig.bannerText} ${adConfig.promoLinkForComment}"
        } else {
            "In 'pinnedComment', include the original author or anime/show title and ask an engaging question."
        }

        val prompt = """
            You are a viral short-form video producer (YouTube Shorts / Reels / TikTok).
            Source URL: $sourceUrl
            Original Video Title: $originalTitle
            Original Channel / Author: $originalAuthor
            Original Tags: ${sourceTags.take(15).joinToString(", ")}
            Spoken Transcript Excerpt: ${transcriptText.take(1500)}
            
            1. Identify the exact creator/speaker name OR anime/movie/game title from the metadata above.
            2. Create 3 different catchy viral titles (under 70 chars each).
            3. $promoInstruction
            
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
                "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey"

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
}
