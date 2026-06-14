package com.example.data

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Query
import java.util.concurrent.TimeUnit
import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.util.Log

@JsonClass(generateAdapter = true)
data class GeminiPart(
    val text: String? = null
)

@JsonClass(generateAdapter = true)
data class GeminiContent(
    val parts: List<GeminiPart>
)

@JsonClass(generateAdapter = true)
data class ResponseFormatText(
    val mimeType: String
)

@JsonClass(generateAdapter = true)
data class ResponseFormat(
    val text: ResponseFormatText? = null
)

@JsonClass(generateAdapter = true)
data class GeminiGenerationConfig(
    val responseFormat: ResponseFormat? = null,
    val temperature: Float? = null
)

@JsonClass(generateAdapter = true)
data class GeminiRequest(
    val contents: List<GeminiContent>,
    val generationConfig: GeminiGenerationConfig? = null,
    val systemInstruction: GeminiContent? = null
)

@JsonClass(generateAdapter = true)
data class GeminiCandidate(
    val content: GeminiContent
)

@JsonClass(generateAdapter = true)
data class GeminiResponse(
    val candidates: List<GeminiCandidate>? = null
)

interface GeminiApiService {
    @POST("v1beta/models/gemini-3.5-flash:generateContent")
    suspend fun generateContent(
        @Query("key") apiKey: String,
        @Body request: GeminiRequest
    ): GeminiResponse
}

object RetrofitClient {
    private const val BASE_URL = "https://generativelanguage.googleapis.com/"

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    val service: GeminiApiService by lazy {
        val retrofit = Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create())
            .build()
        retrofit.create(GeminiApiService::class.java)
    }
}

object GeminiHelper {
    private const val TAG = "GeminiHelper"

    fun isApiKeyAvailable(): Boolean {
        val key = BuildConfig.GEMINI_API_KEY
        return !key.isNullOrEmpty() && key != "MY_GEMINI_API_KEY" && key != "placeholder"
    }

    suspend fun parseIngredientsList(ingredients: String): String? = withContext(Dispatchers.IO) {
        if (!isApiKeyAvailable()) {
            Log.w(TAG, "API key is not configured or is placeholder")
            return@withContext null
        }

        val prompt = """
            Analyze the following list of food or cosmetic ingredients and extract ONLY the chemical additives, preservatives, synthetic colors, artificial sweeteners, thickeners, emulsifiers, or potential toxins (like PFAS indications).
            For each identified chemical ingredient, build an object containing:
            1. "name": The lowercase normalized name of the ingredient (e.g. "carrageenan", "titanium dioxide")
            2. "displayName": The proper casing name (e.g. "Carrageenan")
            3. "plainEnglishName": Translate the chemical into friendly plain English (e.g. "Seaweed extract gel" or "White pigment mineral")
            4. "purpose": Explain why the manufacturer used it in plain English (e.g. "To make the product look creamy and white" or "As a preservative to extend shelf life")
            5. "riskLevel": The known health risk of this chemical. Strictly choose one of: "HIGH", "MODERATE", "LOW"
            6. "riskDescription": What are the known health / allergy risks or triggers in plain English (e.g., "Highly toxic in EU, linked to cell DNA damage" or "May trigger gut inflammation and digest bloating")
            7. "dietarySafety": List of lowercased, comma-separated attributes representing flags this chemical violates or trigger categories. Allowed tokens:
               - "non_vegan" if derived from animals (e.g., carmine, gelatin, shellac, whey, dairy/casein)
               - "containing_gluten" if derived from wheat, barley, or rye grains
               - "soy" if derived from soy (e.g., soy lecithin)
               - "dairy" if dairy allergen (e.g., casein, whey)
               - "dye" if synthetic dye (e.g., red 40, yellow 5, blue 1)
               - "pfas" if long-chain fluorochemical or packaging hazard indication
               - "gluten_free" and "vegan" as positive attributes if safe for those categories.
            
            Return the result as a raw JSON array of these objects. Do not include markdown wraps, just the JSON list itself.
            Ingredients text to analyze: "$ingredients"
        """.trimIndent()

        val systemInstructionText = """
            You are "The Chemical Translator", a professional, objective clinical food scientist and consumer health protection advocate.
            You translate complex chemical ingredient lists into plain, understandable English.
            You must output a syntactically correct JSON array of objects. Do not explain the output outside of the JSON.
        """.trimIndent()

        val request = GeminiRequest(
            contents = listOf(
                GeminiContent(parts = listOf(GeminiPart(text = prompt)))
            ),
            generationConfig = GeminiGenerationConfig(
                responseFormat = ResponseFormat(text = ResponseFormatText(mimeType = "application/json")),
                temperature = 0.2f
            ),
            systemInstruction = GeminiContent(parts = listOf(GeminiPart(text = systemInstructionText)))
        )

        try {
            val response = RetrofitClient.service.generateContent(BuildConfig.GEMINI_API_KEY, request)
            response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
        } catch (e: Exception) {
            Log.e(TAG, "Error calling Gemini API", e)
            null
        }
    }
}
