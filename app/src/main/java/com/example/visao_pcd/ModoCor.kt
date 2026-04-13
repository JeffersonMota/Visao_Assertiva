package com.example.visao_pcd

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect

class ModoCor {
    private var lastSpokenText = ""

    fun processar(bitmap: Bitmap, callback: (String, Rect) -> Unit) {
        val centerX = bitmap.width / 2
        val centerY = bitmap.height / 2
        
        // Analisamos um pequeno quadrado de 50x50 no centro para maior precisão
        val pixel = bitmap.getPixel(centerX, centerY)
        val colorName = identifyColor(Color.red(pixel), Color.green(pixel), Color.blue(pixel))
        
        // Criamos um retângulo central para o Overlay
        val rect = Rect(centerX - 50, centerY - 50, centerX + 50, centerY + 50)
        
        callback(colorName, rect)
    }

    private fun identifyColor(r: Int, g: Int, b: Int): String {
        val hsv = FloatArray(3)
        Color.RGBToHSV(r, g, b, hsv)
        val h = hsv[0]
        val s = hsv[1]
        val v = hsv[2]

        return when {
            v < 0.2f -> "Preto"
            v > 0.8f && s < 0.2f -> "Branco"
            s < 0.2f -> "Cinza"
            h in 0f..20f || h in 330f..360f -> "Vermelho"
            h in 20f..50f -> "Laranja"
            h in 50f..70f -> "Amarelo"
            h in 70f..160f -> "Verde"
            h in 160f..200f -> "Ciano"
            h in 200f..260f -> "Azul"
            h in 260f..300f -> "Roxo"
            h in 300f..330f -> "Rosa"
            else -> "Cor desconhecida"
        }
    }
}
