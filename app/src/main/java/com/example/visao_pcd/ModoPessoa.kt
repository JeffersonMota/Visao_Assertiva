package com.example.visao_pcd

import android.graphics.Bitmap
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content

class ModoPessoa(private val geminiModel: GenerativeModel) {

    suspend fun processar(bitmap: Bitmap): String {
        val prompt = "Descreva a pessoa nesta imagem de forma curta para um cego. Diga o que ela parece estar fazendo e a cor da roupa."
        
        return try {
            val response = geminiModel.generateContent(content {
                image(bitmap)
                text(prompt)
            })
            response.text ?: "Não foi possível descrever a pessoa."
        } catch (e: Exception) {
            "Erro na análise de pessoa: ${e.message}"
        }
    }
}
