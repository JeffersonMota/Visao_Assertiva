package com.example.visao_pcd

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.lifecycle.lifecycleScope
import com.example.visao_pcd.databinding.ActivityMainBinding
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import android.speech.tts.TextToSpeech
import java.util.Locale
import com.google.android.gms.location.LocationServices
import okhttp3.OkHttpClient
import android.view.GestureDetector
import android.view.MotionEvent

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    
    private var tts: TextToSpeech? = null
    private var isTtsReady = false

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
    private var isAwaitingHighQualityCapture = false
    private var isAnalyzingAmbiente = false
    private var isAnalyzingPessoa = false
    private var isAnalyzingRoupa = false
    private var lastDetectedObject = ""
    
    private var lastColorAnalysisTime = 0L
    private var lastCloudAnalysisTime = 0L
    
    private var microondasHelper: ModoMicroondas.MicroondasTouchHelper? = null
    
    private lateinit var voiceCommandHelper: VoiceCommandHelper
    private lateinit var gestureDetector: GestureDetector

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

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
        modoOnibus = ModoOnibus(
            this,
            com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(this),
            okhttp3.OkHttpClient(),
            groqService,
            lifecycleScope,
            "AIzaSyCOp2I7jfprHoqYT-D4ZEs9lwbcEoajOOI"
        )
        modoMicroondas = ModoMicroondas(groqService)
        modoAmbiente = ModoAmbiente(groqService)

        voiceCommandHelper = VoiceCommandHelper(this, { comando ->
            processarComandoVoz(comando)
        }, { isListening ->
            isListeningVoiceCommand = isListening
            if (isListening) falar("Ouvindo...", force = true)
        }, tts)

        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                voiceCommandHelper.startListening()
                return true
            }
        })

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        setupButtons()
    }

    private fun setupButtons() {
        // Interação global por 2 toques, mas mantemos botões para acessibilidade padrão
        binding.viewFinder.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
        }
        binding.overlayView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
        }
        
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
            currentAppMode = AppMode.NENHUM
            binding.mainMenu.visibility = View.VISIBLE
            binding.appBar.visibility = View.GONE
            binding.resultLayout.visibility = View.GONE
            binding.ivCapturedImage.visibility = View.GONE
            binding.tvLiveTextFeedback.visibility = View.GONE
            binding.overlayView.updateMode(AppMode.NENHUM)
            binding.overlayView.updateBoundingBoxes(emptyList())
            falar("Menu principal")
        }
        
        // binding.btnHistorico.setOnClickListener { lerHistorico() }
    }

    private fun switchMode(mode: AppMode) {
        currentAppMode = mode
        binding.mainMenu.visibility = View.GONE
        binding.appBar.visibility = View.VISIBLE
        binding.overlayView.updateMode(mode)
        binding.resultLayout.visibility = View.GONE
        binding.ivCapturedImage.visibility = View.GONE
        binding.tvLiveTextFeedback.visibility = View.GONE
        
        if (mode == AppMode.MICROONDAS) {
            ViewCompat.setAccessibilityDelegate(binding.overlayView, null)
            microondasHelper = null
        }
        
        val nomeModo = when(mode) {
            AppMode.OBJETOS -> "objetos"
            AppMode.TEXTO -> "texto"
            AppMode.DINHEIRO -> "dinheiro"
            AppMode.COR -> "cores"
            AppMode.AMBIENTE -> "ambiente"
            AppMode.ANDANDO -> "andando"
            AppMode.PESSOA -> "pessoa"
            AppMode.ROUPA -> "roupa"
            AppMode.ONIBUS -> "ônibus"
            AppMode.MICROONDAS -> "micro-ondas"
            else -> "nenhum"
        }
        falar("Modo $nomeModo ativado")
    }

    private fun processarComandoVoz(comando: String) {
        Log.d("VoiceCommand", "Comando recebido: $comando")
        when {
            comando.contains("menu") || comando.contains("voltar") -> {
                runOnUiThread {
                    currentAppMode = AppMode.NENHUM
                    binding.mainMenu.visibility = View.VISIBLE
                    binding.appBar.visibility = View.GONE
                    binding.resultLayout.visibility = View.GONE
                    binding.ivCapturedImage.visibility = View.GONE
                    binding.tvLiveTextFeedback.visibility = View.GONE
                    binding.overlayView.updateMode(AppMode.NENHUM)
                    binding.overlayView.updateBoundingBoxes(emptyList())
                    falar("Menu principal", force = true)
                }
            }
            comando.contains("objeto") -> switchMode(AppMode.OBJETOS)
            comando.contains("texto") || comando.contains("ler") -> switchMode(AppMode.TEXTO)
            comando.contains("dinheiro") || comando.contains("nota") -> switchMode(AppMode.DINHEIRO)
            comando.contains("cor") -> switchMode(AppMode.COR)
            comando.contains("ambiente") -> switchMode(AppMode.AMBIENTE)
            comando.contains("andando") || comando.contains("rua") -> switchMode(AppMode.ANDANDO)
            comando.contains("pessoa") || comando.contains("alguém") -> switchMode(AppMode.PESSOA)
            comando.contains("roupa") || comando.contains("vestido") -> switchMode(AppMode.ROUPA)
            comando.contains("ônibus") || comando.contains("onibus") -> switchMode(AppMode.ONIBUS)
            comando.contains("micro-ondas") || comando.contains("microondas") -> switchMode(AppMode.MICROONDAS)
            comando.contains("ajuda") -> falar("Diga o nome do modo, como: texto, objeto, cor, dinheiro, ambiente, andando, pessoa, roupa, ônibus ou micro-ondas. Ou diga menu para voltar.")
            else -> falar("Comando não reconhecido: $comando")
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
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
            }

            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setResolutionSelector(
                    androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            androidx.camera.core.resolutionselector.ResolutionStrategy(
                                android.util.Size(1280, 720),
                                androidx.camera.core.resolutionselector.ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                            )
                        )
                        .build()
                )
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { imageProxy ->
                        processImageAnalysis(imageProxy)
                    }
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider?.unbindAll()
                camera = cameraProvider?.bindToLifecycle(
                    this, cameraSelector, preview, imageAnalyzer
                )
            } catch (exc: Exception) {
                Log.e("MainActivity", "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun salvarConsulta(modo: String, resultado: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val consulta = Consulta(modo = modo, resultado = resultado)
            db.consultaDao().salvar(consulta)
            Log.d("Historico", "Consulta salva: $resultado")
        }
    }

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    private fun processImageAnalysis(imageProxy: ImageProxy) {
        if (isListeningVoiceCommand) {
            imageProxy.close()
            return
        }
        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            val now = System.currentTimeMillis()
            
            runOnUiThread {
                if (currentAppMode != AppMode.OBJETOS && currentAppMode != AppMode.MICROONDAS) {
                    binding.overlayView.setImageSourceInfo(imageProxy.width, imageProxy.height)
                    binding.overlayView.setRotation(imageProxy.imageInfo.rotationDegrees)
                }
            }

            when (currentAppMode) {
                AppMode.OBJETOS -> {
                    if (modoObjeto.estaProcessando) {
                        imageProxy.close()
                        return
                    }
                    
                    Log.d("MainActivity", "Processando frame no Modo Objeto")

                    val bitmap = imageProxy.toBitmap()
                    
                    runOnUiThread {
                        binding.overlayView.setImageSourceInfo(bitmap.width, bitmap.height)
                        binding.overlayView.setRotation(0)
                    }

                    modoObjeto.processar(bitmap) { resultado ->
                        Log.d("MainActivity", "Resultado Modo Objeto: ${resultado.boxes.size} caixas")
                        runOnUiThread {
                            binding.overlayView.updateBoundingBoxes(resultado.boxes)
                        }
                        
                        if (resultado.falas.isNotEmpty()) {
                            // Estilo Lookout: Fala consolidada (ex: "Cadeira e Mesa")
                            // O ModoObjeto já controla o intervalo de 1.5s e 10s para repetições
                            val textoParaFalar = resultado.falas.joinToString(" e ")
                            falar(textoParaFalar, force = false)
                        }
                        
                        imageProxy.close()
                    }
                }
                AppMode.TEXTO -> {
                    if (isAwaitingHighQualityCapture) {
                        isAwaitingHighQualityCapture = false
                        val rawBitmap = imageProxy.toBitmap()
                        camera?.cameraControl?.enableTorch(false)

                        runOnUiThread {
                            binding.ivCapturedImage.setImageBitmap(rawBitmap)
                            binding.ivCapturedImage.visibility = View.VISIBLE
                            binding.tvExtractedText.text = "Lendo texto da imagem..."
                            binding.resultLayout.visibility = View.VISIBLE
                            binding.tvLiveTextFeedback.visibility = View.GONE
                        }

                        modoTexto.processarAltaQualidade(rawBitmap) { textoFinal, orientedBitmap ->
                            runOnUiThread {
                                binding.ivCapturedImage.setImageBitmap(orientedBitmap)
                                binding.tvExtractedText.text = textoFinal
                                falar("Texto detectado: $textoFinal", force = true)
                                salvarConsulta("Texto", textoFinal)
                            }
                        }
                        imageProxy.close()
                        return
                    }

                    if (binding.resultLayout.visibility == View.VISIBLE) {
                        imageProxy.close()
                        return
                    }

                    modoTexto.processar(image, object : ModoTexto.Callback {
                        override fun onEscaneando() {
                            runOnUiThread { binding.tvLiveTextFeedback.text = "Escaneando..." }
                            imageProxy.close()
                        }

                        override fun onFocando() {
                            falar("Texto detectado. Mantenha firme.")
                            runOnUiThread { binding.tvLiveTextFeedback.text = "Focando..." }
                            imageProxy.close()
                        }

                        override fun onTextoDetectado(texto: String, boxes: List<OverlayView.BoxData>) {
                            runOnUiThread {
                                binding.overlayView.updateBoundingBoxes(boxes)
                                if (texto.isNotBlank()) binding.tvLiveTextFeedback.visibility = View.VISIBLE
                                else binding.tvLiveTextFeedback.visibility = View.GONE
                            }
                            if (texto.isBlank()) imageProxy.close()
                        }

                        override fun onCapturarAltaQualidade() {
                            if (binding.resultLayout.visibility == View.VISIBLE) {
                                imageProxy.close()
                                return
                            }
                            camera?.cameraControl?.enableTorch(true)
                            runOnUiThread {
                                binding.tvLiveTextFeedback.text = "Focando e iluminando..."
                                binding.tvLiveTextFeedback.visibility = View.VISIBLE
                            }
                            imageProxy.close()
                            Handler(Looper.getMainLooper()).postDelayed({
                                isAwaitingHighQualityCapture = true
                            }, 500)
                        }

                        override fun onError(e: Exception) {
                            imageProxy.close()
                        }
                    })
                }
                AppMode.DINHEIRO -> {
                    val bitmap = imageProxy.toBitmap()
                    modoDinheiro.processar(image, bitmap) { valor, deveFalar ->
                        if (deveFalar && valor.isNotEmpty()) {
                            falar(valor, force = true)
                            salvarConsulta("Dinheiro", valor)
                        }
                        runOnUiThread {
                            binding.overlayView.updateDetectedMoney(valor)
                        }
                        imageProxy.close()
                    }
                }
                AppMode.COR -> {
                    if (now - lastColorAnalysisTime > 1000) {
                        lastColorAnalysisTime = now
                        val bitmap = imageProxy.toBitmap()
                        modoCor.processar(bitmap) { colorName, _ ->
                            falar(colorName, force = true)
                            salvarConsulta("Cor", colorName)
                            runOnUiThread {
                                binding.overlayView.updateDetectedColor(colorName)
                            }
                        }
                    }
                    imageProxy.close()
                }
                AppMode.AMBIENTE -> {
                    if (!isAnalyzingAmbiente) {
                        isAnalyzingAmbiente = true
                        val bitmap = imageProxy.toBitmap()
                        falar("Analisando ambiente...", force = true)
                        
                        modoAmbiente.processar(bitmap) { resposta: String ->
                            runOnUiThread {
                                binding.ivCapturedImage.setImageBitmap(bitmap)
                                binding.tvExtractedText.text = resposta
                                binding.resultLayout.visibility = View.VISIBLE
                                falar(resposta, force = true)
                                salvarConsulta("Ambiente", resposta)
                                isAnalyzingAmbiente = false
                                imageProxy.close()
                            }
                        }
                    } else {
                        imageProxy.close()
                    }
                }
                AppMode.ANDANDO -> {
                    val bitmap = imageProxy.toBitmap()
                    modoAndando.processar(bitmap) { hasObstacle ->
                        if (hasObstacle) {
                            falar("Objeto grande à frente", force = true)
                        }
                        imageProxy.close()
                    }
                }
                AppMode.PESSOA -> {
                    if (isAnalyzingPessoa) {
                        imageProxy.close()
                        return
                    }
                    isAnalyzingPessoa = true
                    val bitmap = imageProxy.toBitmap()
                    falar("Analisando pessoa...", force = true)
                    
                    modoPessoa.processar(bitmap) { resposta ->
                        runOnUiThread {
                            binding.ivCapturedImage.setImageBitmap(bitmap)
                            binding.tvExtractedText.text = resposta
                            binding.resultLayout.visibility = View.VISIBLE
                            falar(resposta, force = true)
                            salvarConsulta("Pessoa", resposta)
                            isAnalyzingPessoa = false
                        }
                        imageProxy.close()
                    }
                }
                AppMode.ROUPA -> {
                    if (isAnalyzingRoupa) {
                        imageProxy.close()
                        return
                    }
                    isAnalyzingRoupa = true
                    val bitmap = imageProxy.toBitmap()
                    falar("Analisando roupa...", force = true)

                    modoRoupa.processar(bitmap) { resposta ->
                        runOnUiThread {
                            binding.ivCapturedImage.setImageBitmap(bitmap)
                            binding.tvExtractedText.text = resposta
                            binding.resultLayout.visibility = View.VISIBLE
                            falar(resposta, force = true)
                            salvarConsulta("Roupa", resposta)
                            isAnalyzingRoupa = false
                        }
                        imageProxy.close()
                    }
                }
                AppMode.ONIBUS -> {
                    if (now - lastCloudAnalysisTime > 15000) {
                        lastCloudAnalysisTime = now
                        runOnUiThread {
                            binding.mapOsm.visibility = View.VISIBLE
                            modoOnibus.buscarParadasGoogle(binding.mapOsm) { fala ->
                                falar(fala, force = true)
                            }
                        }
                    }
                    imageProxy.close()
                }
                AppMode.MICROONDAS -> {
                    if (modoMicroondas.estaAnalisando()) {
                        imageProxy.close()
                        return
                    }
                    val bitmap = imageProxy.toBitmap()
                    modoMicroondas.analisarPainel(bitmap) { botoes ->
                        val boxes = botoes.map { botao ->
                            OverlayView.BoxData(
                                Rect(
                                    botao.rect.left.toInt(),
                                    botao.rect.top.toInt(),
                                    botao.rect.right.toInt(),
                                    botao.rect.bottom.toInt()
                                ),
                                botao.nome
                            )
                        }
                        runOnUiThread {
                            binding.overlayView.setImageSourceInfo(bitmap.width, bitmap.height)
                            binding.overlayView.setRotation(0)
                            binding.overlayView.updateBoundingBoxes(boxes)
                            if (microondasHelper == null) {
                                microondasHelper = modoMicroondas.MicroondasTouchHelper(binding.overlayView, binding.overlayView)
                                ViewCompat.setAccessibilityDelegate(binding.overlayView, microondasHelper)
                            }
                            microondasHelper?.invalidateRoot()
                        }
                        imageProxy.close()
                    }
                }
                else -> { imageProxy.close() }
            }
        } else { imageProxy.close() }
    }

    private fun ImageProxy.toBitmap(): Bitmap {
        val yBuffer = planes[0].buffer
        val uBuffer = planes[1].buffer
        val vBuffer = planes[2].buffer
        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()
        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)
        val yuvImage = android.graphics.YuvImage(nv21, android.graphics.ImageFormat.NV21, this.width, this.height, null)
        val out = java.io.ByteArrayOutputStream()
        yuvImage.compressToJpeg(android.graphics.Rect(0, 0, yuvImage.width, yuvImage.height), 100, out)
        val imageBytes = out.toByteArray()
        var bitmap = android.graphics.BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
        val rotation = imageInfo.rotationDegrees
        if (rotation != 0) {
            val matrix = android.graphics.Matrix()
            matrix.postRotate(rotation.toFloat())
            bitmap = android.graphics.Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }
        return bitmap
    }

    private fun lerHistorico() {
        lifecycleScope.launch(Dispatchers.IO) {
            val ultimas = db.consultaDao().listarUltimas()
            val texto = if (ultimas.isEmpty()) {
                "Você ainda não possui consultas no histórico."
            } else {
                "Suas últimas consultas são: " + ultimas.joinToString(". ") { it.resultado }
            }
            withContext(Dispatchers.Main) {
                falar(texto, force = true)
            }
        }
    }

    override fun onResume() { super.onResume(); binding.mapOsm.onResume() }
    override fun onPause() { super.onPause(); binding.mapOsm.onPause() }
    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        voiceCommandHelper.destroy()
        tts?.stop()
        tts?.shutdown()
    }

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION)
    }
}
