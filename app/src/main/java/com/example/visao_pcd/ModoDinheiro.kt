package com.example.visao_pcd

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class ModoDinheiro {
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    
    @Volatile
    var estaProcessando = false
    private var lastSpokenTime = 0L
    private val INTERVALO_FALA = 3000L // 3 segundos entre detecções

    interface Callback {
        fun onValorDetectado(valor: String)
        fun onError(e: Exception)
    }

    fun processar(bitmap: Bitmap, callback: Callback) {
        if (estaProcessando) return
        estaProcessando = true

        val image = InputImage.fromBitmap(bitmap, 0)
        
        textRecognizer.process(image)
            .addOnSuccessListener { visionText ->
                val textoOcr = visionText.text.uppercase()
                val agora = System.currentTimeMillis()
                
                // 1. Tenta identificar pelo OCR (Regex de valor exato)
                val valorOcr = extrairValorPorRegex(textoOcr)
                
                // 2. Se o OCR falhar, tenta identificar pela cor HSV
                val valorFinal = valorOcr ?: identificarPorCorHSV(bitmap)

                if (valorFinal != null && (agora - lastSpokenTime > INTERVALO_FALA)) {
                    lastSpokenTime = agora
                    callback.onValorDetectado(valorFinal)
                }
                estaProcessando = false
            }
            .addOnFailureListener {
                estaProcessando = false
                callback.onError(it)
            }
    }

    private fun extrairValorPorRegex(text: String): String? {
        return when {
            text.contains(Regex("\\b200\\b")) -> "200 reais"
            text.contains(Regex("\\b100\\b")) -> "100 reais"
            text.contains(Regex("\\b50\\b")) -> "50 reais"
            text.contains(Regex("\\b20\\b")) -> "20 reais"
            text.contains(Regex("\\b10\\b")) -> "10 reais"
            text.contains(Regex("\\b5\\b")) -> "5 reais"
            text.contains(Regex("\\b2\\b")) -> "2 reais"
            else -> null
        }
    }

    private fun identificarPorCorHSV(bitmap: Bitmap): String? {
        val hsv = FloatArray(3)
        val pixel = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
        Color.colorToHSV(pixel, hsv)

        val hue = hsv[0]
        val sat = hsv[1]
        val value = hsv[2]

        if (sat < 0.25f || value < 0.25f) return null

        return when {
            hue in 170f..210f -> "100 reais"
            hue in 30f..65f -> "20 reais"
            (hue in 0f..20f || hue in 330f..360f) -> "10 reais"
            hue in 80f..150f -> "2 reais"
            hue in 20f..35f -> "50 reais"
            hue in 210f..270f -> "5 reais"
            else -> null
        }
    }
}
