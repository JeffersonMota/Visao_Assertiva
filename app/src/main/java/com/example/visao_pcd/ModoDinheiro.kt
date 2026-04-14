package com.example.visao_pcd

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.LocalModel
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeler
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.custom.CustomImageLabelerOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/**
 * ModoDinheiro: Reconhecimento de cédulas de Real.
 * Focado em evitar falsos positivos e trocas rápidas de valores (crucial para cegos).
 * Trigger: OCR + TFLite (Labels) + Cor (HSV).
 */
class ModoDinheiro {
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private var imageLabeler: ImageLabeler? = null
    private var tfliteFailedPermanently = false

    init {
        loadTFLite()
    }

    private fun loadTFLite() {
        try {
            val localModel = LocalModel.Builder()
                .setAssetFilePath("detector.tflite")
                .build()

            imageLabeler = ImageLabeling.getClient(
                CustomImageLabelerOptions.Builder(localModel)
                    .setConfidenceThreshold(0.40f)
                    .setMaxResultCount(3)
                    .build()
            )
        } catch (e: Exception) {
            Log.e("ModoDinheiro", "Falha ao carregar detector.tflite: ${e.message}")
            tfliteFailedPermanently = true
        }
    }

    private var lastSpokenMoneyValue = ""
    private var lastSpokenMoneyTime = 0L
    private var lastDetectedValue = ""
    private var lastDetectionTime = 0L
    
    // Filtro de estabilidade
    private var consecutiveMatches = 0
    private var currentCandidate = ""
    
    private val MIN_INTERVAL_BETWEEN_DIFFERENT_VALUES = 3500L 

