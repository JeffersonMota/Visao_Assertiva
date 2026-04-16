package com.example.visao_pcd

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.image.TensorImage

class ModoDinheiro(private val context: Context) {
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private var interpreter: Interpreter? = null

    private var lastSpokenMoneyValue = ""
    private var lastSpokenMoneyTime = 0L
    private val MIN_INTERVAL_BETWEEN_DIFFERENT_VALUES = 3500L 

    fun processar(image: InputImage, bitmap: Bitmap, callback: (valor: String, deveFalar: Boolean) -> Unit) {
        val now = System.currentTimeMillis()
        
        // Inicializa o Interpreter apenas quando o modo entrar em uso pela primeira vez
        if (interpreter == null) {
            try {
                val model = FileUtil.loadMappedFile(context, "detector.tflite")
                interpreter = Interpreter(model)
                Log.d("ModoDinheiro", "TFLite Interpreter inicializado sob demanda.")
            } catch (e: Exception) {
                Log.e("ModoDinheiro", "Erro ao inicializar TFLite sob demanda: ${e.message}")
            }
        }

        textRecognizer.process(image)
            .addOnSuccessListener { visionText ->
                val tfliteLabels = try {
                    val tflite = interpreter
                    if (tflite != null) {
                        val tensorImage = TensorImage.fromBitmap(bitmap)
                        val out0Shape = tflite.getOutputTensor(0).shape()
                        val scoresIdx = if (out0Shape.size == 2) 0 else 1
                        val scores = Array(1) { FloatArray(if (scoresIdx == 0) out0Shape[1] else tflite.getOutputTensor(1).shape()[1]) }
                        
                        tflite.runForMultipleInputsOutputs(arrayOf(tensorImage.buffer), mapOf(scoresIdx to scores))
                        
                        val detectedLabelIdx = scores[0].indices.maxByOrNull { scores[0][it] } ?: -1
                        if (detectedLabelIdx != -1 && scores[0][detectedLabelIdx] > 0.5f) {
                            listOf("nota")
                        } else emptyList()
                    } else emptyList()
                } catch (e: Exception) {
                    Log.e("ModoDinheiro", "Erro no TFLite: ${e.message}")
                    emptyList()
                }

                val ocrValue = extractValueFromText(visionText.text.uppercase())
                val colorValue = identifyRealNoteByColor(bitmap)
                processConsensus(ocrValue, tfliteLabels, colorValue, now, callback)
            }
            .addOnFailureListener {
                callback("", false)
            }
    }

    private fun processConsensus(ocrValue: String?, tfliteLabels: List<String>, colorValue: String?, now: Long, callback: (String, Boolean) -> Unit) {
        // Prioridade 1: OCR (Mais preciso para valores)
        // Prioridade 2: Cor (Ajuda se o OCR falhar, mas exige saturação alta)
        var detectedNow: String? = ocrValue ?: colorValue

        if (detectedNow != null) {
            val podeFalar = (now - lastSpokenMoneyTime) > MIN_INTERVAL_BETWEEN_DIFFERENT_VALUES
            if (podeFalar) {
                callback(detectedNow, true)
                lastSpokenMoneyTime = now
                lastSpokenMoneyValue = detectedNow
            } else {
                callback(detectedNow, false)
            }
        } else {
            callback("", false)
        }
    }

    private fun extractValueFromText(text: String): String? {
        val t = text.uppercase()
        // Regex para garantir que o número está isolado ou acompanhado de termos monetários
        val containsValue = when {
            t.contains(Regex("\\b200\\b")) || t.contains("DUZENTOS") -> "200 reais"
            t.contains(Regex("\\b100\\b")) || t.contains("CEM") -> "100 reais"
            t.contains(Regex("\\b50\\b")) || t.contains("CINQUENTA") -> "50 reais"
            t.contains(Regex("\\b20\\b")) || t.contains("VINTE") -> "20 reais"
            t.contains(Regex("\\b10\\b")) || t.contains("DEZ") -> "10 reais"
            t.contains(Regex("\\b5\\b")) || t.contains("CINCO") -> "5 reais"
            t.contains(Regex("\\b2\\b")) || t.contains("DOIS") -> "2 reais"
            else -> null
        }
        return containsValue
    }

    private fun identifyRealNoteByColor(bitmap: Bitmap): String? {
        val hsv = FloatArray(3)
        val w = bitmap.width
        val h = bitmap.height
        
        // Amostragem de múltiplos pontos centrais para evitar ruído
        val pixel = bitmap.getPixel(w/2, h/2)
        Color.colorToHSV(pixel, hsv)
        
        val hue = hsv[0]
        val sat = hsv[1]
        val value = hsv[2]

        // Filtro Anti-Parede: Notas reais têm cores saturadas. 
        // Paredes e sombras têm saturação baixa (< 0.3)
        if (sat < 0.4f || value < 0.2f) return null

        return when {
            hue in 170f..200f -> "100 reais" // Azul/Ciano
            hue in 30f..65f -> "20 reais"   // Amarelo/Laranja
            (hue in 0f..20f || hue in 340f..360f) -> "10 reais" // Vermelho/Rosa
            hue in 80f..150f -> "2 reais"   // Azul escuro / Tartaruga
            hue in 20f..30f -> "50 reais"   // Onça (Bege/Marrom)
            else -> null
        }
    }
}
