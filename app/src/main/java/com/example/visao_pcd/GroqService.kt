package com.example.visao_pcd

import android.graphics.Bitmap
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

    private val client = OkHttpClient.Builder()
        .connectTimeout(45, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    fun analisarImagem(bitmap: Bitmap, prompt: String, callback: (String) -> Unit) {
        enviarRequisicao(prompt, encodeImageToBase64(bitmap), callback)
    }

    fun analisarImagemSemBitmap(prompt: String, callback: (String) -> Unit) {
        enviarRequisicao(prompt, null, callback)
    }

    private fun enviarRequisicao(prompt: String, base64Image: String?, callback: (String) -> Unit) {
        val url = "https://api.groq.com/openai/v1/chat/completions"
        
        val json = JSONObject().apply {
            put("model", if (base64Image != null) "llama-3.2-11b-vision-preview" else "llama-3.3-70b-versatile")
            val messages = JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    val content = JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "text")
                            put("text", prompt)
                        })
                        if (base64Image != null) {
                            put(JSONObject().apply {
                                put("type", "image_url")
                                put("image_url", JSONObject().apply {
                                    put("url", "data:image/jpeg;base64,$base64Image")
                                })
                            })
                        }
                    }
                    put("content", content)
                })
            }
            put("messages", messages)
            put("max_tokens", 512)
        }

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .post(json.toString().toRequestBody(JSON))
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("Groq", "Erro de conexão: ${e.message}")
                callback("Erro ao conectar ao serviço de nuvem.")
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string()
                if (response.isSuccessful && body != null) {
                    try {
                        val jsonRes = JSONObject(body)
                        val choices = jsonRes.getJSONArray("choices")
                        val text = choices.getJSONObject(0).getJSONObject("message").getString("content")
                        callback(text.trim())
                    } catch (e: Exception) {
                        Log.e("Groq", "Erro ao processar JSON: ${e.message}")
                        callback("Erro ao processar resposta da inteligência artificial.")
                    }
                } else {
                    Log.e("Groq", "Erro na resposta: $body")
                    callback("O serviço Groq não respondeu corretamente.")
                }
            }
        })
    }

    private fun encodeImageToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        // Redimensionar para economizar banda e tokens se necessário, 
        // mas o Groq lida bem com imagens normais. Mantendo 70% qualidade.
        bitmap.compress(Bitmap.CompressFormat.JPEG, 70, outputStream)
        val byteArray = outputStream.toByteArray()
        return Base64.encodeToString(byteArray, Base64.NO_WRAP)
    }
}
