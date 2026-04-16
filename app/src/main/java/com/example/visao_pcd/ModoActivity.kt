package com.example.visao_pcd

import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import okhttp3.OkHttpClient

abstract class ModoActivity : AppCompatActivity() {
    // Instâncias dos Modos Locais
    protected val client = OkHttpClient()
    protected lateinit var fusedLocationClient: FusedLocationProviderClient
    protected lateinit var groqService: GroqService
    protected lateinit var ollamaService: OllamaService

    protected lateinit var modoCor: ModoCor
    protected lateinit var modoObjeto: ModoObjeto
    protected lateinit var modoTexto: ModoTexto
    protected lateinit var modoDinheiro: ModoDinheiro
    protected lateinit var modoAndando: ModoAndando
    protected lateinit var modoAmbiente: ModoAmbiente
    protected lateinit var modoOnibus: ModoOnibus
    protected lateinit var modoPessoa: ModoPessoa
    protected lateinit var modoRoupa: ModoRoupa
    protected lateinit var modoMicroondas: ModoMicroondas

    protected fun inicializarModos() {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        groqService = GroqService()
        ollamaService = OllamaService()

        modoCor = ModoCor()
        modoObjeto = ModoObjeto(this)
        modoTexto = ModoTexto()
        modoDinheiro = ModoDinheiro(this)
        
        modoAmbiente = ModoAmbiente(groqService)
        modoAndando = ModoAndando(modoObjeto, groqService)
        modoPessoa = ModoPessoa(groqService)
        modoRoupa = ModoRoupa(groqService)
        modoMicroondas = ModoMicroondas(groqService)
        
        modoOnibus = ModoOnibus(
            context = this,
            fusedLocationClient = fusedLocationClient,
            client = client,
            groqService = groqService,
            scope = lifecycleScope,
            googleMapsKey = "" // Chave será pega do BuildConfig se necessário
        )
    }
}
