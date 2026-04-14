package com.example.visao_pcd

import android.graphics.Bitmap

class ModoAmbiente(private val groqService: GroqService) {

    fun processar(bitmap: Bitmap, callback: (String) -> Unit) {
        val prompt = "Descreva este ambiente de forma resumida, o que há nele e como é a iluminação. Seja direto e breve para um cego."
        groqService.analisarImagem(bitmap, prompt, callback)
    }
}
