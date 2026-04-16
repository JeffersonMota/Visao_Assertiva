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
            Identifique TODOS os botões deste painel de micro-ondas. 
            Retorne uma lista JSON com o nome de cada botão e suas coordenadas [x, y, w, h] normalizadas de 0 a 1000.
            Exemplo: [{"nome": "Ligar", "x": 800, "y": 900, "w": 100, "h": 50}, ...]
            Não inclua texto explicativo, apenas o array JSON.
        """.trimIndent()

        Log.d("ModoMicroondas", "Enviando imagem para análise do Groq...")
        groqService.analisarImagem(bitmap, prompt) { responseText ->
            isAnalyzing = false
            Log.d("ModoMicroondas", "Resposta do Groq: $responseText")
            val cleanJson = responseText.replace("```json", "").replace("```", "").trim()
            val novosBotoes = parseBotoes(cleanJson, bitmap.width, bitmap.height)
            
            if (novosBotoes.isNotEmpty()) {
                botoesDetectados = novosBotoes
                callback(novosBotoes)
            } else {
                Log.e("ModoMicroondas", "Nenhum botão detectado no JSON")
                // Se falhou mas temos antigos, podemos retornar os antigos como fallback?
                // Por enquanto apenas logamos.
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
                
                // Pegamos as coordenadas normalizadas (0-1000)
                // O Groq costuma retornar como x, y (centro) ou x, y (top-left). 
                // Vamos assumir top-left baseado no prompt de exemplo.
                val xNorm = obj.optDouble("x", 0.0).toFloat()
                val yNorm = obj.optDouble("y", 0.0).toFloat()
                val wNorm = obj.optDouble("w", 0.0).toFloat()
                val hNorm = obj.optDouble("h", 0.0).toFloat()
                
                // Convertemos para pixels da imagem original
                val x = (xNorm / 1000f) * imgW
                val y = (yNorm / 1000f) * imgH
                val w = (wNorm / 1000f) * imgW
                val h = (hNorm / 1000f) * imgH
                
                val nome = obj.optString("nome", "Botão")
                // Criamos o RectF nas coordenadas da imagem
                list.add(BotaoMicroondas(i, nome, RectF(x, y, x + w, y + h)))
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
