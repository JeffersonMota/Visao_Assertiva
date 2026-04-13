package com.example.visao_pcd

import android.graphics.Bitmap
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content

class ModoAmbiente(private val geminiModel: GenerativeModel) {

    suspend fun processar(bitmap: Bitmap): String {
        val prompt = "Descreva este ambiente de forma resumida, o que há nele e como é a iluminação."
        
        return try {
            val response = geminiModel.generateContent(content {
                image(bitmap)
                text(prompt)
            })
            response.text ?: "Não foi possível descrever o ambiente."
        } catch (e: Exception) {
            "Erro na análise de ambiente: ${e.message}"
        }
    }
}
