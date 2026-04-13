package com.example.visao_pcd

import android.graphics.Bitmap
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content

class ModoRoupa(private val geminiModel: GenerativeModel) {

    suspend fun processar(bitmap: Bitmap): String {
        val prompt = "Descreva a cor e o tipo desta roupa detalhadamente para uma pessoa cega."
        
        return try {
            val response = geminiModel.generateContent(content {
                image(bitmap)
                text(prompt)
            })
            response.text ?: "Não foi possível descrever a roupa."
        } catch (e: Exception) {
            "Erro na análise de roupa: ${e.message}"
        }
    }
}
