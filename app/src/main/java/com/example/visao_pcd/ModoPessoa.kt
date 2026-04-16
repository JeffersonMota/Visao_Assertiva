package com.example.visao_pcd

import android.graphics.Bitmap

class ModoPessoa(private val groqService: GroqService) {

    fun processar(bitmap: Bitmap, callback: (String) -> Unit) {
        val prompt = "Analise esta imagem e descreva apenas a pessoa principal. " +
                    "Seja direto: diga o gênero aparente, o que está fazendo e a cor da roupa. " +
                    "Use no máximo 15 palavras. Responda em Português do Brasil."
        groqService.analisarImagem(bitmap, prompt, callback)
    }
}
