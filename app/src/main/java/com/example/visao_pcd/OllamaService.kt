package com.example.visao_pcd

import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class OllamaService(private val serverIp: String = "192.168.0.103") { // Atualizado para o IP real da sua rede Wi-Fi

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    fun gerarDescricao(prompt: String, callback: (String) -> Unit) {
        val url = "http://$serverIp:11434/api/generate"
        
        val json = JSONObject().apply {
            put("model", "llama3.2:1b")
            put("prompt", prompt)
            put("stream", false)
            put("system", "Você é um assistente para pessoas com deficiência visual. Descreva a cena de forma concisa e objetiva em português do Brasil.")
        }

        val request = Request.Builder()
            .url(url)
            .post(json.toString().toRequestBody(JSON))
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("Ollama", "Erro de conexão: ${e.message}")
                callback("Erro ao conectar ao servidor de inteligência local.")
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string()
                if (response.isSuccessful && body != null) {
                    try {
                        val jsonRes = JSONObject(body)
                        val text = jsonRes.getString("response")
                        callback(text.trim())
                    } catch (e: Exception) {
                        callback("Erro ao processar resposta da IA.")
                    }
                } else {
                    callback("Servidor local não respondeu.")
                }
            }
        })
    }
}
