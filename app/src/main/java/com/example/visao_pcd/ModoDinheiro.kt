package com.example.visao_pcd

import android.graphics.Bitmap
import android.graphics.Color
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class ModoDinheiro {
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private var lastSpokenMoneyValue = ""
    private var lastSpokenMoneyTime = 0L
    private var lastMoneyColorCheck = 0L

    fun processar(image: InputImage, bitmap: Bitmap, callback: (String) -> Unit) {
        val now = System.currentTimeMillis()
        
        textRecognizer.process(image)
            .addOnSuccessListener { visionText ->
                val text = visionText.text.replace(" ", "").uppercase()
                var noteValue = when {
                    text.contains("200") -> "200 reais"
                    text.contains("100") -> "100 reais"
                    text.contains("50") -> "50 reais"
                    text.contains("20") -> "20 reais"
                    text.contains("10") -> "10 reais"
                    text.contains("5") -> "5 reais"
                    text.contains("2") -> "2 reais"
                    else -> null
                }

                // Fallback por cor se o OCR falhar
                if (noteValue == null && now - lastMoneyColorCheck > 3000) {
                    val pixel = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
                    noteValue = identifyNoteByColor(pixel)
                    lastMoneyColorCheck = now
                }

                if (noteValue != null) {
                    if (noteValue != lastSpokenMoneyValue || now - lastSpokenMoneyTime > 5000) {
                        lastSpokenMoneyValue = noteValue
                        lastSpokenMoneyTime = now
                        callback(noteValue)
                    }
                }
            }
    }

    private fun identifyNoteByColor(pixel: Int): String? {
        val hsv = FloatArray(3)
        Color.colorToHSV(pixel, hsv)
        val h = hsv[0]
        val s = hsv[1]
        val v = hsv[2]

        return when {
            h in 30f..55f && s > 0.3 -> "Possível nota de 50 reais"
            h in 10f..25f && s > 0.4 -> "Possível nota de 20 reais"
            (h in 340f..360f || h in 0f..15f) && s > 0.3 -> "Possível nota de 10 reais"
            h in 240f..285f && s > 0.2 -> "Possível nota de 5 ou 2 reais"
            h in 180f..230f && s > 0.3 -> "Possível nota de 100 reais"
            v in 0.4f..0.8f && s < 0.25f -> "Possível nota de 200 reais"
            else -> null
        }
    }
}
