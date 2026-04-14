package com.example.visao_pcd

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ModoMicroondas(private val groqService: GroqService) {

    data class BotaoMicroondas(val nome: String, val rect: RectF)

    private var botoesDetectados: List<BotaoMicroondas> = emptyList()
    private var lastAnalysisTime: Long = 0
    private val ANALYSIS_INTERVAL = 10000L // 10 segundos para re-analisar o painel completo

    fun analisarPainel(bitmap: Bitmap, callback: (List<BotaoMicroondas>) -> Unit) {
        val agora = System.currentTimeMillis()
        if (agora - lastAnalysisTime < ANALYSIS_INTERVAL && botoesDetectados.isNotEmpty()) {
            callback(botoesDetectados)
            return
        }
        lastAnalysisTime = agora

        val prompt = """
            Analise a imagem deste painel de micro-ondas. 
            Identifique a localização exata de cada botão (Pipoca, Arroz, Bebidas, Vegetais, Carnes, Peixes, Números 0-9, Ligar, Desligar, etc).
            Retorne uma lista no formato JSON: 
            [{"nome": "nome_do_botao", "x": top_left_x, "y": top_left_y, "w": width, "h": height}]
            Use coordenadas normalizadas de 0 a 1000 em relação ao tamanho da imagem.
            Retorne APENAS o JSON.
        """.trimIndent()

        groqService.analisarImagem(bitmap, prompt) { responseText ->
            val jsonText = responseText.replace("```json", "").replace("```", "").trim()
            val novosBotoes = parseBotoes(jsonText, bitmap.width, bitmap.height)
            botoesDetectados = novosBotoes
            callback(novosBotoes)
        }
    }

    private fun parseBotoes(json: String, imgW: Int, imgH: Int): List<BotaoMicroondas> {
        return try {
            val list = mutableListOf<BotaoMicroondas>()
            val jsonArray = org.json.JSONArray(json)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val nome = obj.getString("nome")
                val x = obj.getDouble("x").toFloat() / 1000f * imgW
                val y = obj.getDouble("y").toFloat() / 1000f * imgH
                val w = obj.getDouble("w").toFloat() / 1000f * imgW
                val h = obj.getDouble("h").toFloat() / 1000f * imgH
                list.add(BotaoMicroondas(nome, RectF(x, y, x + w, y + h)))
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun detectarBotaoNoToque(x: Float, y: Float, viewW: Int, viewH: Int, overlay: OverlayView): String? {
        for (botao in botoesDetectados) {
            // Converte o Retangulo do botão (coordenadas da imagem original) para coordenadas da View usando OverlayView
            val rectF = overlay.getScaledRectForAnalysis(botao.rect)
            if (rectF.contains(x, y)) {
                return botao.nome
            }
        }
        return null
    }
}
