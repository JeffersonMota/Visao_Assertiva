package com.example.visao_pcd

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage

class ModoAndando(private val modoObjeto: ModoObjeto, private val groqService: GroqService) {

    fun processar(image: InputImage, imageWidth: Int, imageHeight: Int, callback: (Boolean) -> Unit) {
        modoObjeto.processar(image, imageWidth) { resultado ->
            val hasObstacle = resultado.boxes.any { 
                val areaPercent = (it.rect.width() * it.rect.height()).toFloat() / (imageWidth * imageHeight)
                areaPercent > 0.35
            }
            callback(hasObstacle)
        }
    }

    fun analisarCaminho(bitmap: Bitmap, callback: (String) -> Unit) {
        val prompt = "Você é um assistente de navegação para cegos. Descreva brevemente o caminho à frente, mencionando obstáculos grandes ou buracos se houver. Seja muito curto e direto."
        groqService.analisarImagem(bitmap, prompt, callback)
    }
}
