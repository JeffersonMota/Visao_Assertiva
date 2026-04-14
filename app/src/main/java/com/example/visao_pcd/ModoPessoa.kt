package com.example.visao_pcd

import android.graphics.Bitmap

class ModoPessoa(private val groqService: GroqService) {

    fun processar(bitmap: Bitmap, callback: (String) -> Unit) {
        val prompt = "Descreva a pessoa nesta imagem de forma curta para um cego. Diga o que ela parece estar fazendo e a cor da roupa. Seja breve e objetivo."
        groqService.analisarImagem(bitmap, prompt, callback)
    }
}
