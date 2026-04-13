package com.example.visao_pcd

import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.ai.client.generativeai.GenerativeModel
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import okhttp3.OkHttpClient

abstract class ModoActivity : AppCompatActivity() {
    // Instâncias dos Modos
    protected val modoCor = ModoCor()
    protected val modoObjeto = ModoObjeto()
    protected val modoTexto = ModoTexto()
    protected val modoDinheiro = ModoDinheiro()
    
    protected lateinit var modoAndando: ModoAndando
    protected lateinit var modoAmbiente: ModoAmbiente
    protected lateinit var modoOnibus: ModoOnibus
    protected lateinit var modoPessoa: ModoPessoa
    protected lateinit var modoRoupa: ModoRoupa
    protected lateinit var modoMicroondas: ModoMicroondas

    // Dependências necessárias para os modos
    protected val client = OkHttpClient()
    protected lateinit var geminiModel: GenerativeModel
    protected lateinit var fusedLocationClient: FusedLocationProviderClient

    protected fun inicializarModos(apiKeyGemini: String, apiKeyGoogleMaps: String) {
        geminiModel = GenerativeModel(modelName = "gemini-1.5-flash", apiKey = apiKeyGemini)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        modoAndando = ModoAndando(modoObjeto)
        modoAmbiente = ModoAmbiente(geminiModel)
        modoOnibus = ModoOnibus(this, fusedLocationClient, client, geminiModel, lifecycleScope, apiKeyGoogleMaps)
        modoOnibus.carregarCustomStops()
        modoPessoa = ModoPessoa(geminiModel)
        modoRoupa = ModoRoupa(geminiModel)
        modoMicroondas = ModoMicroondas(geminiModel)
    }
}