    fun processar(image: InputImage, bitmap: Bitmap, callback: (valor: String, deveFalar: Boolean) -> Unit) {
        val now = System.currentTimeMillis()
        
        val taskOCR = textRecognizer.process(image)
        val taskTFLite = if (!tfliteFailedPermanently) imageLabeler?.process(image) else null

        val tasks = mutableListOf<com.google.android.gms.tasks.Task<*>>(taskOCR)
        if (taskTFLite != null) tasks.add(taskTFLite)

        Tasks.whenAllComplete(tasks).addOnSuccessListener {
            val visionText = if (taskOCR.isSuccessful) taskOCR.result else null
            
            val labels = if (taskTFLite?.isSuccessful == true) {
                taskTFLite.result
            } else {
                if (taskTFLite != null && taskTFLite.isComplete && !taskTFLite.isSuccessful) {
                    val error = taskTFLite.exception?.message ?: "erro desconhecido"
                    Log.e("ModoDinheiro", "TFLite falhou: $error")
                    if (error.contains("dimensions") || error.contains("INVALID_ARGUMENT")) {
                        tfliteFailedPermanently = true
                        Log.e("ModoDinheiro", "Desativando TFLite permanentemente devido a erro de compatibilidade do modelo.")
                    }
                }
                null
            }

            // 1. Extração de dados multi-fonte
            val ocrValue = extractValueFromText(visionText?.text?.uppercase() ?: "")
            val tfliteLabels = labels?.mapNotNull { mapLabelToNote(it.text) } ?: emptyList()
            val colorValue = identifyRealNoteByColor(bitmap)

            Log.d("ModoDinheiro", "Deteções: OCR=$ocrValue, TFLite=$tfliteLabels, Cor=$colorValue")

            // 2. Lógica de Consenso com Trava de Conflito
            var detectedNow: String? = null
            
            // Verifica conflito direto entre OCR e Cor
            val hasConflict = ocrValue != null && colorValue != null && ocrValue != colorValue

            if (hasConflict) {
                Log.w("ModoDinheiro", "CONFLITO: OCR($ocrValue) vs Cor($colorValue). Aguardando estabilidade.")
                consecutiveMatches = 0
                currentCandidate = ""
            } else {
                // PRIORIDADE 1: Consenso OCR + (Animal ou Cor) -> Confiança Máxima
                if (ocrValue != null && (tfliteLabels.contains(ocrValue) || colorValue == ocrValue)) {
                    detectedNow = ocrValue
                    Log.d("ModoDinheiro", "Consenso atingido: OCR + Outro ($detectedNow)")
                } 
                // PRIORIDADE 2: Animal + Cor concordam (OCR falhou mas o resto bate)
                else if (tfliteLabels.isNotEmpty() && tfliteLabels.contains(colorValue)) {
                    detectedNow = colorValue
                    Log.d("ModoDinheiro", "Consenso atingido: TFLite + Cor ($detectedNow)")
                }
                // PRIORIDADE 3: Apenas um deles detectou, requeremos persistência maior
                else {
                    val singleSourceCandidate = ocrValue ?: (if (tfliteLabels.isNotEmpty()) tfliteLabels.first() else null) ?: colorValue
                    
                    if (singleSourceCandidate != null && singleSourceCandidate == currentCandidate) {
                        consecutiveMatches++
                    } else {
                        consecutiveMatches = 1
                        currentCandidate = singleSourceCandidate ?: ""
                    }
                    
                    // Aumentamos drasticamente a persistência para a nota de 200 se for apenas por Cor
                    // para evitar que paredes sejam confundidas.
                    val requiredMatches = if (singleSourceCandidate == "200 reais" && ocrValue == null) 10 else 4

                    if (consecutiveMatches >= requiredMatches) {
                        detectedNow = singleSourceCandidate
                        Log.d("ModoDinheiro", "Consenso atingido por persistência ($consecutiveMatches/$requiredMatches): $detectedNow")
                    }
                }
            }

            // 3. Gerenciamento de Feedback (Visual e Auditivo)
            if (detectedNow != null) {
                val isDifferentValue = detectedNow != lastSpokenMoneyValue
                val timeSinceLastSpeak = now - lastSpokenMoneyTime
                
                val podeFalar = if (isDifferentValue) {
                    timeSinceLastSpeak > MIN_INTERVAL_BETWEEN_DIFFERENT_VALUES
                } else {
                    timeSinceLastSpeak > 5000 // Repete o mesmo valor após 5s
                }

                if (podeFalar) {
                    lastSpokenMoneyValue = detectedNow
                    lastSpokenMoneyTime = now
                    lastDetectedValue = detectedNow
                    lastDetectionTime = now
                    callback(detectedNow, true)
                } else {
                    // Mantém o estado detectado para o UI, mas sem falar
                    lastDetectedValue = detectedNow
                    lastDetectionTime = now
                    callback(detectedNow, false)
                }
            } else {
                // Temporal persistence: Se nada detectado por 1.5s, limpa o estado
                if (now - lastDetectionTime > 1500) {
                    lastDetectedValue = ""
                    callback("", false)
                } else {
                    callback(lastDetectedValue, false)
                }
            }
        }.addOnFailureListener {
            Log.e("ModoDinheiro", "Erro no processamento das tasks", it)
            callback("", false)
        }
    }

    private fun extractValueFromText(text: String): String? {
        val t = text.uppercase()
        // 1. Palavras por extenso (Altíssima confiança)
        if (t.contains("DUZENTOS")) return "200 reais"
        if (t.contains("CEM")) return "100 reais"
        if (t.contains("CINQUENTA")) return "50 reais"
        if (t.contains("VINTE")) return "20 reais"
        if (t.contains("DEZ")) return "10 reais"
        if (t.contains("CINCO")) return "5 reais"
        if (t.contains("DOIS")) return "2 reais"

        // 2. Números isolados (Regex \b evita capturar partes de números de série)
        val isolated200 = Regex("\\b200\\b").containsMatchIn(t)
        val isolated100 = Regex("\\b100\\b").containsMatchIn(t)
        val isolated50 = Regex("\\b50\\b").containsMatchIn(t)
        val isolated20 = Regex("\\b20\\b").containsMatchIn(t)
        val isolated10 = Regex("\\b10\\b").containsMatchIn(t)
        val isolated5 = Regex("\\b5\\b").containsMatchIn(t)
        val isolated2 = Regex("\\b2\\b").containsMatchIn(t)

        return when {
            isolated200 -> "200 reais"
            isolated100 -> "100 reais"
            isolated50 -> "50 reais"
            isolated20 -> "20 reais"
            isolated10 -> "10 reais"
            isolated5 -> "5 reais"
            isolated2 -> "2 reais"
            else -> null
        }
    }

