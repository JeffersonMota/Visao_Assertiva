package com.example.visao_pcd

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/**
 * BACKUP DE SEGURANÇA - MODO TEXTO (VERSÃO ESTÁVEL)
 * Por favor, não modifique este arquivo.
 */
class ModoTextoBackup {
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    
    private var textStabilityCounter = 0

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
                callback.onError(it)
            }
    }

    fun processarAltaQualidade(bitmap: Bitmap, callback: (String, Bitmap) -> Unit) {
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
                    callback("Nenhum texto legível encontrado.", orientedBitmap)
                    return@addOnSuccessListener
                }

                val cleanText = rawText
                    .replace("\n", " ")
                    .replace(Regex("\\s+"), " ")
                    .trim()
                
                callback(if (cleanText.length < 3) "Texto muito curto para ler com precisão." else cleanText, orientedBitmap)
            }
            .addOnFailureListener {
                callback("Erro ao processar a imagem.", orientedBitmap)
            }
    }
}
