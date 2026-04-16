package com.example.visao_pcd

import android.content.Context
import java.util.Properties

class LabelTranslator(context: Context) {
    private val translations = Properties()

    init {
        try {
            val assetManager = context.assets
            val inputStream = assetManager.open("labels_pt.properties")
            translations.load(inputStream)
        } catch (e: Exception) {
            // Se o arquivo não existir, usaremos os nomes originais
            e.printStackTrace()
        }
    }

    fun translate(englishLabel: String): String {
        // O Open Images usa espaços, o arquivo .properties usa underscores
        val key = englishLabel.replace(" ", "_")
        return translations.getProperty(key, englishLabel)
    }
}
