package com.example.visao_pcd

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.lifecycle.lifecycleScope
import com.example.visao_pcd.databinding.ActivityMainBinding
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.osmdroid.config.Configuration
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private var cameraProvider: ProcessCameraProvider? = null

    private lateinit var modoObjeto: ModoObjeto
    private lateinit var modoTexto: ModoTexto
    private lateinit var modoDinheiro: ModoDinheiro
    private lateinit var modoCor: ModoCor
    private lateinit var modoAndando: ModoAndando
    private lateinit var modoPessoa: ModoPessoa
    private lateinit var modoRoupa: ModoRoupa
    private lateinit var modoOnibus: ModoOnibus
    private lateinit var modoMicroondas: ModoMicroondas
    private lateinit var modoAmbiente: ModoAmbiente
    
    private lateinit var groqService: GroqService
    private lateinit var db: AppDatabase

    private var currentAppMode = AppMode.NENHUM
    private var isListeningVoiceCommand = false
    private var isAnalyzingAmbiente = false
    private var isAnalyzingPessoa = false
    private var isAnalyzingRoupa = false
    
    private var lastColorAnalysisTime = 0L
    private var lastCloudAnalysisTime = 0L
    private var lastAmbienteAnalysisTime = 0L
    private var lastAndandoAnalysisTime = 0L
    
    private var microondasHelper: ModoMicroondas.MicroondasTouchHelper? = null
    
    private lateinit var voiceCommandHelper: VoiceCommandHelper
    private lateinit var gestureDetector: GestureDetector

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        Configuration.getInstance().userAgentValue = packageName
        Configuration.getInstance().load(this, getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
        
        binding.mapOsm.setTileSource(org.osmdroid.tileprovider.tilesource.TileSourceFactory.MAPNIK)
        binding.mapOsm.setMultiTouchControls(true)
        binding.mapOsm.controller.setZoom(18.0)
        binding.mapOsm.setBuiltInZoomControls(false)

        cameraExecutor = Executors.newSingleThreadExecutor()
        tts = TextToSpeech(this, this)

        db = AppDatabase.getDatabase(this)
        groqService = GroqService()
        
        modoObjeto = ModoObjeto(this)
        modoTexto = ModoTexto()
        modoDinheiro = ModoDinheiro(this)
        modoCor = ModoCor()
        modoAndando = ModoAndando(modoObjeto, groqService)
        modoPessoa = ModoPessoa(groqService)
        modoRoupa = ModoRoupa(groqService)
        modoOnibus = ModoOnibus(this, LocationServices.getFusedLocationProviderClient(this), OkHttpClient(), groqService, lifecycleScope, "AIzaSyCOp2I7jfprHoqYT-D4ZEs9lwbcEoajOOI")
        modoMicroondas = ModoMicroondas(groqService)
        modoAmbiente = ModoAmbiente(groqService)

        voiceCommandHelper = VoiceCommandHelper(this, { processarComandoVoz(it) }, { isListening ->
            isListeningVoiceCommand = isListening
            if (isListening) falar("Ouvindo...", force = true)
        }, tts)

        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (currentAppMode == AppMode.AMBIENTE && !isAnalyzingAmbiente) {
                    isAnalyzingAmbiente = true
                    val bitmap = binding.viewFinder.bitmap
                    if (bitmap != null) {
                        runOnUiThread { iniciarContagemRegressivaAmbiente(bitmap) }
                    }
                    return true
                } else if (currentAppMode == AppMode.PESSOA && !isAnalyzingPessoa) {
                    isAnalyzingPessoa = true
                    val bitmap = binding.viewFinder.bitmap
                    if (bitmap != null) {
                        runOnUiThread { iniciarContagemRegressivaPessoa(bitmap) }
                    }
                    return true
                } else if (currentAppMode == AppMode.ROUPA && !isAnalyzingRoupa) {
                    isAnalyzingRoupa = true
                    val bitmap = binding.viewFinder.bitmap
                    if (bitmap != null) {
                        runOnUiThread { iniciarContagemRegressivaRoupa(bitmap) }
                    }
                    return true
                }
                return false
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                // Ao dar dois toques, voltamos ao menu principal (atalho de navegação)
                runOnUiThread {
                    voltarAoMenuPrincipal()
                }
                return true
            }
        })

        if (allPermissionsGranted()) startCamera()
        else ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)

        setupButtons()
    }

    override fun onResume() {
        super.onResume()
        binding.mapOsm.onResume()
    }

    override fun onPause() {
        super.onPause()
        binding.mapOsm.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        tts?.stop()
        tts?.shutdown()
    }

    private fun setupButtons() {
        binding.viewFinder.setOnTouchListener { _, event -> gestureDetector.onTouchEvent(event); true }
        binding.overlayView.setOnTouchListener { _, event -> gestureDetector.onTouchEvent(event); true }
        
        binding.menuBtnObjetos.setOnClickListener { switchMode(AppMode.OBJETOS) }
        binding.menuBtnTexto.setOnClickListener { switchMode(AppMode.TEXTO) }
        binding.menuBtnDinheiro.setOnClickListener { switchMode(AppMode.DINHEIRO) }
        binding.menuBtnCor.setOnClickListener { switchMode(AppMode.COR) }
        binding.menuBtnAmbiente.setOnClickListener { switchMode(AppMode.AMBIENTE) }
        binding.menuBtnAndando.setOnClickListener { switchMode(AppMode.ANDANDO) }
        binding.menuBtnPessoa.setOnClickListener { switchMode(AppMode.PESSOA) }
        binding.menuBtnRoupa.setOnClickListener { switchMode(AppMode.ROUPA) }
        binding.menuBtnOnibus.setOnClickListener { switchMode(AppMode.ONIBUS) }
        binding.menuBtnMicroondas.setOnClickListener { switchMode(AppMode.MICROONDAS) }
        
        binding.btnCloseResult.setOnClickListener {
            binding.resultLayout.visibility = View.GONE
            binding.ivCapturedImage.visibility = View.GONE
            binding.tvLiveTextFeedback.visibility = View.GONE
            binding.overlayView.updateBoundingBoxes(emptyList())
        }

        binding.toolbar.setNavigationOnClickListener {
            voltarAoMenuPrincipal()
        }
    }

    private fun voltarAoMenuPrincipal() {
        currentAppMode = AppMode.NENHUM
        binding.mainMenu.visibility = View.VISIBLE
        binding.appBar.visibility = View.GONE
        binding.resultLayout.visibility = View.GONE
        binding.ivCapturedImage.visibility = View.GONE
        binding.tvLiveTextFeedback.visibility = View.GONE
        binding.mapOsm.visibility = View.GONE
        binding.navPanel.visibility = View.GONE
        binding.overlayView.updateMode(AppMode.NENHUM)
        binding.overlayView.updateBoundingBoxes(emptyList())
        resetFlags()
        falar("Menu principal", force = true)
    }

    private fun resetFlags() {
        isAnalyzingAmbiente = false
        isAnalyzingPessoa = false
        isAnalyzingRoupa = false
        lastColorAnalysisTime = 0L
        lastCloudAnalysisTime = 0L
        lastAmbienteAnalysisTime = 0L
        lastAndandoAnalysisTime = 0L
        binding.tvCountdown.visibility = View.GONE
    }

    private fun switchMode(mode: AppMode) {
        currentAppMode = mode
        resetFlags() 
        binding.mainMenu.visibility = View.GONE
        binding.appBar.visibility = View.VISIBLE
        binding.overlayView.updateMode(mode)
        binding.resultLayout.visibility = View.GONE
        binding.ivCapturedImage.visibility = View.GONE
        binding.tvLiveTextFeedback.visibility = View.GONE

        if (mode == AppMode.AMBIENTE) {
            lastAmbienteAnalysisTime = Long.MAX_VALUE // Evita análise automática
            falar("Modo ambiente ativado. Toque uma vez na tela para descrever o local.", force = true)
        } else if (mode == AppMode.PESSOA) {
            lastCloudAnalysisTime = 0L
            falar("Modo pessoa. Identificando agora.", force = true)
        } else if (mode == AppMode.ONIBUS) {
            binding.mapOsm.visibility = View.VISIBLE
            binding.navPanel.visibility = View.VISIBLE
            binding.mapOsm.invalidate()
            falar("Modo ônibus ativado. Buscando paradas próximas...", force = true)
            modoOnibus.buscarParadasGoogle(binding.mapOsm) { falar(it, force = true) }
        } else {
            binding.mapOsm.visibility = View.GONE
            binding.navPanel.visibility = View.GONE
            val nomeModo = when(mode) {
                AppMode.OBJETOS -> "objetos"; AppMode.TEXTO -> "texto"; AppMode.DINHEIRO -> "dinheiro"
                AppMode.COR -> "cores"; AppMode.ANDANDO -> "andando"; AppMode.PESSOA -> "pessoa"
                AppMode.ROUPA -> "roupa"; AppMode.ONIBUS -> "ônibus"; AppMode.MICROONDAS -> "micro-ondas"
                else -> "nenhum"
            }
            falar("Modo $nomeModo ativado", force = true)
        }
    }

    private fun processarComandoVoz(comando: String) {
        when {
            comando.contains("menu") || comando.contains("voltar") -> {
                runOnUiThread {
                    voltarAoMenuPrincipal()
                }
            }
            comando.contains("objeto") -> switchMode(AppMode.OBJETOS)
            comando.contains("texto") || comando.contains("ler") -> switchMode(AppMode.TEXTO)
            comando.contains("cor") -> switchMode(AppMode.COR)
            comando.contains("pessoa") -> switchMode(AppMode.PESSOA)
            else -> falar("Comando não reconhecido")
        }
    }

    private fun falar(texto: String, force: Boolean = false) {
        if (!isTtsReady) return
        val queueMode = if (force) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        tts?.speak(texto, queueMode, null, null)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale("pt", "BR")
            isTtsReady = true
            falar("Sistema iniciado. Escolha um modo.")
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(binding.viewFinder.surfaceProvider) }
            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(cameraExecutor) { imageProxy -> processImageAnalysis(imageProxy) } }
            try {
                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalyzer)
            } catch (e: Exception) { Log.e("Camera", "Falha ao iniciar", e) }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun processImageAnalysis(imageProxy: ImageProxy) {
        val now = System.currentTimeMillis()
        when (currentAppMode) {
            AppMode.OBJETOS -> {
                val bitmap = imageProxy.toBitmap() // Já rotacionado corretamente pelo toBitmap()
                modoObjeto.processar(bitmap) { resultado ->
                    runOnUiThread {
                        binding.overlayView.setImageSourceInfo(bitmap.width, bitmap.height)
                        binding.overlayView.updateBoundingBoxes(resultado.boxes)
                        resultado.falas.forEach { falar(it) }
                    }
                }
                imageProxy.close()
            }
            AppMode.PESSOA -> {
                val bitmap = imageProxy.toBitmap() // Já rotacionado corretamente pelo toBitmap()
                modoObjeto.processar(bitmap) { resultado ->
                    val temHumano = resultado.boxes.any { 
                        val label = it.label?.lowercase() ?: ""
                        label.contains("pessoa") || label.contains("person") || label.contains("homem") || label.contains("mulher")
                    }
                    runOnUiThread {
                        binding.overlayView.setImageSourceInfo(bitmap.width, bitmap.height)
                        binding.overlayView.updateBoundingBoxes(resultado.boxes)
                    }
                    if (temHumano && !isAnalyzingPessoa && (now - lastCloudAnalysisTime > 15000)) {
                        lastCloudAnalysisTime = now
                        isAnalyzingPessoa = true
                        runOnUiThread { iniciarContagemRegressivaPessoa(bitmap) }
                    }
                }
                imageProxy.close()
            }
            AppMode.AMBIENTE -> {
                // Remove a análise automática baseada em tempo
                imageProxy.close()
            }
            else -> imageProxy.close()
        }
    }

    private fun iniciarContagemRegressivaPessoa(bitmap: Bitmap) {
        // Removida a rotação extra, o bitmap já vem na vertical
        falar("Atenção: pessoa identificada. Fique parado para a foto.", force = true)
        val handler = Handler(Looper.getMainLooper())
        var count = 5
        
        binding.tvCountdown.visibility = View.VISIBLE
        
        val runnable = object : Runnable {
            override fun run() {
                if (count >= 0) {
                    binding.tvCountdown.text = count.toString()
                    falar(count.toString(), force = true)
                    count--
                    handler.postDelayed(this, 1000)
                } else {
                    binding.tvCountdown.visibility = View.GONE
                    // Bater a foto (Congelar a tela)
                    binding.ivCapturedImage.setImageBitmap(bitmap)
                    binding.ivCapturedImage.visibility = View.VISIBLE
                    binding.resultLayout.visibility = View.GONE
                    falar("Foto capturada. Analisando...", force = true)
                    
                    if (isImageTooDark(bitmap)) {
                        falar("A imagem está um pouco escura. Tente iluminar o rosto da pessoa.", force = true)
                    }

                    modoPessoa.processar(bitmap) { resposta ->
                        isAnalyzingPessoa = false
                        runOnUiThread {
                            if (resposta.isNotEmpty()) {
                                // Adiciona dica de enquadramento se ninguém for detectado
                                val respostaFinal = if (resposta.contains("ninguém", ignoreCase = true) || 
                                    (resposta.contains("não", ignoreCase = true) && resposta.contains("pessoa", ignoreCase = true))) {
                                    "$resposta. Dica: Tente afastar um pouco o celular para enquadrar melhor."
                                } else resposta

                                binding.tvExtractedText.text = respostaFinal
                                binding.resultLayout.visibility = View.VISIBLE
                                falar(respostaFinal, force = true)
                                salvarConsulta("Pessoa", respostaFinal)
                            } else {
                                binding.ivCapturedImage.visibility = View.GONE
                            }
                        }
                    }
                }
            }
        }
        handler.post(runnable)
    }

    private fun ImageProxy.toBitmap(): Bitmap {
        val yBuffer = planes[0].buffer
        val vBuffer = planes[2].buffer
        val uBuffer = planes[1].buffer
        val ySize = yBuffer.remaining()
        val vSize = vBuffer.remaining()
        val uSize = uBuffer.remaining()
        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)
        val yuvImage = android.graphics.YuvImage(nv21, android.graphics.ImageFormat.NV21, this.width, this.height, null)
        val out = java.io.ByteArrayOutputStream()
        yuvImage.compressToJpeg(android.graphics.Rect(0, 0, yuvImage.width, yuvImage.height), 100, out)
        val imageBytes = out.toByteArray()
        val bitmap = android.graphics.BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
        // Rotaciona automaticamente com base nos metadados da câmera
        return bitmap.rotate(this.imageInfo.rotationDegrees.toFloat())
    }

    private fun iniciarContagemRegressivaAmbiente(bitmap: Bitmap) {
        // Removida a rotação extra, o bitmap do viewFinder já é vertical no celular
        falar("Iniciando análise do ambiente em 3 segundos. Mantenha o celular firme.", force = true)
        val handler = Handler(Looper.getMainLooper())
        var count = 3
        binding.tvCountdown.visibility = View.VISIBLE
        
        val runnable = object : Runnable {
            override fun run() {
                if (count >= 0) {
                    binding.tvCountdown.text = count.toString()
                    falar(count.toString(), force = true)
                    count--
                    handler.postDelayed(this, 1000)
                } else {
                    binding.tvCountdown.visibility = View.GONE
                    binding.ivCapturedImage.setImageBitmap(bitmap)
                    binding.ivCapturedImage.visibility = View.VISIBLE
                    binding.resultLayout.visibility = View.GONE
                    falar("Capturando ambiente. Analisando...", force = true)
                    
                    if (isImageTooDark(bitmap)) {
                        falar("O ambiente parece estar muito escuro. Isso pode dificultar a identificação.", force = true)
                    }

                    modoAmbiente.processar(bitmap) { resposta ->
                        isAnalyzingAmbiente = false
                        runOnUiThread {
                            if (resposta.isNotEmpty()) {
                                binding.tvExtractedText.text = resposta
                                binding.resultLayout.visibility = View.VISIBLE
                                falar(resposta, force = true)
                                salvarConsulta("Ambiente", resposta)
                            } else {
                                binding.ivCapturedImage.visibility = View.GONE
                            }
                        }
                    }
                }
            }
        }
        handler.post(runnable)
    }

    private fun iniciarContagemRegressivaRoupa(bitmap: Bitmap) {
        falar("Analizando roupa. Mantenha o celular firme.", force = true)
        val handler = Handler(Looper.getMainLooper())
        var count = 3
        binding.tvCountdown.visibility = View.VISIBLE
        
        val runnable = object : Runnable {
            override fun run() {
                if (count >= 0) {
                    binding.tvCountdown.text = count.toString()
                    falar(count.toString(), force = true)
                    count--
                    handler.postDelayed(this, 1000)
                } else {
                    binding.tvCountdown.visibility = View.GONE
                    binding.ivCapturedImage.setImageBitmap(bitmap)
                    binding.ivCapturedImage.visibility = View.VISIBLE
                    binding.resultLayout.visibility = View.GONE
                    falar("Foto capturada. Identificando cores e tecido...", force = true)

                    modoRoupa.processar(bitmap) { resposta ->
                        isAnalyzingRoupa = false
                        runOnUiThread {
                            if (resposta.isNotEmpty()) {
                                binding.tvExtractedText.text = resposta
                                binding.resultLayout.visibility = View.VISIBLE
                                falar(resposta, force = true)
                                salvarConsulta("Roupa", resposta)
                            } else {
                                binding.ivCapturedImage.visibility = View.GONE
                            }
                        }
                    }
                }
            }
        }
        handler.post(runnable)
    }

    private fun isImageTooDark(bitmap: Bitmap): Boolean {
        // Redimensiona para miniatura para processamento rápido
        val resized = Bitmap.createScaledBitmap(bitmap, 32, 32, true)
        var totalLuminance = 0L
        for (y in 0 until resized.height) {
            for (x in 0 until resized.width) {
                val color = resized.getPixel(x, y)
                val r = (color shr 16) and 0xFF
                val g = (color shr 8) and 0xFF
                val b = color and 0xFF
                // Fórmula de luminância padrão (ITU-R BT.601)
                totalLuminance += (0.299 * r + 0.587 * g + 0.114 * b).toLong()
            }
        }
        val avgLuminance = totalLuminance / (resized.width * resized.height)
        resized.recycle()
        return avgLuminance < 45 // Valor abaixo de 45 é considerado muito escuro
    }

    private fun Bitmap.rotate(degrees: Float): Bitmap {
        val matrix = android.graphics.Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
    }

    private fun salvarConsulta(tipo: String, resposta: String) {
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                db.consultaDao().salvar(Consulta(modo = tipo, resultado = resposta))
            } catch (e: Exception) { Log.e("DB", "Erro ao salvar", e) }
        }
    }

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION)
    }
}
