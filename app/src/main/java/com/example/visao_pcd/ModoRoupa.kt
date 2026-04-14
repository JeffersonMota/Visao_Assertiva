package com.example.visao_pcd

import android.graphics.Bitmap

class ModoRoupa(private val groqService: GroqService) {

    fun processar(bitmap: Bitmap, callback: (String) -> Unit) {
        val prompt = "Descreva a cor e o tipo desta roupa detalhadamente para uma pessoa cega. Seja objetivo."
        groqService.analisarImagem(bitmap, prompt, callback)
    }
}
