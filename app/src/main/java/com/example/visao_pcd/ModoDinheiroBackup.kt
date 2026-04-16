package com.example.visao_pcd

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/**
 * BACKUP DE SEGURANÇA - MODO DINHEIRO (VERSÃO ESTÁVEL)
 * Por favor, não modifique este arquivo.
 */
class ModoDinheiroBackup(private val context: Context) {
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private var lastSpokenMoneyTime = 0L
    private val MIN_INTERVAL_BETWEEN_DIFFERENT_VALUES = 3500L 

    fun processar(image: InputImage, bitmap: Bitmap, callback: (valor: String, deveFalar: Boolean) -> Unit) {
        val now = System.currentTimeMillis()
        
        textRecognizer.process(image)
            .addOnSuccessListener { visionText ->
                val ocrValue = extractValueFromText(visionText.text.uppercase())
                val colorValue = identifyRealNoteByColor(bitmap)
                
                var detectedNow: String? = ocrValue ?: colorValue

                if (detectedNow != null) {
                    val podeFalar = (now - lastSpokenMoneyTime) > MIN_INTERVAL_BETWEEN_DIFFERENT_VALUES
                    if (podeFalar) {
                        callback(detectedNow, true)
                        lastSpokenMoneyTime = now
                    } else {
                        callback(detectedNow, false)
                    }
                } else {
                    callback("", false)
                }
            }
            .addOnFailureListener {
                callback("", false)
            }
    }

    private fun extractValueFromText(text: String): String? {
        val t = text.uppercase()
        return when {
            t.contains(Regex("\\b200\\b")) || t.contains("DUZENTOS") -> "200 reais"
            t.contains(Regex("\\b100\\b")) || t.contains("CEM") -> "100 reais"
            t.contains(Regex("\\b50\\b")) || t.contains("CINQUENTA") -> "50 reais"
            t.contains(Regex("\\b20\\b")) || t.contains("VINTE") -> "20 reais"
            t.contains(Regex("\\b10\\b")) || t.contains("DEZ") -> "10 reais"
            t.contains(Regex("\\b5\\b")) || t.contains("CINCO") -> "5 reais"
            t.contains(Regex("\\b2\\b")) || t.contains("DOIS") -> "2 reais"
            else -> null
        }
    }

    private fun identifyRealNoteByColor(bitmap: Bitmap): String? {
        val hsv = FloatArray(3)
        val w = bitmap.width
        val h = bitmap.height
        val pixel = bitmap.getPixel(w/2, h/2)
        Color.colorToHSV(pixel, hsv)
        
        val hue = hsv[0]
        val sat = hsv[1]
        val value = hsv[2]

        if (sat < 0.4f || value < 0.2f) return null

        return when {
            hue in 170f..200f -> "100 reais"
            hue in 30f..65f -> "20 reais"
            (hue in 0f..20f || hue in 340f..360f) -> "10 reais"
            hue in 80f..150f -> "2 reais"
            hue in 20f..30f -> "50 reais"
            else -> null
        }
    }
}
