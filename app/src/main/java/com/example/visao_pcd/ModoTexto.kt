package com.example.visao_pcd

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class ModoTexto {
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    interface Callback {
        fun onTextoLido(texto: String)
        fun onError(e: Exception)
    }

    fun processar(bitmap: Bitmap, callback: Callback) {
        val image = InputImage.fromBitmap(bitmap, 0)
        textRecognizer.process(image)
            .addOnSuccessListener { visionText ->
                if (visionText.text.isBlank()) {
                    callback.onTextoLido("Nenhum texto encontrado.")
                    return@addOnSuccessListener
                }

                // Reconstroi o texto de forma coesa
                val cleanText = visionText.text
                    .replace(Regex("(?<=\\b\\w) (?=\\w\\b)"), "") // Corrige soletração: "T E X T O" -> "TEXTO"
                    .replace("\n", " ")
                    .replace(Regex("\\s+"), " ")
                    .trim()

                callback.onTextoLido(if (cleanText.length < 1) "Texto ilegível." else cleanText)
            }
            .addOnFailureListener { callback.onError(it) }
    }
}
