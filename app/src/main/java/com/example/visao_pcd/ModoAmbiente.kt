package com.example.visao_pcd

import android.graphics.Bitmap

class ModoAmbiente(private val groqService: GroqService) {

    fun processar(bitmap: Bitmap, callback: (String) -> Unit) {
        val prompt = "Identifique o tipo de ambiente (ex: sala, cozinha, rua) e liste os 3 objetos principais que confirmam isso para um cego se localizar. Seja direto e use no máximo 25 palavras."
        groqService.analisarImagem(bitmap, prompt, callback)
    }
}
