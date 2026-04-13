package com.example.visao_pcd

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class ModoTexto {
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    
    private var textStabilityCounter = 0
    private var lastTextContent = ""

    interface Callback {
        fun onEscaneando()
        fun onFocando()
        fun onTextoDetectado(texto: String, boxes: List<OverlayView.BoxData>)
        fun onCapturarAltaQualidade()
        fun onError(e: Exception)
    }

    fun processar(image: InputImage, callback: Callback) {
        textRecognizer.process(image)
            .addOnSuccessListener { visionText ->
                val detectedText = visionText.text.trim()
                val boxes = visionText.textBlocks.map { block ->
                    OverlayView.BoxData(block.boundingBox ?: Rect(), "")
                }

                callback.onTextoDetectado(detectedText, boxes)

                if (detectedText.length > 5) {
                    // Exige que o texto seja detectado por 3 frames antes de bater a foto
                    textStabilityCounter++
                    if (textStabilityCounter >= 3) {
                        callback.onCapturarAltaQualidade()
                        textStabilityCounter = 0
                    } else {
                        callback.onFocando()
                    }
                } else {
                    textStabilityCounter = 0
                    callback.onEscaneando()
                }
            }
            .addOnFailureListener {
                Log.e("ModoTexto", "Erro no processamento de texto", it)
                callback.onError(it)
            }
    }

    /**
     * Processa a imagem em alta qualidade, garantindo que a foto esteja na vertical
     * e o texto seja limpo para uma leitura fluida (sem soletrar).
     */
    fun processarAltaQualidade(bitmap: Bitmap, callback: (String) -> Unit) {
        // 1. Força a imagem a ficar na vertical (Portrait) antes do OCR
        val orientedBitmap = if (bitmap.width > bitmap.height) {
            val matrix = Matrix()
            matrix.postRotate(90f)
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } else {
            bitmap
        }

        val image = InputImage.fromBitmap(orientedBitmap, 0)
        textRecognizer.process(image)
            .addOnSuccessListener { visionText ->
                val rawText = visionText.text
                if (rawText.isBlank()) {
                    callback("Nenhum texto legível encontrado.")
                    return@addOnSuccessListener
                }

                // 2. Limpeza para leitura humanizada
                val cleanText = rawText
                    .replace("\n", " ")
                    .replace(Regex("\\s+"), " ")
                    .trim()
                
                callback(cleanText)

                callback(cleanText)
            }
            .addOnFailureListener {
                Log.e("ModoTexto", "Erro no processamento de alta qualidade", it)
                callback("Erro ao processar a imagem.")
            }
    }
}
