package com.example.visao_pcd

import android.graphics.Bitmap

class ModoAndando(private val modoObjeto: ModoObjeto, private val groqService: GroqService) {

    fun processar(bitmap: Bitmap, callback: (Boolean) -> Unit) {
        modoObjeto.processar(bitmap) { resultado ->
            val hasObstacle = resultado.boxes.any { 
                val areaPercent = (it.rect.width() * it.rect.height()).toFloat() / (bitmap.width * bitmap.height)
                areaPercent > 0.20 // Reduzido de 0.35 para 0.20 para ser mais sensível
            }
            callback(hasObstacle)
        }
    }

    fun analisarCaminho(bitmap: Bitmap, callback: (String) -> Unit) {
        val prompt = "Você é um assistente de navegação para cegos. Descreva brevemente o caminho à frente, mencionando obstáculos grandes ou buracos se houver. Seja muito curto e direto."
        groqService.analisarImagem(bitmap, prompt, callback)
    }
}
