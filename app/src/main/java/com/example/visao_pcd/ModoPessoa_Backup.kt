package com.example.visao_pcd

import android.graphics.Bitmap

/**
 * BACKUP DE SEGURANÇA - VERSÃO ESTÁVEL 1.0
 * ESTE ARQUIVO NÃO DEVE SER ALTERADO.
 */
class ModoPessoa_Backup(private val groqService: GroqService) {

    fun processar(bitmap: Bitmap, callback: (String) -> Unit) {
        val prompt = """
            Descreva a pessoa nesta foto para alguém que não pode ver. 
            Foque na aparência física, expressão facial, vestimenta e posição. 
            Seja conciso e use linguagem natural em português do Brasil.
        """.trimIndent()
        groqService.analisarImagem(bitmap, prompt, callback)
    }
}