    private fun mapLabelToNote(label: String): String? {
        val l = label.lowercase()
        return when {
            l.contains("200") || l.contains("lobo") || l.contains("wolf") -> "200 reais"
            l.contains("100") || l.contains("garoupa") || l.contains("grouper") || l.contains("fish") -> "100 reais"
            l.contains("50") || l.contains("onça") || l.contains("onca") || l.contains("jaguar") -> "50 reais"
            l.contains("20") || l.contains("mico") || l.contains("monkey") -> "20 reais"
            l.contains("10") || l.contains("arara") || l.contains("macaw") -> "10 reais"
            l.contains("5") || l.contains("garça") || l.contains("garca") || l.contains("heron") || l.contains("egret") -> "5 reais"
            l.contains("2") || l.contains("tartaruga") || l.contains("turtle") -> "2 reais"
            else -> null
        }
    }

    private fun identifyRealNoteByColor(bitmap: Bitmap): String? {
        val w = bitmap.width
        val h = bitmap.height
        val hsv = FloatArray(3)
        val counts = mutableMapOf<String, Int>()

        // Amostragem em grade 4x4 para melhor cobertura central
        val steps = 4
        val stepX = w / (steps + 1)
        val stepY = h / (steps + 1)
        var sampledPoints = 0
        
        for (i in 1..steps) {
            for (j in 1..steps) {
                val px = i * stepX
                val py = j * stepY
                if (px in 0 until w && py in 0 until h) {
                    Color.colorToHSV(bitmap.getPixel(px, py), hsv)
                    val hue = hsv[0]
                    val sat = hsv[1]
                    val val_ = hsv[2]

                    // Filtro de saturação mínima para ignorar paredes e fundos neutros
                    if (val_ in 0.15f..0.90f && sat > 0.12f) {
                        val res = when {
                            // 100 Reais (Ciano/Azul claro)
                            hue in 170f..195f && sat > 0.15f -> "100 reais"
                            // 2 Reais (Azul escuro)
                            hue in 200f..255f && sat > 0.20f -> "2 reais"
                            // 5 Reais (Roxo/Violeta)
                            hue in 260f..320f && sat > 0.15f -> "5 reais"
                            // 10 Reais (Vermelho)
                            (hue in 0f..15f || hue in 345f..360f) && sat > 0.20f -> "10 reais"
                            // 20 Reais (Amarelo)
                            hue in 40f..65f && sat > 0.35f -> "20 reais"
                            // 50 Reais (Laranja/Marrom)
                            hue in 15f..38f && sat > 0.35f -> "50 reais"
                            // 200 Reais (Sépia/Cinza-Terra) - Range ultra restrito e saturação controlada
                            // A nota de 200 não é tão clara quanto uma parede bege e tem saturação média.
                            hue in 30f..45f && sat in 0.15f..0.28f && val_ in 0.30f..0.65f -> "200 reais"
                            else -> null
                        }
                        if (res != null) {
                            counts[res] = (counts[res] ?: 0) + 1
                        }
                        sampledPoints++
                    }
                }
            }
        }
        
        // Exige pelo menos 35% de concordância dos pontos válidos
        val threshold = (sampledPoints * 0.35).toInt().coerceAtLeast(3)
        return counts.entries.maxByOrNull { it.value }?.takeIf { it.value >= threshold }?.key
    }
}
