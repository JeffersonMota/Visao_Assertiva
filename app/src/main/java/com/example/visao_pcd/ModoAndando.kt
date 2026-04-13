package com.example.visao_pcd

import com.google.mlkit.vision.common.InputImage

class ModoAndando(private val modoObjeto: ModoObjeto) {

    fun processar(image: InputImage, imageWidth: Int, imageHeight: Int, callback: (Boolean) -> Unit) {
        modoObjeto.processar(image, imageWidth) { resultado ->
            val hasObstacle = resultado.boxes.any { 
                val areaPercent = (it.rect.width() * it.rect.height()).toFloat() / (imageWidth * imageHeight)
                areaPercent > 0.35
            }
            callback(hasObstacle)
        }
    }
}
