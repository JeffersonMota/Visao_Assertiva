package com.example.visao_pcd

import android.graphics.Bitmap

/**
 * BACKUP DE SEGURANÇA - VERSÃO ESTÁVEL 1.0
 * ESTE ARQUIVO NÃO DEVE SER ALTERADO.
 */
class ModoAmbiente_Backup(private val groqService: GroqService) {

    fun processar(bitmap: Bitmap, callback: (String) -> Unit) {
        val prompt = """
            Você é um assistente para pessoas com deficiência visual. Analise a imagem com EXTREMA precisão.
            
            DIRETRIZES CRÍTICAS:
            1. Descreva APENAS o que está visível na imagem. Não use o conhecimento prévio se não houver evidência visual clara.
            2. Se a imagem estiver escura, borrada ou obstruída, diga: "A imagem está difícil de identificar, tente iluminar ou apontar para outra direção."
            3. Se identificar um cômodo (ex: quarto, sala), mencione-o. Se não tiver certeza, descreva apenas os objetos (ex: "Vejo uma cama e um armário").
            4. Mencione a posição espacial (frente, esquerda, direita).
            5. Seja conciso e use português do Brasil.

            SAÍDA ESPERADA:
            Uma descrição curta (máximo 3 frases) focada na navegação e segurança.
            Exemplo: "Você está em um quarto. Vejo uma cama à frente e uma porta à sua direita. O caminho parece livre."
        """.trimIndent()
        groqService.analisarImagem(bitmap, prompt, callback)
    }
}
