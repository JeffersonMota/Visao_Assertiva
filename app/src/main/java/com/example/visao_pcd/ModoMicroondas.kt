package com.example.visao_pcd

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ModoMicroondas(private val groqService: GroqService) {

    data class BotaoMicroondas(val id: Int, val nome: String, val rect: RectF)

    private var botoesDetectados: List<BotaoMicroondas> = emptyList()
    private var lastAnalysisTime: Long = 0
    private var isAnalyzing: Boolean = false
    private val ANALYSIS_INTERVAL = 10000L // 10 segundos para re-analisar o painel completo

    fun estaAnalisando() = isAnalyzing

    fun analisarPainel(bitmap: Bitmap, callback: (List<BotaoMicroondas>) -> Unit) {
        val agora = System.currentTimeMillis()
        
        // Se já estiver analisando, não faz nada para não sobrecarregar
        if (isAnalyzing) return

        // Se já temos botões e estamos dentro do intervalo, apenas retorna os existentes
        if (agora - lastAnalysisTime < ANALYSIS_INTERVAL && botoesDetectados.isNotEmpty()) {
            callback(botoesDetectados)
            return
        }
        
        isAnalyzing = true
        lastAnalysisTime = agora

        val prompt = """
            Você é um assistente para cegos. Analise este painel de micro-ondas.
            Localize TODOS os botões, números e funções (ex: Pipoca, Descongelar, Ligar).
            Retorne APENAS um array JSON no formato:
            [{"nome": "Ligar", "ymin": 800, "xmin": 700, "ymax": 900, "xmax": 950}, ...]
            Use coordenadas normalizadas de 0 a 1000 baseadas na imagem.
            Não escreva mais nada, apenas o JSON.
        """.trimIndent()

        Log.d("ModoMicroondas", "Enviando imagem para análise do Groq...")
        groqService.analisarImagem(bitmap, prompt) { responseText ->
            isAnalyzing = false
            Log.d("ModoMicroondas", "Resposta do Groq: $responseText")
            
            if (responseText.startsWith("Erro:")) {
                callback(emptyList())
                return@analisarImagem
            }

            val cleanJson = responseText.replace("```json", "").replace("```", "").trim()
            val novosBotoes = parseBotoes(cleanJson, bitmap.width, bitmap.height)
            
            if (novosBotoes.isNotEmpty()) {
                botoesDetectados = novosBotoes
                callback(novosBotoes)
            } else {
                Log.e("ModoMicroondas", "Nenhum botão detectado no JSON")
                callback(emptyList())
            }
        }
    }

    private fun parseBotoes(json: String, imgW: Int, imgH: Int): List<BotaoMicroondas> {
        return try {
            val list = mutableListOf<BotaoMicroondas>()
            
            // Tenta extrair o JSON do texto caso a IA tenha incluído conversas
            val startIndex = json.indexOf("[")
            val endIndex = json.lastIndexOf("]")
            if (startIndex == -1 || endIndex == -1) {
                Log.e("ModoMicroondas", "JSON inválido: não encontrou colchetes")
                return emptyList()
            }
            val cleanJson = json.substring(startIndex, endIndex + 1)
            
            val jsonArray = org.json.JSONArray(cleanJson)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                
                // Coordenadas padrão de detecção [0-1000]
                val ymin = obj.optDouble("ymin", 0.0).toFloat()
                val xmin = obj.optDouble("xmin", 0.0).toFloat()
                val ymax = obj.optDouble("ymax", 0.0).toFloat()
                val xmax = obj.optDouble("xmax", 0.0).toFloat()
                
                // Conversão para pixels reais da imagem
                val left = (xmin / 1000f) * imgW
                val top = (ymin / 1000f) * imgH
                val right = (xmax / 1000f) * imgW
                val bottom = (ymax / 1000f) * imgH
                
                val nome = obj.optString("nome", "Botão")
                list.add(BotaoMicroondas(i, nome, RectF(left, top, right, bottom)))
            }
            Log.d("ModoMicroondas", "Parse concluído: ${list.size} botões encontrados")
            list
        } catch (e: Exception) {
            Log.e("ModoMicroondas", "Erro ao processar JSON: ${e.message}")
            emptyList()
        }
    }

    fun detectarBotaoNoToque(x: Float, y: Float, viewW: Int, viewH: Int, overlay: OverlayView): String? {
        Log.d("ModoMicroondas", "Toque em: $x, $y. Total botões: ${botoesDetectados.size}")
        for (botao in botoesDetectados) {
            val rectF = overlay.getScaledRectForAnalysis(botao.rect)
            Log.d("ModoMicroondas", "Testando botão ${botao.nome}: $rectF")
            if (rectF.contains(x, y)) {
                return botao.nome
            }
        }
        return null
    }

    inner class MicroondasTouchHelper(private val host: View, private val overlay: OverlayView) : ExploreByTouchHelper(host) {
        override fun getVirtualViewAt(x: Float, y: Float): Int {
            botoesDetectados.forEach { botao ->
                val rectF = overlay.getScaledRectForAnalysis(botao.rect)
                if (rectF.contains(x, y)) return botao.id
            }
            return INVALID_ID
        }

        override fun getVisibleVirtualViews(virtualViewIds: MutableList<Int>) {
            botoesDetectados.forEach { virtualViewIds.add(it.id) }
        }

        override fun onPopulateNodeForVirtualView(virtualViewId: Int, node: AccessibilityNodeInfoCompat) {
            val botao = botoesDetectados.find { it.id == virtualViewId }
            if (botao != null) {
                node.contentDescription = botao.nome
                val rectF = overlay.getScaledRectForAnalysis(botao.rect)
                val rect = Rect()
                rectF.roundOut(rect)
                node.setBoundsInParent(rect)
                node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK)
            }
        }

        override fun onPerformActionForVirtualView(virtualViewId: Int, action: Int, arguments: Bundle?): Boolean {
            if (action == AccessibilityNodeInfoCompat.ACTION_CLICK) {
                val botao = botoesDetectados.find { it.id == virtualViewId }
                if (botao != null) {
                    host.announceForAccessibility("Selecionado: ${botao.nome}")
                    return true
                }
            }
            return false
        }
    }
}
