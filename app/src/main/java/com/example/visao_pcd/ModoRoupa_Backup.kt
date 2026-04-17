package com.example.visao_pcd

import android.graphics.Bitmap

/**
 * BACKUP DE SEGURANÇA - VERSÃO ESTÁVEL 1.0
 * ESTE ARQUIVO NÃO DEVE SER ALTERADO.
 */
class ModoRoupa_Backup(private val groqService: GroqService) {

    fun processar(bitmap: Bitmap, callback: (String) -> Unit) {
        val prompt = """
            Você é um assistente especializado em descrever roupas para pessoas com deficiência visual. Analise a imagem com EXTREMA precisão.
            
            DIRETRIZES CRÍTICAS (Estilo Modo Ambiente):
            1. Descreva APENAS o que está visível na imagem. Se a imagem estiver escura ou borrada, diga: "A imagem da roupa está difícil de identificar, tente melhorar a luz."
            2. Identifique primeiro a COR EXATA (ex: azul marinho, cinza chumbo) e o TIPO de peça (ex: camiseta de algodão, calça jeans).
            3. Mencione detalhes cruciais de design: estampas (listras, xadrez), golas, botões, bolsos ou logotipos visíveis.
            4. Se for uma roupa com textura marcante (ex: tricô, couro, jeans), mencione-a.
            5. Seja conciso e use português do Brasil.

            SAÍDA ESPERADA:
            Uma descrição curta (máximo 3 frases) para que o usuário saiba exatamente o que vai usar.
            Exemplo: "Você está segurando uma camiseta de malha azul marinho com gola redonda. Ela é lisa e tem um pequeno bolso no peito esquerdo. É uma peça casual e confortável."
        """.trimIndent()
        groqService.analisarImagem(bitmap, prompt, callback)
    }
}
