package com.example.visao_pcd

import android.content.Context
import android.util.Log
import org.json.JSONObject

class TranslationManager(context: Context) {
    private val labelMap = HashMap<String, String>()

    init {
        try {
            val jsonString = context.assets.open("open_images_br.json")
                .bufferedReader().use { it.readText() }
            val jsonObject = JSONObject(jsonString)
            
            jsonObject.keys().forEach { key ->
                labelMap[key.lowercase()] = jsonObject.getString(key)
            }
            Log.d("TranslationManager", "Carregadas ${labelMap.size} traduções.")
        } catch (e: Exception) {
            Log.e("TranslationManager", "Erro ao carregar open_images_br.json: ${e.message}")
        }
    }

    fun getTranslation(englishLabel: String): String {
        // Normaliza para lowercase para busca resiliente
        return labelMap[englishLabel.lowercase()] ?: englishLabel
    }
}
