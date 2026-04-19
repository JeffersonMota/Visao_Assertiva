package com.example.visao_pcd

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

class GroqService(private val apiKey: String = BuildConfig.GROQ_API_KEY) {

    private val finalApiKey = apiKey.ifEmpty { "SUA_CHAVE_AQUI" }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        // Modelos Estáveis e Gratuitos do Groq (Janeiro 2024)
        private const val VISION_MODEL = "llama-3.2-11b-vision-instant"
        private const val TEXT_MODEL_PRIMARY = "llama-3.3-70b-versatile"
        private const val TEXT_MODEL_SECONDARY = "llama-3.1-8b-instant"
    }

    // Lista de modelos para fallback (Visão)
    private val visionModels = listOf(VISION_MODEL)

    // Lista de modelos para fallback (Texto)
    private val textModels = listOf(TEXT_MODEL_PRIMARY, TEXT_MODEL_SECONDARY)

    fun analisarImagem(bitmap: Bitmap, prompt: String, callback: (String) -> Unit) {
        val base64 = encodeImageToBase64(bitmap)
        tentarModelos(prompt, base64, visionModels, 0, "") { resposta ->
            callback(resposta)
        }
    }

    fun analisarImagemSemBitmap(prompt: String, callback: (String) -> Unit) {
        tentarModelos(prompt, null, textModels, 0, "", callback)
    }

    private fun tentarModelos(
        prompt: String,
        base64Image: String?,
        models: List<String>,
        index: Int,
        lastError: String,
        callback: (String) -> Unit
    ) {
        if (index >= models.size) {
            val msg = when {
                lastError.contains("insufficient_quota") -> "Cota esgotada no Groq (Visão)."
                lastError.contains("rate_limit") -> "Muitas fotos. Aguarde 10s."
                else -> "Erro: $lastError"
            }
            mainHandler.post { callback(msg) }
            return
        }

        val currentModel = models[index]
        val json = JSONObject().apply {
            put("model", currentModel)
            
            val messages = JSONArray()
            
            // System Prompt: Define a persona de forma global e curta
            messages.put(JSONObject().apply {
                put("role", "system")
                put("content", "Você é um assistente para pessoas com deficiência visual. Seja conciso e use português do Brasil.")
            })

            val userMessage = JSONObject().apply {
                put("role", "user")
                if (base64Image != null) {
                    val content = JSONArray().apply {
                        put(JSONObject().apply { put("type", "text"); put("text", prompt) })
                        put(JSONObject().apply {
                            put("type", "image_url")
                            put("image_url", JSONObject().apply { put("url", "data:image/jpeg;base64,$base64Image") })
                        })
                    }
                    put("content", content)
                } else {
                    put("content", prompt)
                }
            }
            messages.put(userMessage)
            
            put("messages", messages)
            put("max_tokens", 300)
        }

        val request = Request.Builder()
            .url("https://api.groq.com/openai/v1/chat/completions")
            .addHeader("Authorization", "Bearer $finalApiKey")
            .post(json.toString().toRequestBody(JSON_TYPE))
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                tentarModelos(prompt, base64Image, models, index + 1, "Sem Internet", callback)
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    val text = JSONObject(body).getJSONArray("choices")
                        .getJSONObject(0).getJSONObject("message").getString("content")
                    mainHandler.post { callback(text.trim()) }
                } else {
                    Log.e("Groq", "Erro no $currentModel: $body")
                    tentarModelos(prompt, base64Image, models, index + 1, body, callback)
                }
            }
        })
    }

    private fun encodeImageToBase64(bitmap: Bitmap): String {
        // 1. Redimensionamento (Regra de Ouro: ~800px para economia de tokens e latência)
        val maxDim = 800 
        val scale = Math.min(maxDim.toFloat() / bitmap.width, maxDim.toFloat() / bitmap.height)
        val resized = if (scale < 1f) {
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
        } else bitmap
        
        // 2. Compressão (JPEG 75% - Equilíbrio ideal entre peso e nitidez para a IA)
        val out = ByteArrayOutputStream()
        resized.compress(Bitmap.CompressFormat.JPEG, 75, out) 
        val b = out.toByteArray()
        
        Log.d("Groq", "Payload de imagem otimizado: ${b.size / 1024} KB")

        if (resized != bitmap) resized.recycle()
        return Base64.encodeToString(b, Base64.NO_WRAP)
    }
}
